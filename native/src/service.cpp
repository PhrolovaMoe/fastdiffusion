#include "service.h"

#include <chrono>
#include <future>
#include <stdexcept>

#include "log.h"

namespace fd {

namespace {
uint64_t micros_since(std::chrono::steady_clock::time_point t0) {
    return (uint64_t)std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now() - t0).count();
}
}  // namespace

Service::Service(const ServiceOptions& opts) : opts_(opts) {
    models_ = std::make_unique<Models>(opts.model_path, opts.engine);
    int workers = opts.cpu_workers;
    if (workers <= 0) workers = std::max(2, (int)std::thread::hardware_concurrency() / 3);
    for (int i = 0; i < workers; i++) cpu_threads_.emplace_back([this] { cpu_loop(); });
    gpu_thread_ = std::thread([this] { gpu_loop(); });
    log_info("service started: %d CPU workers, %zu MiB tile cache", workers, opts.cache_bytes >> 20);
}

Service::~Service() {
    {
        std::lock_guard<std::mutex> lk(mu_);
        stop_ = true;
    }
    cv_.notify_all();
    if (gpu_thread_.joinable()) gpu_thread_.join();
    // Fail whatever never reached the GPU so no caller waits forever.
    while (!queue_.empty()) {
        Request r = queue_.top();
        queue_.pop();
        if (r.task) r.task(false);
        else complete(r.id, "service shut down", !r.grid);
    }
    {
        std::lock_guard<std::mutex> lk(cpu_mu_);
        for (size_t i = 0; i < cpu_threads_.size(); i++) cpu_queue_.push_back(nullptr);  // drain, then stop
    }
    cpu_cv_.notify_all();
    for (auto& t : cpu_threads_) t.join();
    done_cv_.notify_all();
}

int Service::open_world(const WorldParams& params) {
    auto world = std::make_shared<World>(*models_, params, opts_.cache_bytes);
    std::lock_guard<std::mutex> lk(mu_);
    const int id = next_world_++;
    worlds_[id] = std::move(world);
    log_info("opened world %d: seed=%llu scale=%d y=[%d, %d)", id, (unsigned long long)params.seed, params.scale, params.min_y,
             params.min_y + params.height);
    return id;
}

void Service::close_world(int world) {
    std::lock_guard<std::mutex> lk(mu_);
    worlds_.erase(world);  // in-flight requests keep their own reference
}

uint64_t Service::request_region(int world, int64_t rx, int64_t rz, int priority, uint8_t* out) {
    std::lock_guard<std::mutex> lk(mu_);
    auto it = worlds_.find(world);
    if (it == worlds_.end()) throw std::invalid_argument("unknown world handle");
    const uint64_t id = next_id_++;
    queue_.push(Request{id, priority, next_seq_++, it->second, rx, rz, out, nullptr, nullptr});
    cv_.notify_one();
    return id;
}

uint64_t Service::request_grid(int world, const GridRequest& req, int priority, uint8_t* out) {
    std::lock_guard<std::mutex> lk(mu_);
    auto it = worlds_.find(world);
    if (it == worlds_.end()) throw std::invalid_argument("unknown world handle");
    const uint64_t id = next_id_++;
    queue_.push(Request{id, priority, next_seq_++, it->second, 0, 0, out, nullptr, std::make_shared<const GridRequest>(req)});
    cv_.notify_one();
    return id;
}

void Service::cancel(uint64_t id) {
    std::lock_guard<std::mutex> lk(mu_);
    cancelled_.insert(id);
}

void Service::find_land(int world, int64_t& bx, int64_t& bz) {
    auto done = std::make_shared<std::promise<void>>();
    std::future<void> fut = done->get_future();
    {
        std::lock_guard<std::mutex> lk(mu_);
        auto it = worlds_.find(world);
        if (it == worlds_.end()) throw std::invalid_argument("unknown world handle");
        std::shared_ptr<World> w = it->second;
        queue_.push(Request{0, -1000, next_seq_++, w, 0, 0, nullptr, [w, &bx, &bz, done](bool run) {
                                try {
                                    if (!run) throw std::runtime_error("service shut down");
                                    w->find_land(bx, bz);
                                    done->set_value();
                                } catch (...) {
                                    done->set_exception(std::current_exception());
                                }
                            }, nullptr});
    }
    cv_.notify_one();
    fut.get();
}

void Service::complete(uint64_t id, const std::string& error, bool region, bool cancelled) {
    {
        std::lock_guard<std::mutex> lk(mu_);
        cancelled_.erase(id);
    }
    {
        std::lock_guard<std::mutex> lk(done_mu_);
        if (!error.empty()) errors_[id] = error;
        done_.push_back({id, cancelled ? 2 : error.empty() ? 0 : 1});
    }
    if (region && !cancelled) (error.empty() ? regions_done_ : regions_failed_)++;
    done_cv_.notify_all();
}

int Service::poll(Completion* out, int max, int timeout_ms) {
    std::unique_lock<std::mutex> lk(done_mu_);
    done_cv_.wait_for(lk, std::chrono::milliseconds(timeout_ms), [&] { return !done_.empty(); });
    int n = 0;
    while (n < max && !done_.empty()) {
        out[n++] = done_.front();
        done_.pop_front();
    }
    return n;
}

std::string Service::take_error(uint64_t id) {
    std::lock_guard<std::mutex> lk(done_mu_);
    auto it = errors_.find(id);
    if (it == errors_.end()) return {};
    std::string e = std::move(it->second);
    errors_.erase(it);
    return e;
}

Service::Stats Service::stats() {
    Stats s{};
    s.regions_done = regions_done_;
    s.regions_failed = regions_failed_;
    s.gpu_batches = gpu_batches_;
    s.gpu_ms = gpu_us_ / 1000.0;
    s.cpu_ms = cpu_us_ / 1000.0;
    std::lock_guard<std::mutex> lk(mu_);
    s.queued = (int)queue_.size();
    return s;
}

void Service::submit_cpu(std::function<void()> fn) {
    {
        std::lock_guard<std::mutex> lk(cpu_mu_);
        cpu_queue_.push_back(std::move(fn));
    }
    cpu_cv_.notify_one();
}

void Service::cpu_loop() {
    for (;;) {
        std::function<void()> fn;
        {
            std::unique_lock<std::mutex> lk(cpu_mu_);
            cpu_cv_.wait(lk, [&] { return !cpu_queue_.empty(); });
            fn = std::move(cpu_queue_.front());
            cpu_queue_.pop_front();
        }
        if (!fn) return;
        fn();
    }
}

void Service::run_grid(const Request& r) {
    const auto t0 = std::chrono::steady_clock::now();
    auto in = std::make_shared<GridInputs>();
    try {
        Plan plan;
        r.world->plan_grid(*r.grid, plan);
        if (plan.missing_gpu_work() > 0) gpu_batches_++;
        r.world->execute(plan);
        *in = r.world->gather_grid(*r.grid, plan);
    } catch (const std::exception& e) {
        gpu_us_ += micros_since(t0);
        log_error("map grid failed: %s", e.what());
        complete(r.id, e.what(), false);
        return;
    }
    gpu_us_ += micros_since(t0);
    submit_cpu([this, r, in] {
        const auto c0 = std::chrono::steady_clock::now();
        std::string err;
        try {
            r.world->finalize_grid(*in, r.out);
        } catch (const std::exception& e) {
            err = e.what();
            log_error("map grid finalisation failed: %s", e.what());
        }
        cpu_us_ += micros_since(c0);
        complete(r.id, err, false);
    });
}

void Service::gpu_loop() {
    std::vector<Request> cancelled;
    for (;;) {
        std::vector<Request> group;
        {
            std::unique_lock<std::mutex> lk(mu_);
            cv_.wait(lk, [&] { return stop_ || !queue_.empty(); });
            if (stop_) break;
            // Requests cancelled before they started complete straight away.
            auto take = [&](std::vector<Request>& into) {
                Request r = queue_.top();
                queue_.pop();
                if (r.task || !cancelled_.count(r.id)) into.push_back(std::move(r));
                else cancelled.push_back(std::move(r));
            };
            take(group);
            // Batch further regions of the same world so the GPU sees large batches.
            if (!group.empty() && group[0].batchable()) {
                while (!queue_.empty() && (int)group.size() < opts_.max_group_regions && queue_.top().batchable() &&
                       queue_.top().world == group[0].world)
                    take(group);
            }
        }
        for (const Request& r : cancelled) complete(r.id, "cancelled", false, true);
        cancelled.clear();
        if (group.empty()) continue;

        if (group[0].task) {
            group[0].task(true);
            continue;
        }
        if (group[0].grid) {
            run_grid(group[0]);
            continue;
        }

        World& world = *group[0].world;
        const auto t0 = std::chrono::steady_clock::now();
        Plan plan;
        std::vector<RegionInputs> inputs;
        std::string error;
        try {
            for (const Request& r : group) world.plan_region(r.rx, r.rz, plan);
            if (plan.missing_gpu_work() > 0) gpu_batches_++;
            world.execute(plan);
            for (const Request& r : group) inputs.push_back(world.gather_region(r.rx, r.rz, plan));
        } catch (const std::exception& e) {
            error = e.what();
            log_error("terrain generation failed: %s", e.what());
        }
        gpu_us_ += micros_since(t0);

        for (size_t i = 0; i < group.size(); i++) {
            const Request r = group[i];
            if (!error.empty()) {
                complete(r.id, error);
                continue;
            }
            auto in = std::make_shared<RegionInputs>(std::move(inputs[i]));
            submit_cpu([this, r, in] {
                const auto c0 = std::chrono::steady_clock::now();
                std::string err;
                try {
                    r.world->finalize_region(*in, r.out);
                } catch (const std::exception& e) {
                    err = e.what();
                    log_error("region finalisation failed: %s", e.what());
                }
                cpu_us_ += micros_since(c0);
                complete(r.id, err);
            });
        }
    }
}

}  // namespace fd

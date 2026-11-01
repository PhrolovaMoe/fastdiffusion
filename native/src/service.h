// Asynchronous region service: one GPU scheduler thread, a CPU pool for finalisation,
// and a completion queue the JVM drains from its own thread.
#pragma once

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <functional>
#include <memory>
#include <mutex>
#include <queue>
#include <string>
#include <thread>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#include "pipeline.h"

namespace fd {

struct ServiceOptions {
    std::string model_path;
    EngineOptions engine;
    int cpu_workers = 0;        // 0: auto
    size_t cache_bytes = 1024ull << 20;
    int max_group_regions = 16;  // regions planned into one GPU batch
};

struct Completion {
    uint64_t id;
    int status;  // 0 ok, 1 error (message via Service::take_error), 2 cancelled
};

class Service {
public:
    explicit Service(const ServiceOptions& opts);
    ~Service();

    const std::string& device_name() const { return models_->engine.device_name(); }

    int open_world(const WorldParams& params);
    void close_world(int world);

    // Queues a region; `out` must hold kRegionBytes and stay valid until completion.
    // Lower priority values run first.
    uint64_t request_region(int world, int64_t rx, int64_t rz, int priority, uint8_t* out);

    // Queues a map grid; `out` must hold grid_bytes(req.w, req.h) and stay valid until completion.
    uint64_t request_grid(int world, const GridRequest& req, int priority, uint8_t* out);

    // Drops a request that has not started yet; it then completes with status 2.
    void cancel(uint64_t id);

    // Blocks until a land position near the origin is known. Returns block coordinates.
    void find_land(int world, int64_t& bx, int64_t& bz);

    // Waits up to timeout_ms for completions; returns how many were written.
    int poll(Completion* out, int max, int timeout_ms);
    std::string take_error(uint64_t id);

    struct Stats {
        uint64_t regions_done, regions_failed, gpu_batches;
        double gpu_ms, cpu_ms;
        int queued;
    };
    Stats stats();

private:
    struct Request {
        uint64_t id;
        int priority;
        uint64_t seq;
        std::shared_ptr<World> world;
        int64_t rx, rz;
        uint8_t* out;
        std::function<void(bool run)> task;  // non-region work run on the GPU thread
        std::shared_ptr<const GridRequest> grid;  // set for map grids
        bool batchable() const { return !task && !grid; }
    };
    struct RequestOrder {
        bool operator()(const Request& a, const Request& b) const {
            return a.priority != b.priority ? a.priority > b.priority : a.seq > b.seq;
        }
    };

    void gpu_loop();
    void cpu_loop();
    void complete(uint64_t id, const std::string& error, bool region = true, bool cancelled = false);
    void run_grid(const Request& r);
    void submit_cpu(std::function<void()> fn);

    ServiceOptions opts_;
    std::unique_ptr<Models> models_;

    std::mutex mu_;
    std::condition_variable cv_;
    std::priority_queue<Request, std::vector<Request>, RequestOrder> queue_;
    std::unordered_map<int, std::shared_ptr<World>> worlds_;
    int next_world_ = 1;
    uint64_t next_id_ = 1, next_seq_ = 0;
    bool stop_ = false;
    std::unordered_set<uint64_t> cancelled_;

    std::mutex cpu_mu_;
    std::condition_variable cpu_cv_;
    std::deque<std::function<void()>> cpu_queue_;

    std::mutex done_mu_;
    std::condition_variable done_cv_;
    std::deque<Completion> done_;
    std::unordered_map<uint64_t, std::string> errors_;

    std::atomic<uint64_t> regions_done_{0}, regions_failed_{0}, gpu_batches_{0};
    std::atomic<uint64_t> gpu_us_{0}, cpu_us_{0};

    std::thread gpu_thread_;
    std::vector<std::thread> cpu_threads_;
};

}  // namespace fd

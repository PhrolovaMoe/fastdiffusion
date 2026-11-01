// JNI entry points for moe.phrolova.engine.NativeBridge.
#include <jni.h>

#include <cstring>
#include <stdexcept>
#include <string>

#include "convert.h"
#include "log.h"
#include "service.h"

using fd::Service;

namespace {

JavaVM* g_vm = nullptr;
jclass g_bridge = nullptr;
jmethodID g_log = nullptr;

void throw_java(JNIEnv* env, const char* msg) {
    jclass ex = env->FindClass("java/lang/RuntimeException");
    env->ThrowNew(ex, msg);
}

std::string to_string(JNIEnv* env, jstring s) {
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

// Forwards native log lines to NativeBridge.log(int, String); native threads attach as daemons once.
void jvm_log_sink(fd::LogLevel level, const char* message) {
    JNIEnv* env = nullptr;
    if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_8) != JNI_OK) {
        if (g_vm->AttachCurrentThreadAsDaemon(reinterpret_cast<void**>(&env), nullptr) != JNI_OK) return;
    }
    jstring msg = env->NewStringUTF(message);
    env->CallStaticVoidMethod(g_bridge, g_log, (jint)level, msg);
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteLocalRef(msg);
}

Service* svc(jlong h) { return reinterpret_cast<Service*>(h); }

}  // namespace

#define FD_JNI(ret, name) extern "C" JNIEXPORT ret JNICALL Java_moe_phrolova_engine_NativeBridge_##name

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_8) != JNI_OK) return JNI_ERR;
    jclass cls = env->FindClass("moe/phrolova/engine/NativeBridge");
    if (!cls) return JNI_ERR;
    g_bridge = (jclass)env->NewGlobalRef(cls);
    g_log = env->GetStaticMethodID(g_bridge, "log", "(ILjava/lang/String;)V");
    if (!g_log) return JNI_ERR;
    fd::set_log_sink(jvm_log_sink);
    return JNI_VERSION_1_8;
}

FD_JNI(jlong, create)(JNIEnv* env, jclass, jstring model, jint device, jint cpu_workers, jlong cache_bytes) {
    try {
        fd::ServiceOptions o;
        o.model_path = to_string(env, model);
        o.engine.device = device;
        o.cpu_workers = cpu_workers;
        o.cache_bytes = (size_t)cache_bytes;
        return reinterpret_cast<jlong>(new Service(o));
    } catch (const std::exception& e) {
        throw_java(env, e.what());
        return 0;
    }
}

FD_JNI(void, destroy)(JNIEnv*, jclass, jlong h) { delete svc(h); }

FD_JNI(jstring, deviceName)(JNIEnv* env, jclass, jlong h) { return env->NewStringUTF(svc(h)->device_name().c_str()); }

FD_JNI(jint, openWorld)(JNIEnv* env, jclass, jlong h, jlong seed, jint scale, jint min_y, jint height, jint sea_level) {
    try {
        fd::WorldParams p;
        p.seed = (uint64_t)seed;
        p.scale = scale;
        p.min_y = min_y;
        p.height = height;
        p.sea_level = sea_level;
        return svc(h)->open_world(p);
    } catch (const std::exception& e) {
        throw_java(env, e.what());
        return 0;
    }
}

FD_JNI(void, closeWorld)(JNIEnv*, jclass, jlong h, jint world) { svc(h)->close_world(world); }

FD_JNI(jint, regionBytes)(JNIEnv*, jclass) { return (jint)fd::kRegionBytes; }

FD_JNI(jlong, requestRegion)(JNIEnv* env, jclass, jlong h, jint world, jint rx, jint rz, jint priority, jobject buffer) {
    auto* out = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    if (!out || env->GetDirectBufferCapacity(buffer) < (jlong)fd::kRegionBytes) {
        throw_java(env, "region buffer must be a direct buffer of regionBytes()");
        return 0;
    }
    try {
        return (jlong)svc(h)->request_region(world, rx, rz, priority, out);
    } catch (const std::exception& e) {
        throw_java(env, e.what());
        return 0;
    }
}

FD_JNI(jlong, requestGrid)(JNIEnv* env, jclass, jlong h, jint world, jdouble x0, jdouble z0, jdouble step, jint w, jint hgt,
                           jint source, jint priority, jobject buffer) {
    auto* out = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    if (w <= 0 || hgt <= 0 || !out || env->GetDirectBufferCapacity(buffer) < (jlong)fd::grid_bytes(w, hgt)) {
        throw_java(env, "grid buffer must be a direct buffer of gridBytes(w, h)");
        return 0;
    }
    try {
        fd::GridRequest req;
        req.x0 = x0;
        req.z0 = z0;
        req.step = step;
        req.w = w;
        req.h = hgt;
        req.source = source == 0 ? fd::GridSource::Coarse : fd::GridSource::Latent;
        return (jlong)svc(h)->request_grid(world, req, priority, out);
    } catch (const std::exception& e) {
        throw_java(env, e.what());
        return 0;
    }
}

FD_JNI(void, cancel)(JNIEnv*, jclass, jlong h, jlong id) { svc(h)->cancel((uint64_t)id); }

FD_JNI(jint, poll)(JNIEnv* env, jclass, jlong h, jlongArray ids, jintArray status, jint timeout_ms) {
    const jsize max = env->GetArrayLength(ids);
    fd::Completion buf[256];
    const int n = svc(h)->poll(buf, std::min<int>(max, 256), timeout_ms);
    jlong id_out[256];
    jint st_out[256];
    for (int i = 0; i < n; i++) {
        id_out[i] = (jlong)buf[i].id;
        st_out[i] = buf[i].status;
    }
    env->SetLongArrayRegion(ids, 0, n, id_out);
    env->SetIntArrayRegion(status, 0, n, st_out);
    return n;
}

FD_JNI(jstring, takeError)(JNIEnv* env, jclass, jlong h, jlong id) {
    return env->NewStringUTF(svc(h)->take_error((uint64_t)id).c_str());
}

FD_JNI(jlongArray, findLand)(JNIEnv* env, jclass, jlong h, jint world) {
    try {
        int64_t x = 0, z = 0;
        svc(h)->find_land(world, x, z);
        jlongArray out = env->NewLongArray(2);
        const jlong v[2] = {(jlong)x, (jlong)z};
        env->SetLongArrayRegion(out, 0, 2, v);
        return out;
    } catch (const std::exception& e) {
        throw_java(env, e.what());
        return nullptr;
    }
}

FD_JNI(jdoubleArray, stats)(JNIEnv* env, jclass, jlong h) {
    const auto s = svc(h)->stats();
    const jdouble v[6] = {(double)s.regions_done, (double)s.regions_failed, (double)s.gpu_batches, s.gpu_ms, s.cpu_ms,
                          (double)s.queued};
    jdoubleArray out = env->NewDoubleArray(6);
    env->SetDoubleArrayRegion(out, 0, 6, v);
    return out;
}

FD_JNI(void, convertModel)(JNIEnv* env, jclass, jstring weights_dir, jstring pipeline_data, jstring out) {
    try {
        fd::convert_model(to_string(env, weights_dir), to_string(env, pipeline_data), to_string(env, out));
    } catch (const std::exception& e) {
        throw_java(env, e.what());
    }
}

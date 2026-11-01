#include "log.h"

#include <atomic>
#include <cstdio>

namespace fd {

namespace {

void stderr_sink(LogLevel level, const char* message) {
    static const char* names[] = {"DEBUG", "INFO", "WARN", "ERROR"};
    std::fprintf(stderr, "[fastdiffusion %s] %s\n", names[(int)level], message);
}

std::atomic<LogSink> g_sink{stderr_sink};

}  // namespace

void set_log_sink(LogSink sink) { g_sink.store(sink ? sink : stderr_sink); }

void log_msg(LogLevel level, const char* fmt, ...) {
    char buf[2048];
    va_list ap;
    va_start(ap, fmt);
    std::vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    g_sink.load()(level, buf);
}

}  // namespace fd

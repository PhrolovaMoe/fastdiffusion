// Minimal logging that can be redirected to the JVM.
#pragma once

#include <cstdarg>

namespace fd {

enum class LogLevel { Debug = 0, Info = 1, Warn = 2, Error = 3 };

using LogSink = void (*)(LogLevel level, const char* message);
void set_log_sink(LogSink sink);
void log_msg(LogLevel level, const char* fmt, ...) __attribute__((format(printf, 2, 3)));

#define log_debug(...) ::fd::log_msg(::fd::LogLevel::Debug, __VA_ARGS__)
#define log_info(...) ::fd::log_msg(::fd::LogLevel::Info, __VA_ARGS__)
#define log_warn(...) ::fd::log_msg(::fd::LogLevel::Warn, __VA_ARGS__)
#define log_error(...) ::fd::log_msg(::fd::LogLevel::Error, __VA_ARGS__)

}  // namespace fd

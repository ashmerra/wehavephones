#pragma once

#define ANDROID_LOG_DEBUG 3

static inline int __android_log_print(int priority, const char *tag, const char *format, ...) {
    (void)priority;
    (void)tag;
    (void)format;
    return 0;
}

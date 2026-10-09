/*
 * LD_PRELOAD for Wine guest processes on Android with fdsan (API 29+).
 *
 * Wine closes descriptors that bionic still records as owned by a FILE*.
 * This violation aborts WoW on both the Samsung's Android 16 and the Pixel's
 * Android 17. WINE_FDSAN_LEVEL selects the level before any Wine
 * code runs: "off" disables fdsan, "warn" logs every violation. The setter
 * is looked up with dlsym because the app's minSdk predates the fdsan API.
 */
#include <dlfcn.h>
#include <stdlib.h>
#include <string.h>

/* enum android_fdsan_error_level from <android/fdsan.h> (API 29). */
#define FDSAN_ERROR_LEVEL_DISABLED 0
#define FDSAN_ERROR_LEVEL_WARN_ALWAYS 2

typedef int (*set_level_fn)(int level);

__attribute__((constructor)) static void set_fdsan_level(void)
{
    const char *setting = getenv("WINE_FDSAN_LEVEL");
    int level;
    if (!setting) return;
    if (!strcmp(setting, "off")) level = FDSAN_ERROR_LEVEL_DISABLED;
    else if (!strcmp(setting, "warn")) level = FDSAN_ERROR_LEVEL_WARN_ALWAYS;
    else return;

    set_level_fn set_level = (set_level_fn)dlsym(RTLD_DEFAULT, "android_fdsan_set_error_level");
    if (set_level) set_level(level);
}

// On-device GPU/driver probe for the per-device graphics rules (GpuProbe.java).
// Everything here is static: vulkan.c exports generically named globals into
// the same shared library.
#include <vulkan/vulkan.h>

#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <unistd.h>
#include "../adrenotools/include/adrenotools/driver.h"

#define GPU_PROBE_TAG "WeHavePhonesDevices"
#define GPU_PROBE_LOG(...) __android_log_print(ANDROID_LOG_INFO, GPU_PROBE_TAG, __VA_ARGS__)

#define GPU_PROBE_TEXT 256
#define GPU_PROBE_FIELDS 12

// Mirrors the msm_kgsl.h UAPI (include/uapi/linux/msm_kgsl.h).
#define GPU_PROBE_KGSL_IOC_TYPE 0x09
#define GPU_PROBE_KGSL_PROP_DEVICE_INFO 0x1

struct gpu_probe_kgsl_getproperty {
    unsigned int type;
    void *value;
    size_t sizebytes;
};

struct gpu_probe_kgsl_devinfo {
    unsigned int device_id;
    unsigned int chip_id;
    unsigned int mmu_enabled;
    unsigned long gmem_gpubaseaddr;
    unsigned int gpu_id;
    size_t gmem_sizebytes;
};

#define GPU_PROBE_IOCTL_KGSL_GETPROPERTY \
    _IOWR(GPU_PROBE_KGSL_IOC_TYPE, 0x2, struct gpu_probe_kgsl_getproperty)

static pthread_mutex_t gpu_probe_lock = PTHREAD_MUTEX_INITIALIZER;

struct gpu_probe_result {
    int ok;
    char stage[16];
    int vk_result;
    char device_name[GPU_PROBE_TEXT];
    unsigned int vendor_id;
    unsigned int device_id;
    char api_version[32];
    char driver_version[48];
    int vk_driver_id;
    char driver_name[GPU_PROBE_TEXT];
    char driver_info[GPU_PROBE_TEXT];
    char error[GPU_PROBE_TEXT];
};

static void gpu_probe_copy(char *dst, size_t size, const char *src) {
    size_t i = 0;
    if (!size) return;
    for (; src && src[i] && i + 1 < size; i++) {
        unsigned char c = (unsigned char) src[i];
        dst[i] = (c < 0x20 || c > 0x7e) ? '?' : (char) c;
    }
    dst[i] = '\0';
}

static const char *gpu_probe_vk_name(int result) {
    switch (result) {
        case VK_SUCCESS: return "VK_SUCCESS";
        case VK_INCOMPLETE: return "VK_INCOMPLETE";
        case VK_ERROR_OUT_OF_HOST_MEMORY: return "VK_ERROR_OUT_OF_HOST_MEMORY";
        case VK_ERROR_OUT_OF_DEVICE_MEMORY: return "VK_ERROR_OUT_OF_DEVICE_MEMORY";
        case VK_ERROR_INITIALIZATION_FAILED: return "VK_ERROR_INITIALIZATION_FAILED";
        case VK_ERROR_DEVICE_LOST: return "VK_ERROR_DEVICE_LOST";
        case VK_ERROR_LAYER_NOT_PRESENT: return "VK_ERROR_LAYER_NOT_PRESENT";
        case VK_ERROR_EXTENSION_NOT_PRESENT: return "VK_ERROR_EXTENSION_NOT_PRESENT";
        case VK_ERROR_FEATURE_NOT_PRESENT: return "VK_ERROR_FEATURE_NOT_PRESENT";
        case VK_ERROR_INCOMPATIBLE_DRIVER: return "VK_ERROR_INCOMPATIBLE_DRIVER";
        case VK_ERROR_TOO_MANY_OBJECTS: return "VK_ERROR_TOO_MANY_OBJECTS";
        case VK_ERROR_FORMAT_NOT_SUPPORTED: return "VK_ERROR_FORMAT_NOT_SUPPORTED";
        default: return "VK_ERROR_UNKNOWN";
    }
}

static void gpu_probe_fail(struct gpu_probe_result *r, const char *stage, int vk_result,
                           const char *what) {
    char text[GPU_PROBE_TEXT];
    r->ok = 0;
    gpu_probe_copy(r->stage, sizeof(r->stage), stage);
    r->vk_result = vk_result;
    if (vk_result != VK_SUCCESS)
        snprintf(text, sizeof(text), "%s: %s (%d)", what, gpu_probe_vk_name(vk_result), vk_result);
    else
        snprintf(text, sizeof(text), "%s", what);
    gpu_probe_copy(r->error, sizeof(r->error), text);
}

static void gpu_probe_format_version(char *dst, size_t size, uint32_t v) {
    snprintf(dst, size, "%u.%u.%u", VK_VERSION_MAJOR(v), VK_VERSION_MINOR(v), VK_VERSION_PATCH(v));
}

static void *gpu_probe_open(const char *driver_dir, const char *library_name,
                            const char *hook_dir, const char *tmp_dir, int use_system,
                            char *error, size_t error_size) {
    void *handle;

    if (use_system) {
        handle = dlopen("/system/lib64/libvulkan.so", RTLD_LOCAL | RTLD_NOW);
        if (!handle) {
            const char *e = dlerror();
            snprintf(error, error_size, "dlopen system libvulkan failed: %s", e ? e : "unknown");
        }
        return handle;
    }

    if (!driver_dir || !*driver_dir || !library_name || !*library_name) {
        snprintf(error, error_size, "driver not installed");
        return NULL;
    }
    if (tmp_dir && *tmp_dir) mkdir(tmp_dir, S_IRWXU | S_IRWXG);

    handle = adrenotools_open_libvulkan(RTLD_LOCAL | RTLD_NOW, ADRENOTOOLS_DRIVER_CUSTOM,
                                        tmp_dir, hook_dir, driver_dir, library_name, NULL, NULL);
    if (!handle) snprintf(error, error_size, "adrenotools_open_libvulkan failed for %s", library_name);
    return handle;
}

static void gpu_probe_run(struct gpu_probe_result *r, const char *driver_dir,
                          const char *library_name, const char *hook_dir, const char *tmp_dir,
                          int use_system) {
    char text[GPU_PROBE_TEXT] = "";
    void *handle = gpu_probe_open(driver_dir, library_name, hook_dir, tmp_dir, use_system,
                                  text, sizeof(text));
    if (!handle) {
        gpu_probe_fail(r, "load", VK_SUCCESS, text);
        return;
    }

    PFN_vkGetInstanceProcAddr get_instance_proc =
            (PFN_vkGetInstanceProcAddr) dlsym(handle, "vkGetInstanceProcAddr");
    PFN_vkCreateInstance create_instance = (PFN_vkCreateInstance) dlsym(handle, "vkCreateInstance");
    if (!get_instance_proc || !create_instance) {
        gpu_probe_fail(r, "load", VK_SUCCESS, "driver lacks vkGetInstanceProcAddr/vkCreateInstance");
        dlclose(handle);
        return;
    }

    VkApplicationInfo app_info = {};
    app_info.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app_info.pApplicationName = "WeHavePhones probe";
    app_info.pEngineName = "WeHavePhones";
    app_info.apiVersion = VK_API_VERSION_1_1;

    VkInstanceCreateInfo instance_info = {};
    instance_info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    instance_info.pApplicationInfo = &app_info;

    VkInstance instance = VK_NULL_HANDLE;
    VkResult vr = create_instance(&instance_info, NULL, &instance);
    if (vr != VK_SUCCESS) {
        app_info.apiVersion = VK_API_VERSION_1_0;
        instance = VK_NULL_HANDLE;
        vr = create_instance(&instance_info, NULL, &instance);
    }
    if (vr != VK_SUCCESS || instance == VK_NULL_HANDLE) {
        gpu_probe_fail(r, "instance", vr != VK_SUCCESS ? vr : VK_ERROR_INITIALIZATION_FAILED,
                       "vkCreateInstance");
        dlclose(handle);
        return;
    }

    PFN_vkDestroyInstance destroy_instance =
            (PFN_vkDestroyInstance) get_instance_proc(instance, "vkDestroyInstance");
    PFN_vkEnumeratePhysicalDevices enumerate =
            (PFN_vkEnumeratePhysicalDevices) get_instance_proc(instance, "vkEnumeratePhysicalDevices");
    PFN_vkGetPhysicalDeviceProperties get_props =
            (PFN_vkGetPhysicalDeviceProperties) get_instance_proc(instance, "vkGetPhysicalDeviceProperties");
    PFN_vkGetPhysicalDeviceProperties2 get_props2 = NULL;
    PFN_vkGetPhysicalDeviceQueueFamilyProperties get_queues =
            (PFN_vkGetPhysicalDeviceQueueFamilyProperties) get_instance_proc(instance, "vkGetPhysicalDeviceQueueFamilyProperties");
    PFN_vkCreateDevice create_device = (PFN_vkCreateDevice) get_instance_proc(instance, "vkCreateDevice");
    PFN_vkGetDeviceProcAddr get_device_proc =
            (PFN_vkGetDeviceProcAddr) get_instance_proc(instance, "vkGetDeviceProcAddr");
    if (app_info.apiVersion >= VK_API_VERSION_1_1)
        get_props2 = (PFN_vkGetPhysicalDeviceProperties2) get_instance_proc(instance, "vkGetPhysicalDeviceProperties2");

    if (!destroy_instance || !enumerate || !get_props || !get_queues || !create_device ||
        !get_device_proc) {
        gpu_probe_fail(r, "instance", VK_ERROR_INITIALIZATION_FAILED, "missing core entry points");
        if (destroy_instance) destroy_instance(instance, NULL);
        dlclose(handle);
        return;
    }

    uint32_t count = 0;
    vr = enumerate(instance, &count, NULL);
    if (vr != VK_SUCCESS && vr != VK_INCOMPLETE) {
        gpu_probe_fail(r, "enumerate", vr, "vkEnumeratePhysicalDevices");
        goto out;
    }
    if (count < 1) {
        gpu_probe_fail(r, "no-device", VK_SUCCESS, "driver reports no physical device");
        goto out;
    }

    VkPhysicalDevice devices[8];
    if (count > 8) count = 8;
    vr = enumerate(instance, &count, devices);
    if ((vr != VK_SUCCESS && vr != VK_INCOMPLETE) || count < 1 || devices[0] == VK_NULL_HANDLE) {
        gpu_probe_fail(r, "enumerate", vr, "vkEnumeratePhysicalDevices (devices)");
        goto out;
    }
    VkPhysicalDevice physical = devices[0];

    VkPhysicalDeviceProperties props = {};
    get_props(physical, &props);
    gpu_probe_copy(r->device_name, sizeof(r->device_name), props.deviceName);
    r->vendor_id = props.vendorID;
    r->device_id = props.deviceID;
    gpu_probe_format_version(r->api_version, sizeof(r->api_version), props.apiVersion);

    r->vk_driver_id = 0;
    int mesa_style = 1;
    if (get_props2 && props.apiVersion >= VK_API_VERSION_1_2) {
        VkPhysicalDeviceDriverProperties driver_props = {};
        driver_props.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DRIVER_PROPERTIES;
        VkPhysicalDeviceProperties2 props2 = {};
        props2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2;
        props2.pNext = &driver_props;
        get_props2(physical, &props2);
        r->vk_driver_id = (int) driver_props.driverID;
        gpu_probe_copy(r->driver_name, sizeof(r->driver_name), driver_props.driverName);
        gpu_probe_copy(r->driver_info, sizeof(r->driver_info), driver_props.driverInfo);
        if (driver_props.driverID == VK_DRIVER_ID_QUALCOMM_PROPRIETARY) mesa_style = 0;
    } else if (props.vendorID == 0x5143 && !strstr(props.deviceName, "Turnip")) {
        mesa_style = 0;
    }
    if (mesa_style)
        gpu_probe_format_version(r->driver_version, sizeof(r->driver_version), props.driverVersion);
    else
        snprintf(r->driver_version, sizeof(r->driver_version), "0x%08x", props.driverVersion);

    uint32_t family_count = 1;
    VkQueueFamilyProperties family = {};
    get_queues(physical, &family_count, &family);
    if (family_count < 1) {
        gpu_probe_fail(r, "device", VK_SUCCESS, "no queue family");
        goto out;
    }

    float priority = 1.0f;
    VkDeviceQueueCreateInfo queue_info = {};
    queue_info.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    queue_info.queueFamilyIndex = 0;
    queue_info.queueCount = 1;
    queue_info.pQueuePriorities = &priority;

    VkDeviceCreateInfo device_info = {};
    device_info.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    device_info.queueCreateInfoCount = 1;
    device_info.pQueueCreateInfos = &queue_info;

    VkDevice device = VK_NULL_HANDLE;
    vr = create_device(physical, &device_info, NULL, &device);
    if (vr != VK_SUCCESS || device == VK_NULL_HANDLE) {
        gpu_probe_fail(r, "device", vr != VK_SUCCESS ? vr : VK_ERROR_INITIALIZATION_FAILED,
                       "vkCreateDevice");
        goto out;
    }
    PFN_vkDestroyDevice destroy_device =
            (PFN_vkDestroyDevice) get_device_proc(device, "vkDestroyDevice");
    if (destroy_device) destroy_device(device, NULL);

    r->ok = 1;
    gpu_probe_copy(r->stage, sizeof(r->stage), "ok");
    r->vk_result = VK_SUCCESS;

out:
    destroy_instance(instance, NULL);
    dlclose(handle);
}

static jobjectArray gpu_probe_strings(JNIEnv *env, const char *const *values, int count) {
    jclass string_class = (*env)->FindClass(env, "java/lang/String");
    if (!string_class) return NULL;
    jobjectArray array = (*env)->NewObjectArray(env, count, string_class, NULL);
    if (!array) return NULL;
    for (int i = 0; i < count; i++) {
        jstring s = (*env)->NewStringUTF(env, values[i] ? values[i] : "");
        if (!s) return NULL;
        (*env)->SetObjectArrayElement(env, array, i, s);
        (*env)->DeleteLocalRef(env, s);
    }
    return array;
}

static char *gpu_probe_utf(JNIEnv *env, jstring s) {
    if (!s) return strdup("");
    const char *chars = (*env)->GetStringUTFChars(env, s, NULL);
    if (!chars) return strdup("");
    char *copy = strdup(chars);
    (*env)->ReleaseStringUTFChars(env, s, chars);
    return copy ? copy : strdup("");
}

JNIEXPORT jobjectArray JNICALL
Java_com_wehavephones_core_GpuProbe_nativeProbe(JNIEnv *env, jclass clazz, jstring driverDir,
                                                jstring libraryName, jstring hookDir,
                                                jstring tmpDir, jboolean useSystem) {
    struct gpu_probe_result result;
    memset(&result, 0, sizeof(result));
    gpu_probe_copy(result.stage, sizeof(result.stage), "load");

    char *driver_dir = gpu_probe_utf(env, driverDir);
    char *library_name = gpu_probe_utf(env, libraryName);
    char *hook_dir = gpu_probe_utf(env, hookDir);
    char *tmp_dir = gpu_probe_utf(env, tmpDir);

    pthread_mutex_lock(&gpu_probe_lock);
    if (driver_dir && library_name && hook_dir && tmp_dir)
        gpu_probe_run(&result, driver_dir, library_name, hook_dir, tmp_dir, useSystem == JNI_TRUE);
    else
        gpu_probe_fail(&result, "load", VK_SUCCESS, "out of memory");
    pthread_mutex_unlock(&gpu_probe_lock);

    GPU_PROBE_LOG("probe %s: ok=%d stage=%s vk=%d device=%s api=%s driver=%s id=%d err=%s",
                  useSystem ? "system" : library_name, result.ok, result.stage, result.vk_result,
                  result.device_name, result.api_version, result.driver_version,
                  result.vk_driver_id, result.error);

    free(driver_dir);
    free(library_name);
    free(hook_dir);
    free(tmp_dir);

    char vk_result[16], vendor_id[16], device_id[16], driver_id[16];
    snprintf(vk_result, sizeof(vk_result), "%d", result.vk_result);
    snprintf(vendor_id, sizeof(vendor_id), "%u", result.vendor_id);
    snprintf(device_id, sizeof(device_id), "%u", result.device_id);
    snprintf(driver_id, sizeof(driver_id), "%d", result.vk_driver_id);

    // Order is fixed; GpuProbe.java indexes into it.
    const char *fields[GPU_PROBE_FIELDS] = {
            result.ok ? "1" : "0", result.stage, vk_result, result.device_name, vendor_id,
            device_id, result.api_version, result.driver_version, driver_id, result.driver_name,
            result.driver_info, result.error};
    return gpu_probe_strings(env, fields, GPU_PROBE_FIELDS);
}

static void gpu_probe_read_text(const char *path, char *dst, size_t size) {
    dst[0] = '\0';
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return;
    ssize_t n = read(fd, dst, size - 1);
    close(fd);
    if (n < 0) n = 0;
    dst[n] = '\0';
    while (n > 0 && (dst[n - 1] == '\n' || dst[n - 1] == '\r' || dst[n - 1] == ' ')) dst[--n] = '\0';
    gpu_probe_copy(dst, size, dst);
}

// Returns {available, chipId, gpuId, gpuModel, error}.
JNIEXPORT jobjectArray JNICALL
Java_com_wehavephones_core_GpuProbe_nativeReadKgsl(JNIEnv *env, jclass clazz) {
    char available[2] = "0", chip_id[24] = "-1", gpu_id[16] = "0";
    char model[GPU_PROBE_TEXT], error[GPU_PROBE_TEXT] = "";

    gpu_probe_read_text("/sys/class/kgsl/kgsl-3d0/gpu_model", model, sizeof(model));

    int fd = open("/dev/kgsl-3d0", O_RDWR | O_CLOEXEC);
    if (fd < 0) fd = open("/dev/kgsl-3d0", O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        snprintf(error, sizeof(error), "open /dev/kgsl-3d0: %s", strerror(errno));
    } else {
        struct gpu_probe_kgsl_devinfo info;
        memset(&info, 0, sizeof(info));
        struct gpu_probe_kgsl_getproperty prop;
        prop.type = GPU_PROBE_KGSL_PROP_DEVICE_INFO;
        prop.value = &info;
        prop.sizebytes = sizeof(info);
        if (ioctl(fd, GPU_PROBE_IOCTL_KGSL_GETPROPERTY, &prop) < 0) {
            snprintf(error, sizeof(error), "KGSL_PROP_DEVICE_INFO: %s", strerror(errno));
        } else {
            available[0] = '1';
            snprintf(chip_id, sizeof(chip_id), "%u", info.chip_id);
            snprintf(gpu_id, sizeof(gpu_id), "%u", info.gpu_id);
        }
        close(fd);
    }

    GPU_PROBE_LOG("kgsl: available=%s chip_id=%s gpu_id=%s model=%s err=%s", available, chip_id,
                  gpu_id, model, error);

    const char *fields[5] = {available, chip_id, gpu_id, model, error};
    return gpu_probe_strings(env, fields, 5);
}

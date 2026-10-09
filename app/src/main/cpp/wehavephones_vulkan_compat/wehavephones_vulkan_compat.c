/*
 * Implicit Vulkan layer for the wrapper on GPUs without native BCn, found on
 * the Pixel 8a (Mali-G715). It forwards what DXVK 2.3 does through Vulkan 1.3
 * entry points to the fixes the wrapper and the BCn compute layer only apply
 * through the Vulkan 1.0 ones:
 *
 * - BCn: both emulations report support through
 *   vkGetPhysicalDeviceFormatProperties and decode in vkCmdCopyBufferToImage.
 *   DXVK reads VkFormatProperties3 and uploads with vkCmdCopyBufferToImage2,
 *   so it rejected every BC shader resource view (WoW then looped on device
 *   recovery until Android killed it) and later sampled undecoded blocks.
 *   For BC formats this layer ORs the emulated flags into the v2/v3 result
 *   and turns v2 buffer-to-image copies into v1 copies.
 * - Shaders: see the Mali shader fixes below.
 *
 * Everything else passes through. Enabled by ENABLE_WEHAVEPHONES_VULKAN_COMPAT=1
 * (see VulkanCompatLayer.java).
 */
#include <dlfcn.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <vulkan/vulkan.h>

#define LAYER_EXPORT __attribute__((visibility("default")))
#define LOG_PREFIX "wehavephones-vulkan-compat: "

/* vk_layer.h is not in NDK 22; these definitions match the loader ABI. */
typedef enum {
    LAYER_LINK_INFO = 0
} LayerFunction;

typedef struct LayerInstanceLink {
    struct LayerInstanceLink *pNext;
    PFN_vkGetInstanceProcAddr pfnNextGetInstanceProcAddr;
    void *pfnNextGetPhysicalDeviceProcAddr;
} LayerInstanceLink;

typedef struct {
    VkStructureType sType;
    const void *pNext;
    LayerFunction function;
    union {
        LayerInstanceLink *pLayerInfo;
        void *other;
    } u;
} LayerInstanceCreateInfo;

typedef struct LayerDeviceLink {
    struct LayerDeviceLink *pNext;
    PFN_vkGetInstanceProcAddr pfnNextGetInstanceProcAddr;
    PFN_vkGetDeviceProcAddr pfnNextGetDeviceProcAddr;
} LayerDeviceLink;

typedef struct {
    VkStructureType sType;
    const void *pNext;
    LayerFunction function;
    union {
        LayerDeviceLink *pLayerInfo;
        void *other;
    } u;
} LayerDeviceCreateInfo;

/* Vulkan 1.3 VkFormatProperties3 (VK_STRUCTURE_TYPE_FORMAT_PROPERTIES_3). */
#define STRUCTURE_TYPE_FORMAT_PROPERTIES_3 ((VkStructureType)1000360000)
typedef struct {
    VkStructureType sType;
    void *pNext;
    uint64_t linearTilingFeatures;
    uint64_t optimalTilingFeatures;
    uint64_t bufferFeatures;
} FormatProperties3;

/* What the wrapper reports for an emulated BCn format. */
#define EMULATED_BCN_FEATURES (VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT | VK_FORMAT_FEATURE_BLIT_SRC_BIT \
        | VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT | VK_FORMAT_FEATURE_TRANSFER_DST_BIT)

#define MAX_ENTRIES 32

typedef struct {
    void *key;
    VkInstance instance;
    PFN_vkGetInstanceProcAddr nextGipa;
    PFN_vkDestroyInstance destroyInstance;
    PFN_vkGetPhysicalDeviceFormatProperties formatProperties;
    PFN_vkGetPhysicalDeviceFormatProperties2 formatProperties2;
    PFN_vkGetPhysicalDeviceFormatProperties2 formatProperties2Khr;
} InstanceEntry;

/* VK_KHR_copy_commands2 / Vulkan 1.3 buffer-to-image copy. */
typedef struct {
    VkStructureType sType;
    const void *pNext;
    VkDeviceSize bufferOffset;
    uint32_t bufferRowLength;
    uint32_t bufferImageHeight;
    VkImageSubresourceLayers imageSubresource;
    VkOffset3D imageOffset;
    VkExtent3D imageExtent;
} BufferImageCopy2;

typedef struct {
    VkStructureType sType;
    const void *pNext;
    VkBuffer srcBuffer;
    VkImage dstImage;
    VkImageLayout dstImageLayout;
    uint32_t regionCount;
    const BufferImageCopy2 *pRegions;
} CopyBufferToImageInfo2;

typedef void (VKAPI_PTR *PFN_CmdCopyBufferToImage2)(VkCommandBuffer, const CopyBufferToImageInfo2 *);

typedef struct {
    void *key;
    PFN_vkGetDeviceProcAddr nextGdpa;
    PFN_vkDestroyDevice destroyDevice;
    PFN_CmdCopyBufferToImage2 copyBufferToImage2;
    PFN_CmdCopyBufferToImage2 copyBufferToImage2Khr;
    PFN_vkCreateShaderModule createShaderModule;
    PFN_vkCreateGraphicsPipelines createGraphicsPipelines;
    PFN_vkCreateComputePipelines createComputePipelines;
} DeviceEntry;

static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static InstanceEntry instances[MAX_ENTRIES];
static DeviceEntry devices[MAX_ENTRIES];
static InstanceEntry lastInstance;
static uint32_t loggedFormats[8];

static void *dispatchKey(const void *handle) {
    return handle ? *(void **)handle : NULL;
}

/* Instances and their physical devices share the loader dispatch key. */
static InstanceEntry findInstance(const void *handle) {
    void *key = dispatchKey(handle);
    InstanceEntry found = {0};
    pthread_mutex_lock(&lock);
    found = lastInstance;
    for (int i = 0; i < MAX_ENTRIES; i++) {
        if (instances[i].key && instances[i].key == key) {
            found = instances[i];
            break;
        }
    }
    pthread_mutex_unlock(&lock);
    return found;
}

static DeviceEntry findDevice(const void *handle) {
    void *key = dispatchKey(handle);
    DeviceEntry found = {0};
    pthread_mutex_lock(&lock);
    for (int i = 0; i < MAX_ENTRIES; i++) {
        if (devices[i].key && devices[i].key == key) {
            found = devices[i];
            break;
        }
    }
    pthread_mutex_unlock(&lock);
    return found;
}

static int isBcFormat(VkFormat format) {
    return format >= VK_FORMAT_BC1_RGB_UNORM_BLOCK && format <= VK_FORMAT_BC7_SRGB_BLOCK;
}

static void logOnce(VkFormat format, uint32_t before, uint32_t emulated) {
    uint32_t bit = (uint32_t)(format - VK_FORMAT_BC1_RGB_UNORM_BLOCK);
    int first;
    pthread_mutex_lock(&lock);
    first = !(loggedFormats[bit / 32] & (1u << (bit % 32)));
    loggedFormats[bit / 32] |= 1u << (bit % 32);
    pthread_mutex_unlock(&lock);
    if (first) {
        fprintf(stderr, LOG_PREFIX "format %d optimal 0x%x -> 0x%x\n", (int)format, before, before | emulated);
    }
}

static void addEmulatedFeatures(const InstanceEntry *entry, VkPhysicalDevice physicalDevice,
                                VkFormat format, VkFormatProperties2 *properties) {
    if (!isBcFormat(format) || !entry->formatProperties || !properties) return;
    VkFormatProperties emulated;
    memset(&emulated, 0, sizeof(emulated));
    entry->formatProperties(physicalDevice, format, &emulated);
    /* The BCn compute layer can sit above this one; it decodes these formats
       for sampling and transfers, as the wrapper's own emulation does. */
    if (!emulated.optimalTilingFeatures) emulated.optimalTilingFeatures = EMULATED_BCN_FEATURES;
    logOnce(format, properties->formatProperties.optimalTilingFeatures, emulated.optimalTilingFeatures);

    properties->formatProperties.linearTilingFeatures |= emulated.linearTilingFeatures;
    properties->formatProperties.optimalTilingFeatures |= emulated.optimalTilingFeatures;
    properties->formatProperties.bufferFeatures |= emulated.bufferFeatures;
    for (VkBaseOutStructure *next = (VkBaseOutStructure *)properties->pNext; next; next = next->pNext) {
        if (next->sType != STRUCTURE_TYPE_FORMAT_PROPERTIES_3) continue;
        FormatProperties3 *properties3 = (FormatProperties3 *)next;
        properties3->linearTilingFeatures |= emulated.linearTilingFeatures;
        properties3->optimalTilingFeatures |= emulated.optimalTilingFeatures;
        properties3->bufferFeatures |= emulated.bufferFeatures;
    }
}

static VKAPI_ATTR void VKAPI_CALL layerGetPhysicalDeviceFormatProperties2(
        VkPhysicalDevice physicalDevice, VkFormat format, VkFormatProperties2 *properties) {
    InstanceEntry entry = findInstance(physicalDevice);
    PFN_vkGetPhysicalDeviceFormatProperties2 next = entry.formatProperties2 ? entry.formatProperties2 : entry.formatProperties2Khr;
    if (!next) return;
    next(physicalDevice, format, properties);
    addEmulatedFeatures(&entry, physicalDevice, format, properties);
}

static VKAPI_ATTR void VKAPI_CALL layerGetPhysicalDeviceFormatProperties2Khr(
        VkPhysicalDevice physicalDevice, VkFormat format, VkFormatProperties2 *properties) {
    InstanceEntry entry = findInstance(physicalDevice);
    PFN_vkGetPhysicalDeviceFormatProperties2 next = entry.formatProperties2Khr ? entry.formatProperties2Khr : entry.formatProperties2;
    if (!next) return;
    next(physicalDevice, format, properties);
    addEmulatedFeatures(&entry, physicalDevice, format, properties);
}

static VKAPI_ATTR VkResult VKAPI_CALL layerCreateInstance(
        const VkInstanceCreateInfo *createInfo, const VkAllocationCallbacks *allocator, VkInstance *instance) {
    LayerInstanceCreateInfo *link = (LayerInstanceCreateInfo *)createInfo->pNext;
    while (link && !(link->sType == VK_STRUCTURE_TYPE_LOADER_INSTANCE_CREATE_INFO && link->function == LAYER_LINK_INFO)) {
        link = (LayerInstanceCreateInfo *)link->pNext;
    }
    if (!link) return VK_ERROR_INITIALIZATION_FAILED;

    PFN_vkGetInstanceProcAddr nextGipa = link->u.pLayerInfo->pfnNextGetInstanceProcAddr;
    link->u.pLayerInfo = link->u.pLayerInfo->pNext;
    PFN_vkCreateInstance createInstance = (PFN_vkCreateInstance)nextGipa(VK_NULL_HANDLE, "vkCreateInstance");
    if (!createInstance) return VK_ERROR_INITIALIZATION_FAILED;

    VkResult result = createInstance(createInfo, allocator, instance);
    if (result != VK_SUCCESS) return result;

    InstanceEntry entry = {0};
    entry.key = dispatchKey(*instance);
    entry.instance = *instance;
    entry.nextGipa = nextGipa;
    entry.destroyInstance = (PFN_vkDestroyInstance)nextGipa(*instance, "vkDestroyInstance");
    entry.formatProperties = (PFN_vkGetPhysicalDeviceFormatProperties)nextGipa(*instance, "vkGetPhysicalDeviceFormatProperties");
    entry.formatProperties2 = (PFN_vkGetPhysicalDeviceFormatProperties2)nextGipa(*instance, "vkGetPhysicalDeviceFormatProperties2");
    entry.formatProperties2Khr = (PFN_vkGetPhysicalDeviceFormatProperties2)nextGipa(*instance, "vkGetPhysicalDeviceFormatProperties2KHR");

    pthread_mutex_lock(&lock);
    lastInstance = entry;
    for (int i = 0; i < MAX_ENTRIES; i++) {
        if (!instances[i].key || instances[i].key == entry.key) {
            instances[i] = entry;
            break;
        }
    }
    pthread_mutex_unlock(&lock);
    return VK_SUCCESS;
}

static VKAPI_ATTR void VKAPI_CALL layerDestroyInstance(VkInstance instance, const VkAllocationCallbacks *allocator) {
    InstanceEntry entry = findInstance(instance);
    void *key = dispatchKey(instance);
    pthread_mutex_lock(&lock);
    for (int i = 0; i < MAX_ENTRIES; i++) {
        if (instances[i].key == key) memset(&instances[i], 0, sizeof(instances[i]));
    }
    if (lastInstance.key == key) memset(&lastInstance, 0, sizeof(lastInstance));
    pthread_mutex_unlock(&lock);
    if (entry.destroyInstance) entry.destroyInstance(instance, allocator);
}

static VKAPI_ATTR VkResult VKAPI_CALL layerCreateDevice(
        VkPhysicalDevice physicalDevice, const VkDeviceCreateInfo *createInfo,
        const VkAllocationCallbacks *allocator, VkDevice *device) {
    LayerDeviceCreateInfo *link = (LayerDeviceCreateInfo *)createInfo->pNext;
    while (link && !(link->sType == VK_STRUCTURE_TYPE_LOADER_DEVICE_CREATE_INFO && link->function == LAYER_LINK_INFO)) {
        link = (LayerDeviceCreateInfo *)link->pNext;
    }
    if (!link) return VK_ERROR_INITIALIZATION_FAILED;

    PFN_vkGetInstanceProcAddr nextGipa = link->u.pLayerInfo->pfnNextGetInstanceProcAddr;
    PFN_vkGetDeviceProcAddr nextGdpa = link->u.pLayerInfo->pfnNextGetDeviceProcAddr;
    link->u.pLayerInfo = link->u.pLayerInfo->pNext;
    InstanceEntry instance = findInstance(physicalDevice);
    PFN_vkCreateDevice createDevice = (PFN_vkCreateDevice)nextGipa(instance.instance, "vkCreateDevice");
    if (!createDevice) return VK_ERROR_INITIALIZATION_FAILED;

    VkResult result = createDevice(physicalDevice, createInfo, allocator, device);
    if (result != VK_SUCCESS) return result;

    DeviceEntry entry = {0};
    entry.key = dispatchKey(*device);
    entry.nextGdpa = nextGdpa;
    entry.destroyDevice = (PFN_vkDestroyDevice)nextGdpa(*device, "vkDestroyDevice");
    entry.copyBufferToImage2 = (PFN_CmdCopyBufferToImage2)nextGdpa(*device, "vkCmdCopyBufferToImage2");
    entry.copyBufferToImage2Khr = (PFN_CmdCopyBufferToImage2)nextGdpa(*device, "vkCmdCopyBufferToImage2KHR");
    entry.createShaderModule = (PFN_vkCreateShaderModule)nextGdpa(*device, "vkCreateShaderModule");
    entry.createGraphicsPipelines = (PFN_vkCreateGraphicsPipelines)nextGdpa(*device, "vkCreateGraphicsPipelines");
    entry.createComputePipelines = (PFN_vkCreateComputePipelines)nextGdpa(*device, "vkCreateComputePipelines");
    pthread_mutex_lock(&lock);
    for (int i = 0; i < MAX_ENTRIES; i++) {
        if (!devices[i].key || devices[i].key == entry.key) {
            devices[i] = entry;
            break;
        }
    }
    pthread_mutex_unlock(&lock);
    return VK_SUCCESS;
}

static VKAPI_ATTR void VKAPI_CALL layerDestroyDevice(VkDevice device, const VkAllocationCallbacks *allocator) {
    DeviceEntry entry = findDevice(device);
    void *key = dispatchKey(device);
    pthread_mutex_lock(&lock);
    for (int i = 0; i < MAX_ENTRIES; i++) {
        if (devices[i].key == key) memset(&devices[i], 0, sizeof(devices[i]));
    }
    pthread_mutex_unlock(&lock);
    if (entry.destroyDevice) entry.destroyDevice(device, allocator);
}

/*
 * The loader's exported vkCmdCopyBufferToImage dispatches from the top of the
 * layer chain, so the BCn compute layer sees the copy even when it sits above
 * this layer; the wrapper's emulation sees it either way.
 */
static PFN_vkCmdCopyBufferToImage loaderCopyBufferToImage;
static pthread_once_t loaderCopyOnce = PTHREAD_ONCE_INIT;

static void resolveLoaderCopy(void) {
    void *loader = dlopen("libvulkan.so.1", RTLD_NOW | RTLD_NOLOAD);
    if (!loader) loader = dlopen("libvulkan.so", RTLD_NOW | RTLD_NOLOAD);
    if (loader) loaderCopyBufferToImage = (PFN_vkCmdCopyBufferToImage)dlsym(loader, "vkCmdCopyBufferToImage");
    fprintf(stderr, LOG_PREFIX "v2 buffer-to-image copies %s\n",
            loaderCopyBufferToImage ? "go through vkCmdCopyBufferToImage" : "pass through (no loader entry point)");
}

static PFN_vkCmdCopyBufferToImage topCopyBufferToImage(void) {
    pthread_once(&loaderCopyOnce, resolveLoaderCopy);
    return loaderCopyBufferToImage;
}

/*
 * Both BCn decoders only hook vkCmdCopyBufferToImage, while DXVK 2.x uploads
 * with the copy_commands2 variant, so compressed blocks reached the RGBA
 * replacement images undecoded. Regions with extension structs pass through.
 */
static void copyBufferToImage2(VkCommandBuffer commandBuffer, const CopyBufferToImageInfo2 *info,
                               PFN_CmdCopyBufferToImage2 next) {
    PFN_vkCmdCopyBufferToImage copy = topCopyBufferToImage();
    int plain = copy && !info->pNext && info->regionCount <= 64;
    for (uint32_t i = 0; plain && i < info->regionCount; i++) {
        if (info->pRegions[i].pNext) plain = 0;
    }
    if (!plain) {
        if (next) next(commandBuffer, info);
        return;
    }

    VkBufferImageCopy regions[64];
    for (uint32_t i = 0; i < info->regionCount; i++) {
        const BufferImageCopy2 *region = &info->pRegions[i];
        regions[i].bufferOffset = region->bufferOffset;
        regions[i].bufferRowLength = region->bufferRowLength;
        regions[i].bufferImageHeight = region->bufferImageHeight;
        regions[i].imageSubresource = region->imageSubresource;
        regions[i].imageOffset = region->imageOffset;
        regions[i].imageExtent = region->imageExtent;
    }
    copy(commandBuffer, info->srcBuffer, info->dstImage, info->dstImageLayout, info->regionCount, regions);
}

static VKAPI_ATTR void VKAPI_CALL layerCmdCopyBufferToImage2(VkCommandBuffer commandBuffer, const CopyBufferToImageInfo2 *info) {
    DeviceEntry entry = findDevice(commandBuffer);
    copyBufferToImage2(commandBuffer, info, entry.copyBufferToImage2 ? entry.copyBufferToImage2 : entry.copyBufferToImage2Khr);
}

static VKAPI_ATTR void VKAPI_CALL layerCmdCopyBufferToImage2Khr(VkCommandBuffer commandBuffer, const CopyBufferToImageInfo2 *info) {
    DeviceEntry entry = findDevice(commandBuffer);
    copyBufferToImage2(commandBuffer, info, entry.copyBufferToImage2Khr ? entry.copyBufferToImage2Khr : entry.copyBufferToImage2);
}


/*
 * Mali shader fixes. The wrapper rewrites SPIR-V for Mali in
 * vkCreateShaderModule only: it strips ClipDistance/CullDistance, which Mali
 * lacks although the wrapper reports support, and turns OpConstantComposite
 * of spec-constant booleans into OpSpecConstantComposite. Its second pass
 * reads its byte count as a word count and writes past the copied code,
 * corrupting the heap (Scudo "invalid chunk state" on world entry), so
 * WRAPPER_NO_PATCH_OPCONSTCOMP=1 disables it and this layer applies a bounded
 * version. DXVK 2.3 also chains raw SPIR-V into pipeline stages (always with
 * graphics pipeline libraries, and next to its module otherwise), which
 * skipped both passes; such stages get a module created through the top of
 * the chain instead.
 */
#define SPIRV_OP_TYPE_BOOL 20
#define SPIRV_OP_TYPE_VECTOR 23
#define SPIRV_OP_CONSTANT_COMPOSITE 44
#define SPIRV_OP_SPEC_CONSTANT_TRUE 48
#define SPIRV_OP_SPEC_CONSTANT_FALSE 49
#define SPIRV_OP_SPEC_CONSTANT 50
#define SPIRV_OP_SPEC_CONSTANT_COMPOSITE 51
#define SPIRV_ID_BOOL 1
#define SPIRV_ID_SPEC_CONSTANT 2

static void patchSpecConstantComposites(uint32_t *code, size_t wordCount) {
    if (wordCount < 5) return;
    uint32_t bound = code[3];
    if (!bound || bound > (1u << 22)) return;
    uint8_t *flags = calloc(bound, 1);
    uint8_t *vectorSize = calloc(bound, 1);
    if (!flags || !vectorSize) {
        free(flags);
        free(vectorSize);
        return;
    }

    for (size_t offset = 5; offset < wordCount;) {
        uint32_t length = code[offset] >> 16;
        uint32_t opcode = code[offset] & 0xffffu;
        if (!length || offset + length > wordCount) break;

        if (opcode == SPIRV_OP_TYPE_BOOL && length >= 2 && code[offset + 1] < bound) {
            flags[code[offset + 1]] |= SPIRV_ID_BOOL;
        }
        else if (opcode == SPIRV_OP_TYPE_VECTOR && length >= 4 && code[offset + 1] < bound
                 && code[offset + 2] < bound && (flags[code[offset + 2]] & SPIRV_ID_BOOL)) {
            vectorSize[code[offset + 1]] = (uint8_t)code[offset + 3];
        }
        else if ((opcode == SPIRV_OP_SPEC_CONSTANT_TRUE || opcode == SPIRV_OP_SPEC_CONSTANT_FALSE
                  || opcode == SPIRV_OP_SPEC_CONSTANT) && length >= 3 && code[offset + 2] < bound) {
            flags[code[offset + 2]] |= SPIRV_ID_SPEC_CONSTANT;
        }
        else if (opcode == SPIRV_OP_CONSTANT_COMPOSITE && length >= 3 && code[offset + 1] < bound) {
            uint32_t components = vectorSize[code[offset + 1]];
            int allSpec = components && 3 + components <= length;
            for (uint32_t i = 0; allSpec && i < components; i++) {
                uint32_t id = code[offset + 3 + i];
                allSpec = id < bound && (flags[id] & SPIRV_ID_SPEC_CONSTANT);
            }
            if (allSpec) code[offset] = (code[offset] & ~0xffffu) | SPIRV_OP_SPEC_CONSTANT_COMPOSITE;
        }
        offset += length;
    }
    free(flags);
    free(vectorSize);
}

static VKAPI_ATTR VkResult VKAPI_CALL layerCreateShaderModule(
        VkDevice device, const VkShaderModuleCreateInfo *info,
        const VkAllocationCallbacks *allocator, VkShaderModule *module) {
    DeviceEntry entry = findDevice(device);
    if (!entry.createShaderModule) return VK_ERROR_INITIALIZATION_FAILED;
    uint32_t *code = info->codeSize >= 20 ? malloc(info->codeSize) : NULL;
    if (!code) return entry.createShaderModule(device, info, allocator, module);

    memcpy(code, info->pCode, info->codeSize);
    patchSpecConstantComposites(code, info->codeSize / sizeof(uint32_t));
    VkShaderModuleCreateInfo patched = *info;
    patched.pCode = code;
    VkResult result = entry.createShaderModule(device, &patched, allocator, module);
    free(code);
    return result;
}

static PFN_vkCreateShaderModule loaderCreateShaderModule;
static PFN_vkDestroyShaderModule loaderDestroyShaderModule;
static pthread_once_t loaderModuleOnce = PTHREAD_ONCE_INIT;

static void resolveLoaderModules(void) {
    void *loader = dlopen("libvulkan.so.1", RTLD_NOW | RTLD_NOLOAD);
    if (!loader) loader = dlopen("libvulkan.so", RTLD_NOW | RTLD_NOLOAD);
    if (loader) {
        loaderCreateShaderModule = (PFN_vkCreateShaderModule)dlsym(loader, "vkCreateShaderModule");
        loaderDestroyShaderModule = (PFN_vkDestroyShaderModule)dlsym(loader, "vkDestroyShaderModule");
    }
    fprintf(stderr, LOG_PREFIX "inline pipeline SPIR-V %s\n", loaderCreateShaderModule && loaderDestroyShaderModule
            ? "goes through vkCreateShaderModule" : "passes through (no loader entry point)");
}

typedef struct {
    VkShaderModule *items;
    uint32_t count;
} ShaderModules;

static int hasInlineCode(const VkPipelineShaderStageCreateInfo *stage) {
    const VkBaseInStructure *next = stage->pNext;
    return next && next->sType == VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
}

/* Replaces inline SPIR-V (chained first, as DXVK does) with a module. */
static VkResult replaceInlineCode(VkDevice device, VkPipelineShaderStageCreateInfo *stage, ShaderModules *modules) {
    const VkShaderModuleCreateInfo *inlineInfo = stage->pNext;
    if (stage->module == VK_NULL_HANDLE) {
        VkShaderModuleCreateInfo info = *inlineInfo;
        info.pNext = NULL;
        VkShaderModule module = VK_NULL_HANDLE;
        VkResult result = loaderCreateShaderModule(device, &info, NULL, &module);
        if (result != VK_SUCCESS) return result;
        modules->items[modules->count++] = module;
        stage->module = module;
    }
    stage->pNext = inlineInfo->pNext;
    return VK_SUCCESS;
}

static void destroyModules(VkDevice device, ShaderModules *modules) {
    for (uint32_t i = 0; i < modules->count; i++) loaderDestroyShaderModule(device, modules->items[i], NULL);
    free(modules->items);
}

static VKAPI_ATTR VkResult VKAPI_CALL layerCreateGraphicsPipelines(
        VkDevice device, VkPipelineCache cache, uint32_t count, const VkGraphicsPipelineCreateInfo *infos,
        const VkAllocationCallbacks *allocator, VkPipeline *pipelines) {
    DeviceEntry entry = findDevice(device);
    if (!entry.createGraphicsPipelines) return VK_ERROR_INITIALIZATION_FAILED;
    pthread_once(&loaderModuleOnce, resolveLoaderModules);

    uint32_t stageCount = 0;
    int inlineCode = 0;
    for (uint32_t i = 0; i < count; i++) {
        stageCount += infos[i].stageCount;
        for (uint32_t s = 0; s < infos[i].stageCount; s++) inlineCode |= hasInlineCode(&infos[i].pStages[s]);
    }
    if (!inlineCode || !loaderCreateShaderModule || !loaderDestroyShaderModule) {
        return entry.createGraphicsPipelines(device, cache, count, infos, allocator, pipelines);
    }

    VkGraphicsPipelineCreateInfo *copies = malloc(count * sizeof(*copies));
    VkPipelineShaderStageCreateInfo *stages = malloc(stageCount * sizeof(*stages));
    ShaderModules modules = { malloc(stageCount * sizeof(VkShaderModule)), 0 };
    VkResult result = copies && stages && modules.items ? VK_SUCCESS : VK_ERROR_OUT_OF_HOST_MEMORY;
    for (uint32_t i = 0, base = 0; result == VK_SUCCESS && i < count; base += infos[i].stageCount, i++) {
        copies[i] = infos[i];
        copies[i].pStages = stages + base;
        for (uint32_t s = 0; result == VK_SUCCESS && s < infos[i].stageCount; s++) {
            stages[base + s] = infos[i].pStages[s];
            if (hasInlineCode(&stages[base + s])) result = replaceInlineCode(device, &stages[base + s], &modules);
        }
    }
    if (result == VK_SUCCESS) result = entry.createGraphicsPipelines(device, cache, count, copies, allocator, pipelines);
    destroyModules(device, &modules);
    free(stages);
    free(copies);
    return result;
}

static VKAPI_ATTR VkResult VKAPI_CALL layerCreateComputePipelines(
        VkDevice device, VkPipelineCache cache, uint32_t count, const VkComputePipelineCreateInfo *infos,
        const VkAllocationCallbacks *allocator, VkPipeline *pipelines) {
    DeviceEntry entry = findDevice(device);
    if (!entry.createComputePipelines) return VK_ERROR_INITIALIZATION_FAILED;
    pthread_once(&loaderModuleOnce, resolveLoaderModules);

    int inlineCode = 0;
    for (uint32_t i = 0; i < count; i++) inlineCode |= hasInlineCode(&infos[i].stage);
    if (!inlineCode || !loaderCreateShaderModule || !loaderDestroyShaderModule) {
        return entry.createComputePipelines(device, cache, count, infos, allocator, pipelines);
    }

    VkComputePipelineCreateInfo *copies = malloc(count * sizeof(*copies));
    ShaderModules modules = { malloc(count * sizeof(VkShaderModule)), 0 };
    VkResult result = copies && modules.items ? VK_SUCCESS : VK_ERROR_OUT_OF_HOST_MEMORY;
    for (uint32_t i = 0; result == VK_SUCCESS && i < count; i++) {
        copies[i] = infos[i];
        if (hasInlineCode(&copies[i].stage)) result = replaceInlineCode(device, &copies[i].stage, &modules);
    }
    if (result == VK_SUCCESS) result = entry.createComputePipelines(device, cache, count, copies, allocator, pipelines);
    destroyModules(device, &modules);
    free(copies);
    return result;
}

LAYER_EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL WeHavePhonesVulkanCompat_GetDeviceProcAddr(VkDevice device, const char *name);

LAYER_EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL WeHavePhonesVulkanCompat_GetInstanceProcAddr(VkInstance instance, const char *name) {
    if (!strcmp(name, "vkGetInstanceProcAddr")) return (PFN_vkVoidFunction)WeHavePhonesVulkanCompat_GetInstanceProcAddr;
    if (!strcmp(name, "vkGetDeviceProcAddr")) return (PFN_vkVoidFunction)WeHavePhonesVulkanCompat_GetDeviceProcAddr;
    if (!strcmp(name, "vkCreateInstance")) return (PFN_vkVoidFunction)layerCreateInstance;
    if (!strcmp(name, "vkDestroyInstance")) return (PFN_vkVoidFunction)layerDestroyInstance;
    if (!strcmp(name, "vkCreateDevice")) return (PFN_vkVoidFunction)layerCreateDevice;
    if (!strcmp(name, "vkDestroyDevice")) return (PFN_vkVoidFunction)layerDestroyDevice;
    if (!strcmp(name, "vkGetPhysicalDeviceFormatProperties2")) return (PFN_vkVoidFunction)layerGetPhysicalDeviceFormatProperties2;
    if (!strcmp(name, "vkGetPhysicalDeviceFormatProperties2KHR")) return (PFN_vkVoidFunction)layerGetPhysicalDeviceFormatProperties2Khr;

    InstanceEntry entry = findInstance(instance);
    return entry.nextGipa ? entry.nextGipa(instance, name) : NULL;
}

LAYER_EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL WeHavePhonesVulkanCompat_GetDeviceProcAddr(VkDevice device, const char *name) {
    if (!strcmp(name, "vkGetDeviceProcAddr")) return (PFN_vkVoidFunction)WeHavePhonesVulkanCompat_GetDeviceProcAddr;
    if (!strcmp(name, "vkDestroyDevice")) return (PFN_vkVoidFunction)layerDestroyDevice;

    DeviceEntry entry = findDevice(device);
    if (!strcmp(name, "vkCmdCopyBufferToImage2") && entry.copyBufferToImage2) return (PFN_vkVoidFunction)layerCmdCopyBufferToImage2;
    if (!strcmp(name, "vkCmdCopyBufferToImage2KHR") && entry.copyBufferToImage2Khr) return (PFN_vkVoidFunction)layerCmdCopyBufferToImage2Khr;
    if (!strcmp(name, "vkCreateShaderModule") && entry.createShaderModule) return (PFN_vkVoidFunction)layerCreateShaderModule;
    if (!strcmp(name, "vkCreateGraphicsPipelines") && entry.createGraphicsPipelines) return (PFN_vkVoidFunction)layerCreateGraphicsPipelines;
    if (!strcmp(name, "vkCreateComputePipelines") && entry.createComputePipelines) return (PFN_vkVoidFunction)layerCreateComputePipelines;
    return entry.nextGdpa ? entry.nextGdpa(device, name) : NULL;
}

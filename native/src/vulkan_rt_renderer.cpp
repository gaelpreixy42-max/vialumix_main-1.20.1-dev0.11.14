// Persistent Vulkan ray-tracing renderer used by the Vialumix Iris bridge.
//
// The renderer owns its own Vulkan device (matched to OpenGL's adapter by LUID), keeps a TLAS built from
// the world geometry supplied by Java and, once per frame, traces three RGBA16F images that are exported
// to OpenGL (EXT_memory_object_win32) so Iris composite/gbuffer programs can sample them.
#include <jni.h>
#ifdef _WIN32
#define VK_USE_PLATFORM_WIN32_KHR
#endif
#include <vulkan/vulkan.h>

#include <algorithm>
#include <array>
#include <cstdint>
#include <cstring>
#include <stdexcept>
#include <string>
#include <vector>

#ifdef _WIN32
#include <windows.h>
#endif

namespace {
constexpr int kImages = 3;
constexpr VkFormat kFormat = VK_FORMAT_R16G16B16A16_SFLOAT;
constexpr size_t kFrameBytes = 7 * 4 * sizeof(float);

struct Buffer { VkBuffer handle = VK_NULL_HANDLE; VkDeviceMemory memory = VK_NULL_HANDLE; VkDeviceSize size = 0; void* mapped = nullptr; };
struct Accel { VkAccelerationStructureKHR handle = VK_NULL_HANDLE; Buffer storage{}; };
struct OutImage { VkImage image = VK_NULL_HANDLE; VkDeviceMemory memory = VK_NULL_HANDLE; VkImageView view = VK_NULL_HANDLE; VkDeviceSize size = 0; };

struct Ctx {
    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice physical = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    uint32_t queueFamily = UINT32_MAX;
    VkCommandPool pool = VK_NULL_HANDLE;
    VkCommandBuffer cmd = VK_NULL_HANDLE;
    VkFence fence = VK_NULL_HANDLE;
    bool fencePending = false;

    VkDescriptorSetLayout setLayout = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout = VK_NULL_HANDLE;
    VkPipeline pipeline = VK_NULL_HANDLE;
    VkDescriptorPool descPool = VK_NULL_HANDLE;
    VkDescriptorSet set = VK_NULL_HANDLE;
    Buffer sbt{}; VkStridedDeviceAddressRegionKHR rgenRegion{}, missRegion{}, hitRegion{}, callRegion{};
    Buffer frameUbo{};

    // geometry: 0 = solid, 1 = water
    Buffer verts[2]{}, colors[2]{};
    Accel blas[2]{}; Accel tlas{}; Buffer instanceBuf{};
    bool sceneReady = false;

    OutImage images[kImages]{};
    VkSemaphore semVkToGl = VK_NULL_HANDLE, semGlToVk = VK_NULL_HANDLE;
    bool imagesReady = false;
    uint32_t width = 0, height = 0;

    PFN_vkCreateAccelerationStructureKHR createAS = nullptr;
    PFN_vkDestroyAccelerationStructureKHR destroyAS = nullptr;
    PFN_vkGetAccelerationStructureBuildSizesKHR getSizes = nullptr;
    PFN_vkCmdBuildAccelerationStructuresKHR buildAS = nullptr;
    PFN_vkGetAccelerationStructureDeviceAddressKHR getASAddress = nullptr;
    PFN_vkGetBufferDeviceAddress getBufferAddress = nullptr;
    PFN_vkCmdTraceRaysKHR traceRays = nullptr;
    PFN_vkCreateRayTracingPipelinesKHR createPipelines = nullptr;
    PFN_vkGetRayTracingShaderGroupHandlesKHR getHandles = nullptr;
#ifdef _WIN32
    PFN_vkGetMemoryWin32HandleKHR getMemoryHandle = nullptr;
    PFN_vkGetSemaphoreWin32HandleKHR getSemaphoreHandle = nullptr;
#endif
    VkPhysicalDeviceRayTracingPipelinePropertiesKHR rtProps{};
    VkDeviceSize scratchAlignment = 256;
} c;

void require(VkResult r, const char* what) {
    if (r != VK_SUCCESS) throw std::runtime_error(std::string(what) + " failed (VkResult " + std::to_string(r) + ")");
}
bool hasExtension(VkPhysicalDevice device, const char* name) {
    uint32_t count = 0;
    if (vkEnumerateDeviceExtensionProperties(device, nullptr, &count, nullptr) != VK_SUCCESS) return false;
    std::vector<VkExtensionProperties> list(count);
    if (vkEnumerateDeviceExtensionProperties(device, nullptr, &count, list.data()) != VK_SUCCESS) return false;
    return std::any_of(list.begin(), list.end(), [name](const auto& e) { return std::strcmp(e.extensionName, name) == 0; });
}
uint32_t memoryType(uint32_t bits, VkMemoryPropertyFlags flags) {
    VkPhysicalDeviceMemoryProperties props{};
    vkGetPhysicalDeviceMemoryProperties(c.physical, &props);
    for (uint32_t i = 0; i < props.memoryTypeCount; ++i)
        if ((bits & (1u << i)) && (props.memoryTypes[i].propertyFlags & flags) == flags) return i;
    throw std::runtime_error("No Vulkan memory type satisfies the requested flags");
}
uint64_t alignUp(uint64_t v, uint64_t a) { return (v + a - 1) & ~(a - 1); }

Buffer makeBuffer(VkDeviceSize size, VkBufferUsageFlags usage, VkMemoryPropertyFlags flags, bool map = false) {
    Buffer b{}; b.size = size;
    VkBufferCreateInfo ci{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO}; ci.size = size; ci.usage = usage | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
    ci.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    require(vkCreateBuffer(c.device, &ci, nullptr, &b.handle), "vkCreateBuffer");
    VkMemoryRequirements req{}; vkGetBufferMemoryRequirements(c.device, b.handle, &req);
    VkMemoryAllocateFlagsInfo fi{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_FLAGS_INFO}; fi.flags = VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT;
    VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO}; ai.pNext = &fi; ai.allocationSize = req.size;
    ai.memoryTypeIndex = memoryType(req.memoryTypeBits, flags);
    require(vkAllocateMemory(c.device, &ai, nullptr, &b.memory), "vkAllocateMemory(buffer)");
    require(vkBindBufferMemory(c.device, b.handle, b.memory, 0), "vkBindBufferMemory");
    if (map) require(vkMapMemory(c.device, b.memory, 0, size, 0, &b.mapped), "vkMapMemory");
    return b;
}
void destroyBuffer(Buffer& b) {
    if (c.device) {
        if (b.mapped) vkUnmapMemory(c.device, b.memory);
        if (b.handle) vkDestroyBuffer(c.device, b.handle, nullptr);
        if (b.memory) vkFreeMemory(c.device, b.memory, nullptr);
    }
    b = {};
}
VkDeviceAddress addressOf(const Buffer& b) {
    VkBufferDeviceAddressInfo i{VK_STRUCTURE_TYPE_BUFFER_DEVICE_ADDRESS_INFO}; i.buffer = b.handle;
    return c.getBufferAddress(c.device, &i);
}
void destroyAccel(Accel& a) {
    if (c.device && a.handle && c.destroyAS) c.destroyAS(c.device, a.handle, nullptr);
    destroyBuffer(a.storage);
    a = {};
}

VkCommandBuffer beginOneShot() {
    VkCommandBufferAllocateInfo ai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO}; ai.commandPool = c.pool;
    ai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY; ai.commandBufferCount = 1;
    VkCommandBuffer cmd = VK_NULL_HANDLE; require(vkAllocateCommandBuffers(c.device, &ai, &cmd), "vkAllocateCommandBuffers");
    VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO}; bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    require(vkBeginCommandBuffer(cmd, &bi), "vkBeginCommandBuffer");
    return cmd;
}
void endOneShot(VkCommandBuffer cmd) {
    require(vkEndCommandBuffer(cmd), "vkEndCommandBuffer");
    VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO}; si.commandBufferCount = 1; si.pCommandBuffers = &cmd;
    require(vkQueueSubmit(c.queue, 1, &si, VK_NULL_HANDLE), "vkQueueSubmit(one-shot)");
    require(vkQueueWaitIdle(c.queue), "vkQueueWaitIdle(one-shot)");
    vkFreeCommandBuffers(c.device, c.pool, 1, &cmd);
}

// Uploads host data into a device-local buffer (usable as SSBO and acceleration-structure input).
Buffer uploadDeviceLocal(const void* data, VkDeviceSize size, VkBufferUsageFlags usage) {
    Buffer staging = makeBuffer(size, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, true);
    std::memcpy(staging.mapped, data, size);
    Buffer dst = makeBuffer(size, usage | VK_BUFFER_USAGE_TRANSFER_DST_BIT, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    VkCommandBuffer cmd = beginOneShot();
    VkBufferCopy copy{0, 0, size}; vkCmdCopyBuffer(cmd, staging.handle, dst.handle, 1, &copy);
    endOneShot(cmd);
    destroyBuffer(staging);
    return dst;
}

Accel buildAccel(VkAccelerationStructureTypeKHR type, const VkAccelerationStructureGeometryKHR& geometry, uint32_t primitives) {
    VkAccelerationStructureBuildGeometryInfoKHR bi{VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_GEOMETRY_INFO_KHR};
    bi.type = type; bi.flags = VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR; bi.mode = VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR;
    bi.geometryCount = 1; bi.pGeometries = &geometry;
    VkAccelerationStructureBuildSizesInfoKHR sizes{VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_SIZES_INFO_KHR};
    c.getSizes(c.device, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR, &bi, &primitives, &sizes);
    Accel a{};
    a.storage = makeBuffer(sizes.accelerationStructureSize, VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    VkAccelerationStructureCreateInfoKHR ci{VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_CREATE_INFO_KHR};
    ci.buffer = a.storage.handle; ci.size = sizes.accelerationStructureSize; ci.type = type;
    require(c.createAS(c.device, &ci, nullptr, &a.handle), "vkCreateAccelerationStructureKHR");
    Buffer scratch = makeBuffer(sizes.buildScratchSize + c.scratchAlignment, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    bi.dstAccelerationStructure = a.handle;
    bi.scratchData.deviceAddress = alignUp(addressOf(scratch), c.scratchAlignment);
    const VkAccelerationStructureBuildRangeInfoKHR range{primitives, 0, 0, 0};
    const VkAccelerationStructureBuildRangeInfoKHR* ranges[] = {&range};
    VkCommandBuffer cmd = beginOneShot();
    c.buildAS(cmd, 1, &bi, ranges);
    VkMemoryBarrier barrier{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
    barrier.srcAccessMask = VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR; barrier.dstAccessMask = VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                         VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR | VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR, 0, 1, &barrier, 0, nullptr, 0, nullptr);
    endOneShot(cmd);
    destroyBuffer(scratch);
    return a;
}

std::vector<uint32_t> readSpirv(JNIEnv* env, jbyteArray input, const char* name) {
    if (!input) throw std::runtime_error(std::string("Missing SPIR-V shader ") + name);
    const jsize length = env->GetArrayLength(input);
    if (length < 20 || length % 4 != 0) throw std::runtime_error(std::string("Invalid SPIR-V size for ") + name);
    std::vector<uint32_t> words(static_cast<size_t>(length) / 4);
    env->GetByteArrayRegion(input, 0, length, reinterpret_cast<jbyte*>(words.data()));
    if (env->ExceptionCheck() || words[0] != 0x07230203) throw std::runtime_error(std::string("Bad SPIR-V module ") + name);
    return words;
}
VkShaderModule makeModule(const std::vector<uint32_t>& words) {
    VkShaderModuleCreateInfo ci{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO}; ci.codeSize = words.size() * 4; ci.pCode = words.data();
    VkShaderModule m = VK_NULL_HANDLE; require(vkCreateShaderModule(c.device, &ci, nullptr, &m), "vkCreateShaderModule"); return m;
}

void destroyImages() {
    if (!c.device) return;
    for (auto& img : c.images) {
        if (img.view) vkDestroyImageView(c.device, img.view, nullptr);
        if (img.image) vkDestroyImage(c.device, img.image, nullptr);
        if (img.memory) vkFreeMemory(c.device, img.memory, nullptr);
        img = {};
    }
    if (c.semVkToGl) vkDestroySemaphore(c.device, c.semVkToGl, nullptr);
    if (c.semGlToVk) vkDestroySemaphore(c.device, c.semGlToVk, nullptr);
    c.semVkToGl = c.semGlToVk = VK_NULL_HANDLE;
    c.imagesReady = false;
}
void destroyGeometry() {
    for (int i = 0; i < 2; ++i) { destroyAccel(c.blas[i]); destroyBuffer(c.verts[i]); destroyBuffer(c.colors[i]); }
    destroyAccel(c.tlas); destroyBuffer(c.instanceBuf);
    c.sceneReady = false;
}
void shutdown() {
    if (c.device) vkDeviceWaitIdle(c.device);
    destroyImages();
    destroyGeometry();
    destroyBuffer(c.frameUbo);
    destroyBuffer(c.sbt);
    if (c.device) {
        if (c.fence) vkDestroyFence(c.device, c.fence, nullptr);
        if (c.pipeline) vkDestroyPipeline(c.device, c.pipeline, nullptr);
        if (c.descPool) vkDestroyDescriptorPool(c.device, c.descPool, nullptr);
        if (c.pipelineLayout) vkDestroyPipelineLayout(c.device, c.pipelineLayout, nullptr);
        if (c.setLayout) vkDestroyDescriptorSetLayout(c.device, c.setLayout, nullptr);
        if (c.pool) vkDestroyCommandPool(c.device, c.pool, nullptr);
        vkDestroyDevice(c.device, nullptr);
    }
    if (c.instance) vkDestroyInstance(c.instance, nullptr);
    c = {};
}

void createDevice(const uint8_t* luid) {
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO}; app.pApplicationName = "Vialumix RT Bridge"; app.apiVersion = VK_API_VERSION_1_2;
    VkInstanceCreateInfo ici{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO}; ici.pApplicationInfo = &app;
    require(vkCreateInstance(&ici, nullptr, &c.instance), "vkCreateInstance");
    uint32_t count = 0; require(vkEnumeratePhysicalDevices(c.instance, &count, nullptr), "vkEnumeratePhysicalDevices");
    std::vector<VkPhysicalDevice> devices(count); require(vkEnumeratePhysicalDevices(c.instance, &count, devices.data()), "vkEnumeratePhysicalDevices");
    const char* extensions[] = {VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME, VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME,
        VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME, VK_KHR_BUFFER_DEVICE_ADDRESS_EXTENSION_NAME,
        VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME, VK_KHR_EXTERNAL_SEMAPHORE_WIN32_EXTENSION_NAME};
    for (auto device : devices) {
        VkPhysicalDeviceIDProperties id{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ID_PROPERTIES};
        VkPhysicalDeviceProperties2 props{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2}; props.pNext = &id;
        vkGetPhysicalDeviceProperties2(device, &props);
        if (id.deviceLUIDValid != VK_TRUE || std::memcmp(id.deviceLUID, luid, VK_LUID_SIZE) != 0) continue;
        bool all = true; for (auto e : extensions) if (!hasExtension(device, e)) all = false;
        if (!all) continue;
        uint32_t qc = 0; vkGetPhysicalDeviceQueueFamilyProperties(device, &qc, nullptr);
        std::vector<VkQueueFamilyProperties> queues(qc); vkGetPhysicalDeviceQueueFamilyProperties(device, &qc, queues.data());
        for (uint32_t i = 0; i < qc; ++i)
            if (queues[i].queueCount && (queues[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) && (queues[i].queueFlags & VK_QUEUE_COMPUTE_BIT)) { c.physical = device; c.queueFamily = i; break; }
        if (c.physical) break;
    }
    if (!c.physical) throw std::runtime_error("No RT-capable Vulkan adapter matches OpenGL's device LUID");

    c.rtProps.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_PIPELINE_PROPERTIES_KHR;
    VkPhysicalDeviceAccelerationStructurePropertiesKHR asProps{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_PROPERTIES_KHR};
    c.rtProps.pNext = &asProps;
    VkPhysicalDeviceProperties2 p2{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2}; p2.pNext = &c.rtProps;
    vkGetPhysicalDeviceProperties2(c.physical, &p2);
    c.rtProps.pNext = nullptr;
    c.scratchAlignment = std::max<VkDeviceSize>(asProps.minAccelerationStructureScratchOffsetAlignment, 128);

    float priority = 1.0f;
    VkDeviceQueueCreateInfo qci{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO}; qci.queueFamilyIndex = c.queueFamily; qci.queueCount = 1; qci.pQueuePriorities = &priority;
    VkPhysicalDeviceAccelerationStructureFeaturesKHR asf{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_FEATURES_KHR}; asf.accelerationStructure = VK_TRUE;
    VkPhysicalDeviceRayTracingPipelineFeaturesKHR rtf{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_PIPELINE_FEATURES_KHR}; rtf.rayTracingPipeline = VK_TRUE; rtf.pNext = &asf;
    VkPhysicalDeviceBufferDeviceAddressFeatures bda{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_BUFFER_DEVICE_ADDRESS_FEATURES}; bda.bufferDeviceAddress = VK_TRUE; bda.pNext = &rtf;
    VkDeviceCreateInfo dci{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO}; dci.pNext = &bda; dci.queueCreateInfoCount = 1; dci.pQueueCreateInfos = &qci;
    dci.enabledExtensionCount = static_cast<uint32_t>(std::size(extensions)); dci.ppEnabledExtensionNames = extensions;
    require(vkCreateDevice(c.physical, &dci, nullptr, &c.device), "vkCreateDevice");
    vkGetDeviceQueue(c.device, c.queueFamily, 0, &c.queue);

#define LOAD(var, type, name) var = reinterpret_cast<type>(vkGetDeviceProcAddr(c.device, name)); if (!var) throw std::runtime_error(name " unavailable")
    LOAD(c.createAS, PFN_vkCreateAccelerationStructureKHR, "vkCreateAccelerationStructureKHR");
    LOAD(c.destroyAS, PFN_vkDestroyAccelerationStructureKHR, "vkDestroyAccelerationStructureKHR");
    LOAD(c.getSizes, PFN_vkGetAccelerationStructureBuildSizesKHR, "vkGetAccelerationStructureBuildSizesKHR");
    LOAD(c.buildAS, PFN_vkCmdBuildAccelerationStructuresKHR, "vkCmdBuildAccelerationStructuresKHR");
    LOAD(c.getASAddress, PFN_vkGetAccelerationStructureDeviceAddressKHR, "vkGetAccelerationStructureDeviceAddressKHR");
    LOAD(c.getBufferAddress, PFN_vkGetBufferDeviceAddress, "vkGetBufferDeviceAddress");
    LOAD(c.traceRays, PFN_vkCmdTraceRaysKHR, "vkCmdTraceRaysKHR");
    LOAD(c.createPipelines, PFN_vkCreateRayTracingPipelinesKHR, "vkCreateRayTracingPipelinesKHR");
    LOAD(c.getHandles, PFN_vkGetRayTracingShaderGroupHandlesKHR, "vkGetRayTracingShaderGroupHandlesKHR");
#ifdef _WIN32
    LOAD(c.getMemoryHandle, PFN_vkGetMemoryWin32HandleKHR, "vkGetMemoryWin32HandleKHR");
    LOAD(c.getSemaphoreHandle, PFN_vkGetSemaphoreWin32HandleKHR, "vkGetSemaphoreWin32HandleKHR");
#endif
#undef LOAD

    VkCommandPoolCreateInfo pci{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO}; pci.queueFamilyIndex = c.queueFamily;
    pci.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    require(vkCreateCommandPool(c.device, &pci, nullptr, &c.pool), "vkCreateCommandPool");
    VkCommandBufferAllocateInfo ai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO}; ai.commandPool = c.pool; ai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY; ai.commandBufferCount = 1;
    require(vkAllocateCommandBuffers(c.device, &ai, &c.cmd), "vkAllocateCommandBuffers(frame)");
    VkFenceCreateInfo fci{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    require(vkCreateFence(c.device, &fci, nullptr, &c.fence), "vkCreateFence");
    c.frameUbo = makeBuffer(kFrameBytes, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, true);
    std::memset(c.frameUbo.mapped, 0, kFrameBytes);
}

void createPipeline(const std::vector<uint32_t>& rgen, const std::vector<uint32_t>& miss, const std::vector<uint32_t>& shadowMiss, const std::vector<uint32_t>& chit) {
    const VkShaderStageFlags rt = VK_SHADER_STAGE_RAYGEN_BIT_KHR | VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR;
    VkDescriptorSetLayoutBinding b[9]{};
    for (int i = 0; i < 3; ++i) { b[i].binding = i; b[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE; b[i].descriptorCount = 1; b[i].stageFlags = VK_SHADER_STAGE_RAYGEN_BIT_KHR; }
    b[3].binding = 3; b[3].descriptorType = VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR; b[3].descriptorCount = 1; b[3].stageFlags = VK_SHADER_STAGE_RAYGEN_BIT_KHR;
    b[4].binding = 4; b[4].descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER; b[4].descriptorCount = 1; b[4].stageFlags = VK_SHADER_STAGE_RAYGEN_BIT_KHR;
    for (int i = 5; i < 9; ++i) { b[i].binding = i; b[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER; b[i].descriptorCount = 1; b[i].stageFlags = rt; }
    VkDescriptorSetLayoutCreateInfo dl{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO}; dl.bindingCount = 9; dl.pBindings = b;
    require(vkCreateDescriptorSetLayout(c.device, &dl, nullptr, &c.setLayout), "vkCreateDescriptorSetLayout");
    VkPipelineLayoutCreateInfo pl{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO}; pl.setLayoutCount = 1; pl.pSetLayouts = &c.setLayout;
    require(vkCreatePipelineLayout(c.device, &pl, nullptr, &c.pipelineLayout), "vkCreatePipelineLayout");

    VkShaderModule modules[4] = {makeModule(rgen), makeModule(miss), makeModule(shadowMiss), makeModule(chit)};
    const VkShaderStageFlagBits stageBits[4] = {VK_SHADER_STAGE_RAYGEN_BIT_KHR, VK_SHADER_STAGE_MISS_BIT_KHR, VK_SHADER_STAGE_MISS_BIT_KHR, VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR};
    VkPipelineShaderStageCreateInfo stages[4]{};
    for (int i = 0; i < 4; ++i) { stages[i].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO; stages[i].stage = stageBits[i]; stages[i].module = modules[i]; stages[i].pName = "main"; }
    VkRayTracingShaderGroupCreateInfoKHR groups[4]{};
    for (auto& g : groups) { g.sType = VK_STRUCTURE_TYPE_RAY_TRACING_SHADER_GROUP_CREATE_INFO_KHR; g.generalShader = g.closestHitShader = g.anyHitShader = g.intersectionShader = VK_SHADER_UNUSED_KHR; }
    groups[0].type = VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR; groups[0].generalShader = 0;
    groups[1].type = VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR; groups[1].generalShader = 1;
    groups[2].type = VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR; groups[2].generalShader = 2;
    groups[3].type = VK_RAY_TRACING_SHADER_GROUP_TYPE_TRIANGLES_HIT_GROUP_KHR; groups[3].closestHitShader = 3;
    VkRayTracingPipelineCreateInfoKHR ci{VK_STRUCTURE_TYPE_RAY_TRACING_PIPELINE_CREATE_INFO_KHR}; ci.stageCount = 4; ci.pStages = stages;
    ci.groupCount = 4; ci.pGroups = groups; ci.maxPipelineRayRecursionDepth = 1; ci.layout = c.pipelineLayout;
    VkResult pr = c.createPipelines(c.device, VK_NULL_HANDLE, VK_NULL_HANDLE, 1, &ci, nullptr, &c.pipeline);
    for (auto m : modules) vkDestroyShaderModule(c.device, m, nullptr);
    require(pr, "vkCreateRayTracingPipelinesKHR");

    VkDescriptorPoolSize sizes[4] = {{VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 3}, {VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR, 1},
                                     {VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1}, {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 4}};
    VkDescriptorPoolCreateInfo dp{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO}; dp.maxSets = 1; dp.poolSizeCount = 4; dp.pPoolSizes = sizes;
    require(vkCreateDescriptorPool(c.device, &dp, nullptr, &c.descPool), "vkCreateDescriptorPool");
    VkDescriptorSetAllocateInfo da{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO}; da.descriptorPool = c.descPool; da.descriptorSetCount = 1; da.pSetLayouts = &c.setLayout;
    require(vkAllocateDescriptorSets(c.device, &da, &c.set), "vkAllocateDescriptorSets");

    // Shader binding table: [raygen][miss x2][hit]; each region starts at a base-alignment boundary.
    const uint32_t handleSize = c.rtProps.shaderGroupHandleSize;
    const uint32_t stride = static_cast<uint32_t>(alignUp(handleSize, c.rtProps.shaderGroupHandleAlignment));
    const uint64_t base = c.rtProps.shaderGroupBaseAlignment;
    const uint64_t rgenOff = 0, missOff = alignUp(rgenOff + stride, base), hitOff = alignUp(missOff + 2ull * stride, base), total = hitOff + stride;
    c.sbt = makeBuffer(total + base, VK_BUFFER_USAGE_SHADER_BINDING_TABLE_BIT_KHR, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, true);
    std::vector<uint8_t> handles(static_cast<size_t>(handleSize) * 4);
    require(c.getHandles(c.device, c.pipeline, 0, 4, handles.size(), handles.data()), "vkGetRayTracingShaderGroupHandlesKHR");
    const VkDeviceAddress addr = alignUp(addressOf(c.sbt), base);
    const uint64_t shift = addr - addressOf(c.sbt);
    auto* mem = static_cast<uint8_t*>(c.sbt.mapped) + shift;
    std::memset(mem, 0, total);
    std::memcpy(mem + rgenOff, handles.data() + 0 * handleSize, handleSize);
    std::memcpy(mem + missOff, handles.data() + 1 * handleSize, handleSize);
    std::memcpy(mem + missOff + stride, handles.data() + 2 * handleSize, handleSize);
    std::memcpy(mem + hitOff, handles.data() + 3 * handleSize, handleSize);
    c.rgenRegion = {addr + rgenOff, stride, stride};
    c.missRegion = {addr + missOff, stride, 2ull * stride};
    c.hitRegion = {addr + hitOff, stride, stride};
}

void writeDescriptors() {
    VkDescriptorImageInfo imageInfo[3]{};
    for (int i = 0; i < 3; ++i) { imageInfo[i].imageView = c.images[i].view; imageInfo[i].imageLayout = VK_IMAGE_LAYOUT_GENERAL; }
    VkWriteDescriptorSetAccelerationStructureKHR asw{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET_ACCELERATION_STRUCTURE_KHR};
    asw.accelerationStructureCount = 1; asw.pAccelerationStructures = &c.tlas.handle;
    VkDescriptorBufferInfo ubo{c.frameUbo.handle, 0, kFrameBytes};
    VkDescriptorBufferInfo ssbo[4] = {{c.verts[0].handle, 0, VK_WHOLE_SIZE}, {c.colors[0].handle, 0, VK_WHOLE_SIZE},
                                      {c.verts[1].handle, 0, VK_WHOLE_SIZE}, {c.colors[1].handle, 0, VK_WHOLE_SIZE}};
    VkWriteDescriptorSet w[9]{};
    for (int i = 0; i < 9; ++i) { w[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET; w[i].dstSet = c.set; w[i].dstBinding = i; w[i].descriptorCount = 1; }
    for (int i = 0; i < 3; ++i) { w[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE; w[i].pImageInfo = &imageInfo[i]; }
    w[3].descriptorType = VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR; w[3].pNext = &asw;
    w[4].descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER; w[4].pBufferInfo = &ubo;
    for (int i = 0; i < 4; ++i) { w[5 + i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER; w[5 + i].pBufferInfo = &ssbo[i]; }
    vkUpdateDescriptorSets(c.device, 9, w, 0, nullptr);
}

// A scene slot always contains at least one (tiny, far-away) triangle so every buffer and BLAS is valid.
void buildSlot(int slot, const std::vector<float>& verticesIn, const std::vector<float>& colorsIn) {
    std::vector<float> vertices = verticesIn, colors = colorsIn;
    if (vertices.size() < 9 || vertices.size() % 9 != 0 || colors.size() < (vertices.size() / 9) * 4) {
        vertices = {0, -100000, 0, 0.01f, -100000, 0, 0, -100000, 0.01f};
        colors = {0, 0, 0, 0};
    }
    const uint32_t triangles = static_cast<uint32_t>(vertices.size() / 9);
    c.verts[slot] = uploadDeviceLocal(vertices.data(), vertices.size() * sizeof(float),
        VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR);
    c.colors[slot] = uploadDeviceLocal(colors.data(), static_cast<VkDeviceSize>(triangles) * 4 * sizeof(float), VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
    VkAccelerationStructureGeometryTrianglesDataKHR tri{VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_TRIANGLES_DATA_KHR};
    tri.vertexFormat = VK_FORMAT_R32G32B32_SFLOAT; tri.vertexData.deviceAddress = addressOf(c.verts[slot]); tri.vertexStride = 12;
    tri.maxVertex = triangles * 3 - 1; tri.indexType = VK_INDEX_TYPE_NONE_KHR;
    VkAccelerationStructureGeometryKHR geom{VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_KHR};
    geom.geometryType = VK_GEOMETRY_TYPE_TRIANGLES_KHR; geom.flags = VK_GEOMETRY_OPAQUE_BIT_KHR; geom.geometry.triangles = tri;
    c.blas[slot] = buildAccel(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR, geom, triangles);
}

void buildScene(const std::vector<float>& sv, const std::vector<float>& sc, const std::vector<float>& wv, const std::vector<float>& wc) {
    vkDeviceWaitIdle(c.device);
    destroyGeometry();
    buildSlot(0, sv, sc);
    buildSlot(1, wv, wc);
    VkAccelerationStructureInstanceKHR inst[2]{};
    for (int i = 0; i < 2; ++i) {
        inst[i].transform.matrix[0][0] = inst[i].transform.matrix[1][1] = inst[i].transform.matrix[2][2] = 1.0f;
        inst[i].instanceCustomIndex = i; inst[i].mask = i == 0 ? 0x01 : 0x02; inst[i].instanceShaderBindingTableRecordOffset = 0;
        inst[i].flags = VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR;
        VkAccelerationStructureDeviceAddressInfoKHR ai{VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_DEVICE_ADDRESS_INFO_KHR}; ai.accelerationStructure = c.blas[i].handle;
        inst[i].accelerationStructureReference = c.getASAddress(c.device, &ai);
    }
    c.instanceBuf = uploadDeviceLocal(inst, sizeof(inst), VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR);
    VkAccelerationStructureGeometryInstancesDataKHR data{VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_INSTANCES_DATA_KHR};
    data.data.deviceAddress = addressOf(c.instanceBuf);
    VkAccelerationStructureGeometryKHR geom{VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_KHR};
    geom.geometryType = VK_GEOMETRY_TYPE_INSTANCES_KHR; geom.geometry.instances = data;
    c.tlas = buildAccel(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR, geom, 2);
    c.sceneReady = true;
    if (c.imagesReady) writeDescriptors();
}

void createImages(uint32_t width, uint32_t height) {
    vkDeviceWaitIdle(c.device);
    destroyImages();
    c.width = width; c.height = height;
    for (auto& img : c.images) {
        VkExternalMemoryImageCreateInfo ext{VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO}; ext.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT;
        VkImageCreateInfo ci{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO}; ci.pNext = &ext; ci.imageType = VK_IMAGE_TYPE_2D; ci.format = kFormat;
        ci.extent = {width, height, 1}; ci.mipLevels = 1; ci.arrayLayers = 1; ci.samples = VK_SAMPLE_COUNT_1_BIT; ci.tiling = VK_IMAGE_TILING_OPTIMAL;
        ci.usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT; ci.sharingMode = VK_SHARING_MODE_EXCLUSIVE; ci.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        require(vkCreateImage(c.device, &ci, nullptr, &img.image), "vkCreateImage(RT output)");
        VkMemoryRequirements req{}; vkGetImageMemoryRequirements(c.device, img.image, &req); img.size = req.size;
        VkExportMemoryAllocateInfo exp{VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO}; exp.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT;
        VkMemoryDedicatedAllocateInfo ded{VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO}; ded.pNext = &exp; ded.image = img.image;
        VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO}; ai.pNext = &ded; ai.allocationSize = req.size;
        ai.memoryTypeIndex = memoryType(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        require(vkAllocateMemory(c.device, &ai, nullptr, &img.memory), "vkAllocateMemory(RT output)");
        require(vkBindImageMemory(c.device, img.image, img.memory, 0), "vkBindImageMemory(RT output)");
        VkImageViewCreateInfo vi{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO}; vi.image = img.image; vi.viewType = VK_IMAGE_VIEW_TYPE_2D; vi.format = kFormat;
        vi.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        require(vkCreateImageView(c.device, &vi, nullptr, &img.view), "vkCreateImageView(RT output)");
    }
    VkExportSemaphoreCreateInfo se{VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO}; se.handleTypes = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT;
    VkSemaphoreCreateInfo si{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO}; si.pNext = &se;
    require(vkCreateSemaphore(c.device, &si, nullptr, &c.semVkToGl), "vkCreateSemaphore(vk->gl)");
    require(vkCreateSemaphore(c.device, &si, nullptr, &c.semGlToVk), "vkCreateSemaphore(gl->vk)");
    c.imagesReady = true;
    if (c.sceneReady) writeDescriptors();
}

void throwJava(JNIEnv* env, const char* message) {
    jclass cls = env->FindClass("java/lang/RuntimeException");
    if (cls) env->ThrowNew(cls, message);
}
std::vector<float> readFloats(JNIEnv* env, jfloatArray array) {
    if (!array) return {};
    const jsize n = env->GetArrayLength(array);
    std::vector<float> out(static_cast<size_t>(n));
    if (n) env->GetFloatArrayRegion(array, 0, n, out.data());
    return out;
}
} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL Java_com_vialumix_rt_VialumixNative_nativeRtInit(
    JNIEnv* env, jclass, jbyteArray luidArray, jbyteArray rgen, jbyteArray miss, jbyteArray shadowMiss, jbyteArray chit) {
#ifdef _WIN32
    try {
        shutdown();
        if (!luidArray || env->GetArrayLength(luidArray) != VK_LUID_SIZE) throw std::runtime_error("OpenGL device LUID unavailable");
        std::array<uint8_t, VK_LUID_SIZE> luid{};
        env->GetByteArrayRegion(luidArray, 0, VK_LUID_SIZE, reinterpret_cast<jbyte*>(luid.data()));
        auto r = readSpirv(env, rgen, "rt.rgen"), m = readSpirv(env, miss, "rt.rmiss"), s = readSpirv(env, shadowMiss, "rt_shadow.rmiss"), h = readSpirv(env, chit, "rt.rchit");
        createDevice(luid.data());
        createPipeline(r, m, s, h);
        return JNI_TRUE;
    } catch (const std::exception& e) { shutdown(); throwJava(env, e.what()); return JNI_FALSE; }
#else
    throwJava(env, "The Vialumix RT bridge is currently Windows-only"); return JNI_FALSE;
#endif
}

JNIEXPORT jboolean JNICALL Java_com_vialumix_rt_VialumixNative_nativeRtSetScene(
    JNIEnv* env, jclass, jfloatArray solidVerts, jfloatArray solidColors, jfloatArray waterVerts, jfloatArray waterColors) {
    try {
        if (!c.device) throw std::runtime_error("RT renderer is not initialised");
        buildScene(readFloats(env, solidVerts), readFloats(env, solidColors), readFloats(env, waterVerts), readFloats(env, waterColors));
        return JNI_TRUE;
    } catch (const std::exception& e) { throwJava(env, e.what()); return JNI_FALSE; }
}

// Returns {width, height, vk->gl semaphore, gl->vk semaphore, (memoryHandle, size) x3}.
JNIEXPORT jlongArray JNICALL Java_com_vialumix_rt_VialumixNative_nativeRtConfigure(JNIEnv* env, jclass, jint width, jint height) {
#ifdef _WIN32
    try {
        if (!c.device) throw std::runtime_error("RT renderer is not initialised");
        if (width < 16 || height < 16 || width > 8192 || height > 8192) throw std::runtime_error("Unsupported RT output size");
        c.fencePending = false;
        if (c.fence) { vkDeviceWaitIdle(c.device); vkResetFences(c.device, 1, &c.fence); }
        createImages(static_cast<uint32_t>(width), static_cast<uint32_t>(height));
        std::array<jlong, 4 + kImages * 2> vals{};
        vals[0] = width; vals[1] = height;
        VkSemaphoreGetWin32HandleInfoKHR si{VK_STRUCTURE_TYPE_SEMAPHORE_GET_WIN32_HANDLE_INFO_KHR}; si.handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT;
        HANDLE h1 = nullptr, h2 = nullptr;
        si.semaphore = c.semVkToGl; require(c.getSemaphoreHandle(c.device, &si, &h1), "vkGetSemaphoreWin32HandleKHR(vk->gl)");
        si.semaphore = c.semGlToVk; VkResult r2 = c.getSemaphoreHandle(c.device, &si, &h2);
        if (r2 != VK_SUCCESS) { CloseHandle(h1); require(r2, "vkGetSemaphoreWin32HandleKHR(gl->vk)"); }
        vals[2] = reinterpret_cast<jlong>(h1); vals[3] = reinterpret_cast<jlong>(h2);
        for (int i = 0; i < kImages; ++i) {
            VkMemoryGetWin32HandleInfoKHR mi{VK_STRUCTURE_TYPE_MEMORY_GET_WIN32_HANDLE_INFO_KHR}; mi.memory = c.images[i].memory; mi.handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT;
            HANDLE mh = nullptr;
            VkResult mr = c.getMemoryHandle(c.device, &mi, &mh);
            if (mr != VK_SUCCESS) {
                CloseHandle(h1); CloseHandle(h2);
                for (int j = 0; j < i; ++j) CloseHandle(reinterpret_cast<HANDLE>(vals[4 + j * 2]));
                require(mr, "vkGetMemoryWin32HandleKHR");
            }
            vals[4 + i * 2] = reinterpret_cast<jlong>(mh); vals[5 + i * 2] = static_cast<jlong>(c.images[i].size);
        }
        jlongArray out = env->NewLongArray(static_cast<jsize>(vals.size()));
        if (out) env->SetLongArrayRegion(out, 0, static_cast<jsize>(vals.size()), vals.data());
        return out;
    } catch (const std::exception& e) { throwJava(env, e.what()); return nullptr; }
#else
    throwJava(env, "The Vialumix RT bridge is currently Windows-only"); return nullptr;
#endif
}

// frame: 7 vec4 (see rt.rgen). glSignaled: OpenGL signalled the gl->vk semaphore since the previous trace.
JNIEXPORT jboolean JNICALL Java_com_vialumix_rt_VialumixNative_nativeRtTrace(JNIEnv* env, jclass, jfloatArray frame, jboolean glSignaled) {
    try {
        if (!c.device || !c.sceneReady || !c.imagesReady) return JNI_FALSE;
        if (!frame || env->GetArrayLength(frame) < static_cast<jsize>(kFrameBytes / sizeof(float))) throw std::runtime_error("RT frame parameter block too small");
        if (c.fencePending) {
            VkResult w = vkWaitForFences(c.device, 1, &c.fence, VK_TRUE, 2000000000ull);
            if (w != VK_SUCCESS) throw std::runtime_error("Timed out waiting for the previous RT frame");
            vkResetFences(c.device, 1, &c.fence);
            c.fencePending = false;
        }
        env->GetFloatArrayRegion(frame, 0, static_cast<jsize>(kFrameBytes / sizeof(float)), static_cast<jfloat*>(c.frameUbo.mapped));

        vkResetCommandBuffer(c.cmd, 0);
        VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO}; bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        require(vkBeginCommandBuffer(c.cmd, &bi), "vkBeginCommandBuffer(RT)");
        VkImageMemoryBarrier acquire[kImages]{};
        for (int i = 0; i < kImages; ++i) {
            acquire[i].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER; acquire[i].dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
            acquire[i].oldLayout = VK_IMAGE_LAYOUT_UNDEFINED; acquire[i].newLayout = VK_IMAGE_LAYOUT_GENERAL;
            acquire[i].srcQueueFamilyIndex = VK_QUEUE_FAMILY_EXTERNAL; acquire[i].dstQueueFamilyIndex = c.queueFamily;
            acquire[i].image = c.images[i].image; acquire[i].subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        }
        vkCmdPipelineBarrier(c.cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR, 0, 0, nullptr, 0, nullptr, kImages, acquire);
        vkCmdBindPipeline(c.cmd, VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR, c.pipeline);
        vkCmdBindDescriptorSets(c.cmd, VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR, c.pipelineLayout, 0, 1, &c.set, 0, nullptr);
        c.traceRays(c.cmd, &c.rgenRegion, &c.missRegion, &c.hitRegion, &c.callRegion, c.width, c.height, 1);
        VkImageMemoryBarrier release[kImages]{};
        for (int i = 0; i < kImages; ++i) {
            release[i].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER; release[i].srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT; release[i].dstAccessMask = VK_ACCESS_MEMORY_READ_BIT;
            release[i].oldLayout = VK_IMAGE_LAYOUT_GENERAL; release[i].newLayout = VK_IMAGE_LAYOUT_GENERAL;
            release[i].srcQueueFamilyIndex = c.queueFamily; release[i].dstQueueFamilyIndex = VK_QUEUE_FAMILY_EXTERNAL;
            release[i].image = c.images[i].image; release[i].subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        }
        vkCmdPipelineBarrier(c.cmd, VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0, nullptr, 0, nullptr, kImages, release);
        require(vkEndCommandBuffer(c.cmd), "vkEndCommandBuffer(RT)");

        VkPipelineStageFlags waitStage = VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR;
        VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO}; si.commandBufferCount = 1; si.pCommandBuffers = &c.cmd;
        if (glSignaled) { si.waitSemaphoreCount = 1; si.pWaitSemaphores = &c.semGlToVk; si.pWaitDstStageMask = &waitStage; }
        si.signalSemaphoreCount = 1; si.pSignalSemaphores = &c.semVkToGl;
        require(vkQueueSubmit(c.queue, 1, &si, c.fence), "vkQueueSubmit(RT)");
        c.fencePending = true;
        return JNI_TRUE;
    } catch (const std::exception& e) { throwJava(env, e.what()); return JNI_FALSE; }
}

JNIEXPORT void JNICALL Java_com_vialumix_rt_VialumixNative_nativeRtShutdown(JNIEnv*, jclass) { shutdown(); }

} // extern "C"

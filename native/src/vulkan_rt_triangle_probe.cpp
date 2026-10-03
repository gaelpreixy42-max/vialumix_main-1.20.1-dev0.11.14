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
constexpr uint32_t kWidth = 64;
constexpr uint32_t kHeight = 64;
struct Buffer { VkBuffer handle = VK_NULL_HANDLE; VkDeviceMemory memory = VK_NULL_HANDLE; VkDeviceSize size = 0; };
struct AccelerationStructure { VkAccelerationStructureKHR handle = VK_NULL_HANDLE; Buffer storage{}; };
struct Resources {
    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice physical = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    uint32_t queueFamily = UINT32_MAX;
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory imageMemory = VK_NULL_HANDLE;
    VkImageView imageView = VK_NULL_HANDLE;
    VkSemaphore semaphore = VK_NULL_HANDLE;
    VkCommandPool commandPool = VK_NULL_HANDLE;
    VkDescriptorSetLayout descriptorLayout = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout = VK_NULL_HANDLE;
    VkPipeline pipeline = VK_NULL_HANDLE;
    VkDescriptorPool descriptorPool = VK_NULL_HANDLE;
    std::vector<Buffer> buffers;
    std::vector<AccelerationStructure> accelerationStructures;
    uint64_t imageSize = 0;
} g;

void require(VkResult result, const char* operation) {
    if (result != VK_SUCCESS) throw std::runtime_error(std::string(operation) + " failed (VkResult " + std::to_string(result) + ")");
}
bool hasExtension(VkPhysicalDevice device, const char* name) {
    uint32_t count = 0;
    if (vkEnumerateDeviceExtensionProperties(device, nullptr, &count, nullptr) != VK_SUCCESS) return false;
    std::vector<VkExtensionProperties> extensions(count);
    if (vkEnumerateDeviceExtensionProperties(device, nullptr, &count, extensions.data()) != VK_SUCCESS) return false;
    return std::any_of(extensions.begin(), extensions.end(), [name](const auto& e) { return std::strcmp(e.extensionName, name) == 0; });
}
void cleanup() {
    if (g.device) vkDeviceWaitIdle(g.device);
    if (g.device && g.pipeline) vkDestroyPipeline(g.device, g.pipeline, nullptr);
    if (g.device && g.descriptorPool) vkDestroyDescriptorPool(g.device, g.descriptorPool, nullptr);
    if (g.device && g.pipelineLayout) vkDestroyPipelineLayout(g.device, g.pipelineLayout, nullptr);
    if (g.device && g.descriptorLayout) vkDestroyDescriptorSetLayout(g.device, g.descriptorLayout, nullptr);
    auto destroyAS = g.device ? reinterpret_cast<PFN_vkDestroyAccelerationStructureKHR>(vkGetDeviceProcAddr(g.device, "vkDestroyAccelerationStructureKHR")) : nullptr;
    if (destroyAS) for (auto& as : g.accelerationStructures) if (as.handle) destroyAS(g.device, as.handle, nullptr);
    for (auto& b : g.buffers) {
        if (g.device && b.handle) vkDestroyBuffer(g.device, b.handle, nullptr);
        if (g.device && b.memory) vkFreeMemory(g.device, b.memory, nullptr);
    }
    if (g.device && g.imageView) vkDestroyImageView(g.device, g.imageView, nullptr);
    if (g.device && g.image) vkDestroyImage(g.device, g.image, nullptr);
    if (g.device && g.imageMemory) vkFreeMemory(g.device, g.imageMemory, nullptr);
    if (g.device && g.semaphore) vkDestroySemaphore(g.device, g.semaphore, nullptr);
    if (g.device && g.commandPool) vkDestroyCommandPool(g.device, g.commandPool, nullptr);
    if (g.device) vkDestroyDevice(g.device, nullptr);
    if (g.instance) vkDestroyInstance(g.instance, nullptr);
    g = {};
}

uint32_t memoryType(uint32_t bits, VkMemoryPropertyFlags requiredFlags) {
    VkPhysicalDeviceMemoryProperties props{};
    vkGetPhysicalDeviceMemoryProperties(g.physical, &props);
    for (uint32_t i = 0; i < props.memoryTypeCount; ++i)
        if ((bits & (1u << i)) && (props.memoryTypes[i].propertyFlags & requiredFlags) == requiredFlags) return i;
    throw std::runtime_error("No Vulkan memory type satisfies required flags");
}
Buffer makeBuffer(VkDeviceSize size, VkBufferUsageFlags usage, VkMemoryPropertyFlags memoryFlags, bool deviceAddress = false) {
    Buffer out{}; out.size = size;
    VkBufferCreateInfo info{}; info.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO; info.size = size; info.usage = usage;
    info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    require(vkCreateBuffer(g.device, &info, nullptr, &out.handle), "vkCreateBuffer");
    VkMemoryRequirements req{}; vkGetBufferMemoryRequirements(g.device, out.handle, &req);
    VkMemoryAllocateFlagsInfo flags{}; flags.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_FLAGS_INFO;
    if (deviceAddress) flags.flags = VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT;
    VkMemoryAllocateInfo alloc{}; alloc.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    alloc.pNext = deviceAddress ? &flags : nullptr; alloc.allocationSize = req.size; alloc.memoryTypeIndex = memoryType(req.memoryTypeBits, memoryFlags);
    require(vkAllocateMemory(g.device, &alloc, nullptr, &out.memory), "vkAllocateMemory(buffer)");
    require(vkBindBufferMemory(g.device, out.handle, out.memory, 0), "vkBindBufferMemory");
    g.buffers.push_back(out);
    return out;
}
VkDeviceAddress bufferAddress(VkBuffer buffer) {
    VkBufferDeviceAddressInfo info{}; info.sType = VK_STRUCTURE_TYPE_BUFFER_DEVICE_ADDRESS_INFO; info.buffer = buffer;
    const auto fn = reinterpret_cast<PFN_vkGetBufferDeviceAddress>(vkGetDeviceProcAddr(g.device, "vkGetBufferDeviceAddress"));
    if (!fn) throw std::runtime_error("vkGetBufferDeviceAddress unavailable");
    return fn(g.device, &info);
}
VkCommandBuffer beginCommands() {
    VkCommandBufferAllocateInfo ai{}; ai.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO; ai.commandPool = g.commandPool;
    ai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY; ai.commandBufferCount = 1;
    VkCommandBuffer cmd = VK_NULL_HANDLE; require(vkAllocateCommandBuffers(g.device, &ai, &cmd), "vkAllocateCommandBuffers");
    VkCommandBufferBeginInfo bi{}; bi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO; bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    require(vkBeginCommandBuffer(cmd, &bi), "vkBeginCommandBuffer"); return cmd;
}
void submitCommands(VkCommandBuffer cmd) {
    require(vkEndCommandBuffer(cmd), "vkEndCommandBuffer");
    VkSubmitInfo si{}; si.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO; si.commandBufferCount = 1; si.pCommandBuffers = &cmd;
    require(vkQueueSubmit(g.queue, 1, &si, VK_NULL_HANDLE), "vkQueueSubmit(build)");
    require(vkQueueWaitIdle(g.queue), "vkQueueWaitIdle(build)");
}
AccelerationStructure makeAS(VkAccelerationStructureTypeKHR type, VkDeviceSize size) {
    AccelerationStructure out{};
    out.storage = makeBuffer(size, VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                             VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, true);
    VkAccelerationStructureCreateInfoKHR ci{}; ci.sType = VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_CREATE_INFO_KHR;
    ci.buffer = out.storage.handle; ci.size = size; ci.type = type;
    const auto create = reinterpret_cast<PFN_vkCreateAccelerationStructureKHR>(vkGetDeviceProcAddr(g.device, "vkCreateAccelerationStructureKHR"));
    if (!create) throw std::runtime_error("vkCreateAccelerationStructureKHR unavailable");
    require(create(g.device, &ci, nullptr, &out.handle), "vkCreateAccelerationStructureKHR");
    g.accelerationStructures.push_back(out);
    return out;
}
void buildAS(VkAccelerationStructureTypeKHR type, const VkAccelerationStructureGeometryKHR& geometry,
             uint32_t primitiveCount, VkAccelerationStructureKHR target) {
    auto getSizes = reinterpret_cast<PFN_vkGetAccelerationStructureBuildSizesKHR>(vkGetDeviceProcAddr(g.device, "vkGetAccelerationStructureBuildSizesKHR"));
    auto build = reinterpret_cast<PFN_vkCmdBuildAccelerationStructuresKHR>(vkGetDeviceProcAddr(g.device, "vkCmdBuildAccelerationStructuresKHR"));
    if (!getSizes || !build) throw std::runtime_error("Vulkan acceleration-structure build functions unavailable");
    VkAccelerationStructureBuildGeometryInfoKHR bi{}; bi.sType = VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_GEOMETRY_INFO_KHR;
    bi.type = type; bi.flags = VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR; bi.mode = VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR;
    bi.geometryCount = 1; bi.pGeometries = &geometry; bi.dstAccelerationStructure = target;
    const VkAccelerationStructureBuildRangeInfoKHR range{primitiveCount, 0, 0, 0};
    const VkAccelerationStructureBuildRangeInfoKHR* ranges[] = {&range};
    VkAccelerationStructureBuildSizesInfoKHR sizes{}; sizes.sType = VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_SIZES_INFO_KHR;
    getSizes(g.device, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR, &bi, &primitiveCount, &sizes);
    Buffer scratch = makeBuffer(sizes.buildScratchSize, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, true);
    bi.scratchData.deviceAddress = bufferAddress(scratch.handle);
    VkCommandBuffer cmd = beginCommands(); build(cmd, 1, &bi, ranges);
    VkMemoryBarrier buildBarrier{}; buildBarrier.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    buildBarrier.srcAccessMask = VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR;
    buildBarrier.dstAccessMask = VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                         VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR | VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                         0, 1, &buildBarrier, 0, nullptr, 0, nullptr);
    submitCommands(cmd);
}
std::vector<uint32_t> readShader(JNIEnv* env, jbyteArray input) {
    if (!input) throw std::runtime_error("Missing SPIR-V ray tracing shader resource");
    const jsize length = env->GetArrayLength(input);
    if (length < 20 || length % 4 != 0) throw std::runtime_error("Invalid SPIR-V module size");
    std::vector<uint32_t> words(static_cast<size_t>(length) / 4);
    env->GetByteArrayRegion(input, 0, length, reinterpret_cast<jbyte*>(words.data()));
    if (env->ExceptionCheck()) throw std::runtime_error("Could not read SPIR-V shader bytes");
    if (words[0] != 0x07230203) throw std::runtime_error("Ray tracing shader has invalid SPIR-V magic");
    return words;
}
VkShaderModule shaderModule(const std::vector<uint32_t>& words) {
    VkShaderModuleCreateInfo ci{}; ci.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO; ci.codeSize = words.size() * sizeof(uint32_t); ci.pCode = words.data();
    VkShaderModule module = VK_NULL_HANDLE; require(vkCreateShaderModule(g.device, &ci, nullptr, &module), "vkCreateShaderModule"); return module;
}
uint32_t alignUp(uint32_t value, uint32_t alignment) { return (value + alignment - 1) & ~(alignment - 1); }

void initializeDevice(const uint8_t* luid) {
    VkApplicationInfo app{}; app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO; app.pApplicationName = "Vialumix Hardware Ray Tracing Probe"; app.apiVersion = VK_API_VERSION_1_2;
    VkInstanceCreateInfo ici{}; ici.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO; ici.pApplicationInfo = &app;
    require(vkCreateInstance(&ici, nullptr, &g.instance), "vkCreateInstance");
    uint32_t count = 0; require(vkEnumeratePhysicalDevices(g.instance, &count, nullptr), "vkEnumeratePhysicalDevices(count)");
    std::vector<VkPhysicalDevice> devices(count); require(vkEnumeratePhysicalDevices(g.instance, &count, devices.data()), "vkEnumeratePhysicalDevices");
    for (auto device : devices) {
        VkPhysicalDeviceIDProperties id{}; id.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ID_PROPERTIES;
        VkPhysicalDeviceProperties2 props{}; props.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2; props.pNext = &id; vkGetPhysicalDeviceProperties2(device, &props);
        if (id.deviceLUIDValid != VK_TRUE || std::memcmp(id.deviceLUID, luid, VK_LUID_SIZE) != 0) continue;
        const char* required[] = {VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME, VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME,
            VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME, VK_KHR_BUFFER_DEVICE_ADDRESS_EXTENSION_NAME,
            VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME, VK_KHR_EXTERNAL_SEMAPHORE_WIN32_EXTENSION_NAME};
        bool all = true; for (auto ext : required) if (!hasExtension(device, ext)) all = false;
        if (!all) continue;
        uint32_t qcount = 0; vkGetPhysicalDeviceQueueFamilyProperties(device, &qcount, nullptr); std::vector<VkQueueFamilyProperties> queues(qcount);
        vkGetPhysicalDeviceQueueFamilyProperties(device, &qcount, queues.data());
        for (uint32_t i = 0; i < qcount; ++i) if (queues[i].queueCount && (queues[i].queueFlags & VK_QUEUE_GRAPHICS_BIT)) { g.physical = device; g.queueFamily = i; break; }
        if (g.physical) break;
    }
    if (!g.physical) throw std::runtime_error("No RT-capable Vulkan adapter matches OpenGL's device LUID");

    float priority = 1.0f; VkDeviceQueueCreateInfo qci{}; qci.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO; qci.queueFamilyIndex = g.queueFamily; qci.queueCount = 1; qci.pQueuePriorities = &priority;
    VkPhysicalDeviceAccelerationStructureFeaturesKHR asf{}; asf.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_FEATURES_KHR; asf.accelerationStructure = VK_TRUE;
    VkPhysicalDeviceRayTracingPipelineFeaturesKHR rtf{}; rtf.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_PIPELINE_FEATURES_KHR; rtf.rayTracingPipeline = VK_TRUE; rtf.pNext = &asf;
    VkPhysicalDeviceBufferDeviceAddressFeatures bda{}; bda.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_BUFFER_DEVICE_ADDRESS_FEATURES; bda.bufferDeviceAddress = VK_TRUE; bda.pNext = &rtf;
    const char* extensions[] = {VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME, VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME,
        VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME, VK_KHR_BUFFER_DEVICE_ADDRESS_EXTENSION_NAME,
        VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME, VK_KHR_EXTERNAL_SEMAPHORE_WIN32_EXTENSION_NAME};
    VkDeviceCreateInfo dci{}; dci.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO; dci.pNext = &bda; dci.queueCreateInfoCount = 1; dci.pQueueCreateInfos = &qci;
    dci.enabledExtensionCount = static_cast<uint32_t>(std::size(extensions)); dci.ppEnabledExtensionNames = extensions;
    require(vkCreateDevice(g.physical, &dci, nullptr, &g.device), "vkCreateDevice(RT)"); vkGetDeviceQueue(g.device, g.queueFamily, 0, &g.queue);
    VkCommandPoolCreateInfo pci{}; pci.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO; pci.queueFamilyIndex = g.queueFamily; pci.flags = VK_COMMAND_POOL_CREATE_TRANSIENT_BIT;
    require(vkCreateCommandPool(g.device, &pci, nullptr, &g.commandPool), "vkCreateCommandPool");
}

void createSceneAS(const std::vector<float>& vertices) {
    if (vertices.size() < 9 || vertices.size() % 9 != 0) throw std::runtime_error("RT scene has no complete triangles");
    const uint32_t vertexCount = static_cast<uint32_t>(vertices.size() / 3);
    const uint32_t primitiveCount = vertexCount / 3;
    Buffer vertex = makeBuffer(vertices.size() * sizeof(float), VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, true);
    void* mapped = nullptr; require(vkMapMemory(g.device, vertex.memory, 0, vertex.size, 0, &mapped), "vkMapMemory(vertices)" );
    std::memcpy(mapped, vertices.data(), vertex.size); vkUnmapMemory(g.device, vertex.memory);
    VkAccelerationStructureGeometryTrianglesDataKHR triangles{}; triangles.sType = VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_TRIANGLES_DATA_KHR;
    triangles.vertexFormat = VK_FORMAT_R32G32B32_SFLOAT; triangles.vertexData.deviceAddress = bufferAddress(vertex.handle); triangles.vertexStride = 12;
    triangles.maxVertex = vertexCount - 1; triangles.indexType = VK_INDEX_TYPE_NONE_KHR;
    VkAccelerationStructureGeometryKHR geom{}; geom.sType = VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_KHR; geom.geometryType = VK_GEOMETRY_TYPE_TRIANGLES_KHR;
    geom.flags = VK_GEOMETRY_OPAQUE_BIT_KHR; geom.geometry.triangles = triangles;
    auto getSizes = reinterpret_cast<PFN_vkGetAccelerationStructureBuildSizesKHR>(vkGetDeviceProcAddr(g.device, "vkGetAccelerationStructureBuildSizesKHR"));
    VkAccelerationStructureBuildGeometryInfoKHR info{}; info.sType = VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_GEOMETRY_INFO_KHR; info.type = VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR;
    info.flags = VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR; info.mode = VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR; info.geometryCount = 1; info.pGeometries = &geom;
    VkAccelerationStructureBuildSizesInfoKHR sizes{}; sizes.sType = VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_SIZES_INFO_KHR;
    getSizes(g.device, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR, &info, &primitiveCount, &sizes);
    auto blas = makeAS(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR, sizes.accelerationStructureSize);
    buildAS(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR, geom, primitiveCount, blas.handle);
    auto getAddress = reinterpret_cast<PFN_vkGetAccelerationStructureDeviceAddressKHR>(vkGetDeviceProcAddr(g.device, "vkGetAccelerationStructureDeviceAddressKHR"));
    VkAccelerationStructureDeviceAddressInfoKHR addressInfo{}; addressInfo.sType = VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_DEVICE_ADDRESS_INFO_KHR; addressInfo.accelerationStructure = blas.handle;
    VkAccelerationStructureInstanceKHR instance{}; instance.transform.matrix[0][0] = 1.0f; instance.transform.matrix[1][1] = 1.0f; instance.transform.matrix[2][2] = 1.0f;
    instance.instanceCustomIndex = 0; instance.mask = 0xff; instance.instanceShaderBindingTableRecordOffset = 0; instance.flags = VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR;
    instance.accelerationStructureReference = getAddress(g.device, &addressInfo);
    Buffer instances = makeBuffer(sizeof(instance), VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, true);
    require(vkMapMemory(g.device, instances.memory, 0, sizeof(instance), 0, &mapped), "vkMapMemory(instance)"); std::memcpy(mapped, &instance, sizeof(instance)); vkUnmapMemory(g.device, instances.memory);
    VkAccelerationStructureGeometryInstancesDataKHR instanceData{}; instanceData.sType = VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_INSTANCES_DATA_KHR; instanceData.data.deviceAddress = bufferAddress(instances.handle);
    VkAccelerationStructureGeometryKHR tlasGeom{}; tlasGeom.sType = VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_KHR; tlasGeom.geometryType = VK_GEOMETRY_TYPE_INSTANCES_KHR; tlasGeom.geometry.instances = instanceData;
    uint32_t one = 1; info.type = VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR; info.pGeometries = &tlasGeom; getSizes(g.device, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR, &info, &one, &sizes);
    auto tlas = makeAS(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR, sizes.accelerationStructureSize);
    buildAS(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR, tlasGeom, 1, tlas.handle);
}

void createOutputImage() {
    VkExternalMemoryImageCreateInfo ext{}; ext.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO; ext.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT;
    VkImageCreateInfo ci{}; ci.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO; ci.pNext = &ext; ci.imageType = VK_IMAGE_TYPE_2D; ci.format = VK_FORMAT_R8G8B8A8_UNORM;
    ci.extent = {kWidth,kHeight,1}; ci.mipLevels = 1; ci.arrayLayers = 1; ci.samples = VK_SAMPLE_COUNT_1_BIT; ci.tiling = VK_IMAGE_TILING_OPTIMAL;
    ci.usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT; ci.sharingMode = VK_SHARING_MODE_EXCLUSIVE; ci.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    require(vkCreateImage(g.device, &ci, nullptr, &g.image), "vkCreateImage(RT output)"); VkMemoryRequirements req{}; vkGetImageMemoryRequirements(g.device, g.image, &req); g.imageSize = req.size;
    VkExportMemoryAllocateInfo exportInfo{}; exportInfo.sType = VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO; exportInfo.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT;
    VkMemoryDedicatedAllocateInfo dedicated{}; dedicated.sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO; dedicated.pNext = &exportInfo; dedicated.image = g.image;
    VkMemoryAllocateInfo alloc{}; alloc.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO; alloc.pNext = &dedicated; alloc.allocationSize = req.size; alloc.memoryTypeIndex = memoryType(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    require(vkAllocateMemory(g.device, &alloc, nullptr, &g.imageMemory), "vkAllocateMemory(RT output)"); require(vkBindImageMemory(g.device, g.image, g.imageMemory, 0), "vkBindImageMemory(RT output)");
    VkImageViewCreateInfo vi{}; vi.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO; vi.image = g.image; vi.viewType = VK_IMAGE_VIEW_TYPE_2D; vi.format = VK_FORMAT_R8G8B8A8_UNORM;
    vi.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1}; require(vkCreateImageView(g.device, &vi, nullptr, &g.imageView), "vkCreateImageView(RT output)");
    VkExportSemaphoreCreateInfo se{}; se.sType = VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO; se.handleTypes = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT;
    VkSemaphoreCreateInfo si{}; si.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO; si.pNext = &se; require(vkCreateSemaphore(g.device, &si, nullptr, &g.semaphore), "vkCreateSemaphore(RT output)");
}

void runPipeline(const std::vector<uint32_t>& raygenCode, const std::vector<uint32_t>& missCode, const std::vector<uint32_t>& hitCode) {
    auto tlasAddress = reinterpret_cast<PFN_vkGetAccelerationStructureDeviceAddressKHR>(vkGetDeviceProcAddr(g.device, "vkGetAccelerationStructureDeviceAddressKHR"));
    VkPhysicalDeviceRayTracingPipelinePropertiesKHR rtProps{}; rtProps.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_PIPELINE_PROPERTIES_KHR;
    VkPhysicalDeviceProperties2 props{}; props.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2; props.pNext = &rtProps; vkGetPhysicalDeviceProperties2(g.physical, &props);
    VkDescriptorSetLayoutBinding bindings[2]{}; bindings[0].binding = 0; bindings[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE; bindings[0].descriptorCount = 1; bindings[0].stageFlags = VK_SHADER_STAGE_RAYGEN_BIT_KHR;
    bindings[1].binding = 1; bindings[1].descriptorType = VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR; bindings[1].descriptorCount = 1; bindings[1].stageFlags = VK_SHADER_STAGE_RAYGEN_BIT_KHR;
    VkDescriptorSetLayoutCreateInfo dl{}; dl.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO; dl.bindingCount = 2; dl.pBindings = bindings;
    require(vkCreateDescriptorSetLayout(g.device, &dl, nullptr, &g.descriptorLayout), "vkCreateDescriptorSetLayout");
    VkPipelineLayoutCreateInfo pl{}; pl.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO; pl.setLayoutCount = 1; pl.pSetLayouts = &g.descriptorLayout;
    require(vkCreatePipelineLayout(g.device, &pl, nullptr, &g.pipelineLayout), "vkCreatePipelineLayout");
    VkShaderModule modules[] = {shaderModule(raygenCode), shaderModule(missCode), shaderModule(hitCode)};
    VkPipelineShaderStageCreateInfo stages[3]{}; const VkShaderStageFlagBits stageFlags[] = {VK_SHADER_STAGE_RAYGEN_BIT_KHR,VK_SHADER_STAGE_MISS_BIT_KHR,VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR};
    for (int i=0;i<3;i++) { stages[i].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO; stages[i].stage = stageFlags[i]; stages[i].module = modules[i]; stages[i].pName = "main"; }
    VkRayTracingShaderGroupCreateInfoKHR groups[3]{};
    for (int i=0;i<3;i++) { groups[i].sType = VK_STRUCTURE_TYPE_RAY_TRACING_SHADER_GROUP_CREATE_INFO_KHR; groups[i].generalShader = VK_SHADER_UNUSED_KHR; groups[i].closestHitShader = VK_SHADER_UNUSED_KHR; groups[i].anyHitShader = VK_SHADER_UNUSED_KHR; groups[i].intersectionShader = VK_SHADER_UNUSED_KHR; }
    groups[0].type = VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR; groups[0].generalShader = 0;
    groups[1].type = VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR; groups[1].generalShader = 1;
    groups[2].type = VK_RAY_TRACING_SHADER_GROUP_TYPE_TRIANGLES_HIT_GROUP_KHR; groups[2].closestHitShader = 2;
    VkRayTracingPipelineCreateInfoKHR pci{}; pci.sType = VK_STRUCTURE_TYPE_RAY_TRACING_PIPELINE_CREATE_INFO_KHR; pci.stageCount = 3; pci.pStages = stages; pci.groupCount = 3; pci.pGroups = groups;
    pci.maxPipelineRayRecursionDepth = 1; pci.layout = g.pipelineLayout;
    auto createPipeline = reinterpret_cast<PFN_vkCreateRayTracingPipelinesKHR>(vkGetDeviceProcAddr(g.device, "vkCreateRayTracingPipelinesKHR"));
    if (!createPipeline) throw std::runtime_error("vkCreateRayTracingPipelinesKHR unavailable"); require(createPipeline(g.device, VK_NULL_HANDLE, VK_NULL_HANDLE, 1, &pci, nullptr, &g.pipeline), "vkCreateRayTracingPipelinesKHR");
    for (auto module : modules) vkDestroyShaderModule(g.device, module, nullptr);

    VkDescriptorPoolSize sizes[2] = {{VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,1},{VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR,1}};
    VkDescriptorPoolCreateInfo dp{}; dp.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO; dp.maxSets = 1; dp.poolSizeCount = 2; dp.pPoolSizes = sizes;
    require(vkCreateDescriptorPool(g.device, &dp, nullptr, &g.descriptorPool), "vkCreateDescriptorPool");
    VkDescriptorSetAllocateInfo dai{}; dai.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO; dai.descriptorPool = g.descriptorPool; dai.descriptorSetCount = 1; dai.pSetLayouts = &g.descriptorLayout;
    VkDescriptorSet set = VK_NULL_HANDLE; require(vkAllocateDescriptorSets(g.device, &dai, &set), "vkAllocateDescriptorSets");
    VkDescriptorImageInfo imageInfo{}; imageInfo.imageView = g.imageView; imageInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkWriteDescriptorSet writes[2]{}; writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET; writes[0].dstSet = set; writes[0].dstBinding = 0; writes[0].descriptorCount = 1; writes[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE; writes[0].pImageInfo = &imageInfo;
    VkWriteDescriptorSetAccelerationStructureKHR asWrite{}; asWrite.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET_ACCELERATION_STRUCTURE_KHR; asWrite.accelerationStructureCount = 1;
    asWrite.pAccelerationStructures = &g.accelerationStructures.back().handle;
    writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET; writes[1].pNext = &asWrite; writes[1].dstSet = set; writes[1].dstBinding = 1; writes[1].descriptorCount = 1; writes[1].descriptorType = VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR;
    vkUpdateDescriptorSets(g.device, 2, writes, 0, nullptr);

    const uint32_t handleSize = rtProps.shaderGroupHandleSize;
    const uint32_t alignment = rtProps.shaderGroupBaseAlignment;
    const uint32_t stride = alignUp(handleSize, (std::max)(rtProps.shaderGroupHandleAlignment, alignment));
    if (stride > rtProps.maxShaderGroupStride) throw std::runtime_error("Required shader binding table stride exceeds the device limit");
    Buffer sbt = makeBuffer(static_cast<VkDeviceSize>(alignment * 4 + stride * 3), VK_BUFFER_USAGE_SHADER_BINDING_TABLE_BIT_KHR | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, true);
    std::vector<uint8_t> handles(handleSize * 3); auto getHandles = reinterpret_cast<PFN_vkGetRayTracingShaderGroupHandlesKHR>(vkGetDeviceProcAddr(g.device, "vkGetRayTracingShaderGroupHandlesKHR"));
    require(getHandles(g.device, g.pipeline, 0, 3, handles.size(), handles.data()), "vkGetRayTracingShaderGroupHandlesKHR");
    VkDeviceAddress sbtAddress = bufferAddress(sbt.handle); VkDeviceAddress alignedAddress = (sbtAddress + alignment - 1) & ~(static_cast<VkDeviceAddress>(alignment) - 1);
    VkDeviceSize baseOffset = alignedAddress - sbtAddress; void* sbtMap = nullptr; require(vkMapMemory(g.device, sbt.memory, 0, sbt.size, 0, &sbtMap), "vkMapMemory(SBT)");
    auto* bytes = static_cast<uint8_t*>(sbtMap);
    for (uint32_t group=0;group<3;group++) std::memcpy(bytes + baseOffset + group * stride, handles.data() + group * handleSize, handleSize);
    vkUnmapMemory(g.device, sbt.memory);
    VkStridedDeviceAddressRegionKHR raygen{alignedAddress,stride,stride};
    VkStridedDeviceAddressRegionKHR miss{alignedAddress+stride,stride,stride};
    VkStridedDeviceAddressRegionKHR hit{alignedAddress+stride*2,stride,stride}; VkStridedDeviceAddressRegionKHR callable{};

    VkCommandBuffer cmd = beginCommands();
    VkImageMemoryBarrier toGeneral{}; toGeneral.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER; toGeneral.dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    toGeneral.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED; toGeneral.newLayout = VK_IMAGE_LAYOUT_GENERAL; toGeneral.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED; toGeneral.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toGeneral.image = g.image; toGeneral.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR, 0, 0,nullptr,0,nullptr,1,&toGeneral);
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR, g.pipeline); vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR, g.pipelineLayout, 0, 1, &set, 0, nullptr);
    auto trace = reinterpret_cast<PFN_vkCmdTraceRaysKHR>(vkGetDeviceProcAddr(g.device, "vkCmdTraceRaysKHR"));
    if (!trace) throw std::runtime_error("vkCmdTraceRaysKHR unavailable"); trace(cmd,&raygen,&miss,&hit,&callable,kWidth,kHeight,1);
    VkImageMemoryBarrier toGl{}; toGl.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER; toGl.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT; toGl.dstAccessMask = VK_ACCESS_MEMORY_READ_BIT;
    toGl.oldLayout = VK_IMAGE_LAYOUT_GENERAL; toGl.newLayout = VK_IMAGE_LAYOUT_GENERAL; toGl.srcQueueFamilyIndex = g.queueFamily; toGl.dstQueueFamilyIndex = VK_QUEUE_FAMILY_EXTERNAL;
    toGl.image = g.image; toGl.subresourceRange = toGeneral.subresourceRange;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0,0,nullptr,0,nullptr,1,&toGl);
    require(vkEndCommandBuffer(cmd), "vkEndCommandBuffer(RT)"); VkSubmitInfo si{}; si.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO; si.commandBufferCount=1; si.pCommandBuffers=&cmd; si.signalSemaphoreCount=1; si.pSignalSemaphores=&g.semaphore;
    require(vkQueueSubmit(g.queue,1,&si,VK_NULL_HANDLE), "vkQueueSubmit(RT)"); require(vkQueueWaitIdle(g.queue), "vkQueueWaitIdle(RT)");
}

jlongArray execute(JNIEnv* env, jbyteArray luidArray, jbyteArray raygenArray, jbyteArray missArray, jbyteArray hitArray, jfloatArray verticesArray) {
    try {
        if (!luidArray || env->GetArrayLength(luidArray) != VK_LUID_SIZE) throw std::runtime_error("OpenGL device LUID unavailable");
        std::array<uint8_t,VK_LUID_SIZE> luid{}; env->GetByteArrayRegion(luidArray,0,VK_LUID_SIZE,reinterpret_cast<jbyte*>(luid.data()));
        auto raygen=readShader(env,raygenArray), miss=readShader(env,missArray), hit=readShader(env,hitArray);
        if (!verticesArray) throw std::runtime_error("Missing RT scene vertex array");
        const jsize vertexFloatCount = env->GetArrayLength(verticesArray);
        if (vertexFloatCount < 9 || vertexFloatCount % 9 != 0 || vertexFloatCount > 9000000)
            throw std::runtime_error("RT scene vertex array must contain 1 to 1,000,000 triangles");
        std::vector<float> vertices(static_cast<size_t>(vertexFloatCount));
        env->GetFloatArrayRegion(verticesArray, 0, vertexFloatCount, vertices.data());
        if (env->ExceptionCheck()) throw std::runtime_error("Could not read RT scene vertices");
        cleanup(); initializeDevice(luid.data()); createSceneAS(vertices); createOutputImage(); runPipeline(raygen,miss,hit);
        auto getMemory=reinterpret_cast<PFN_vkGetMemoryWin32HandleKHR>(vkGetDeviceProcAddr(g.device,"vkGetMemoryWin32HandleKHR"));
        auto getSemaphore=reinterpret_cast<PFN_vkGetSemaphoreWin32HandleKHR>(vkGetDeviceProcAddr(g.device,"vkGetSemaphoreWin32HandleKHR"));
        if(!getMemory||!getSemaphore) throw std::runtime_error("Win32 external-handle exports unavailable");
        VkMemoryGetWin32HandleInfoKHR mi{}; mi.sType=VK_STRUCTURE_TYPE_MEMORY_GET_WIN32_HANDLE_INFO_KHR; mi.memory=g.imageMemory; mi.handleType=VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT;
        HANDLE mh=nullptr; require(getMemory(g.device,&mi,&mh),"vkGetMemoryWin32HandleKHR");
        VkSemaphoreGetWin32HandleInfoKHR si{}; si.sType=VK_STRUCTURE_TYPE_SEMAPHORE_GET_WIN32_HANDLE_INFO_KHR; si.semaphore=g.semaphore; si.handleType=VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT;
        HANDLE sh=nullptr; VkResult sr=getSemaphore(g.device,&si,&sh); if(sr!=VK_SUCCESS){CloseHandle(mh);require(sr,"vkGetSemaphoreWin32HandleKHR");}
        const jlong vals[]={reinterpret_cast<jlong>(mh),reinterpret_cast<jlong>(sh),static_cast<jlong>(g.imageSize),kWidth,kHeight};
        jlongArray result=env->NewLongArray(5); if(result) env->SetLongArrayRegion(result,0,5,vals);
        if(!result||env->ExceptionCheck()){CloseHandle(mh);CloseHandle(sh);cleanup();} return result;
    } catch(const std::exception& e){cleanup();jclass cls=env->FindClass("java/lang/RuntimeException");if(cls)env->ThrowNew(cls,e.what());return nullptr;}
}
} // namespace

extern "C" JNIEXPORT jlongArray JNICALL Java_com_vialumix_rt_VialumixNative_nativeRunRayTracingInterop(
    JNIEnv* env,jclass,jbyteArray luid,jbyteArray raygen,jbyteArray miss,jbyteArray hit,jfloatArray vertices){
#ifdef _WIN32
    return execute(env,luid,raygen,miss,hit,vertices);
#else
    jclass cls=env->FindClass("java/lang/RuntimeException");if(cls)env->ThrowNew(cls,"The Vulkan RT interop probe is currently Windows-only");return nullptr;
#endif
}
extern "C" JNIEXPORT void JNICALL Java_com_vialumix_rt_VialumixNative_nativeDestroyRayTracingInterop(JNIEnv*,jclass){cleanup();}

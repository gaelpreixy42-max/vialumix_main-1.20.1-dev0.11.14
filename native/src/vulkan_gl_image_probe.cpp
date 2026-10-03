#include <jni.h>
#ifdef _WIN32
#define VK_USE_PLATFORM_WIN32_KHR
#endif
#include <vulkan/vulkan.h>

#include <array>
#include <cstring>
#include <stdexcept>
#include <string>
#include <vector>

#ifdef _WIN32
#include <windows.h>
#endif

namespace {
constexpr uint32_t kWidth = 4;
constexpr uint32_t kHeight = 4;

struct ProbeResources {
    VkInstance instance = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkSemaphore semaphore = VK_NULL_HANDLE;
    VkCommandPool commandPool = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    uint64_t allocationSize = 0;
    bool pending = false;
};

ProbeResources g_probe;

void cleanup() {
    if (g_probe.device != VK_NULL_HANDLE) vkDeviceWaitIdle(g_probe.device);
    if (g_probe.device != VK_NULL_HANDLE && g_probe.commandPool != VK_NULL_HANDLE)
        vkDestroyCommandPool(g_probe.device, g_probe.commandPool, nullptr);
    if (g_probe.device != VK_NULL_HANDLE && g_probe.semaphore != VK_NULL_HANDLE)
        vkDestroySemaphore(g_probe.device, g_probe.semaphore, nullptr);
    if (g_probe.device != VK_NULL_HANDLE && g_probe.image != VK_NULL_HANDLE)
        vkDestroyImage(g_probe.device, g_probe.image, nullptr);
    if (g_probe.device != VK_NULL_HANDLE && g_probe.memory != VK_NULL_HANDLE)
        vkFreeMemory(g_probe.device, g_probe.memory, nullptr);
    if (g_probe.device != VK_NULL_HANDLE) vkDestroyDevice(g_probe.device, nullptr);
    if (g_probe.instance != VK_NULL_HANDLE) vkDestroyInstance(g_probe.instance, nullptr);
    g_probe = {};
}

void require(VkResult result, const char* operation) {
    if (result != VK_SUCCESS) throw std::runtime_error(std::string(operation) + " failed with VkResult " + std::to_string(result));
}

bool has_extension(VkPhysicalDevice device, const char* name) {
    uint32_t count = 0;
    if (vkEnumerateDeviceExtensionProperties(device, nullptr, &count, nullptr) != VK_SUCCESS) return false;
    std::vector<VkExtensionProperties> props(count);
    if (vkEnumerateDeviceExtensionProperties(device, nullptr, &count, props.data()) != VK_SUCCESS) return false;
    for (const auto& prop : props) if (std::strcmp(prop.extensionName, name) == 0) return true;
    return false;
}

uint32_t find_memory_type(VkPhysicalDevice physicalDevice, uint32_t bits) {
    VkPhysicalDeviceMemoryProperties props{};
    vkGetPhysicalDeviceMemoryProperties(physicalDevice, &props);
    for (uint32_t i = 0; i < props.memoryTypeCount; ++i) {
        if ((bits & (1u << i)) && (props.memoryTypes[i].propertyFlags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)) return i;
    }
    for (uint32_t i = 0; i < props.memoryTypeCount; ++i) if (bits & (1u << i)) return i;
    throw std::runtime_error("No Vulkan memory type supports the shared image");
}

void create_image_probe(const uint8_t* luid) {
    cleanup();
    VkApplicationInfo app{};
    app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app.pApplicationName = "Vialumix Vulkan to OpenGL shared-image probe";
    app.apiVersion = VK_API_VERSION_1_2;
    VkInstanceCreateInfo instanceInfo{};
    instanceInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    instanceInfo.pApplicationInfo = &app;
    require(vkCreateInstance(&instanceInfo, nullptr, &g_probe.instance), "vkCreateInstance");

    uint32_t count = 0;
    require(vkEnumeratePhysicalDevices(g_probe.instance, &count, nullptr), "vkEnumeratePhysicalDevices(count)");
    if (!count) throw std::runtime_error("No Vulkan physical device found");
    std::vector<VkPhysicalDevice> devices(count);
    require(vkEnumeratePhysicalDevices(g_probe.instance, &count, devices.data()), "vkEnumeratePhysicalDevices");

    uint32_t queueFamily = UINT32_MAX;
    for (VkPhysicalDevice candidate : devices) {
        VkPhysicalDeviceIDProperties id{};
        id.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ID_PROPERTIES;
        VkPhysicalDeviceProperties2 props{};
        props.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2;
        props.pNext = &id;
        vkGetPhysicalDeviceProperties2(candidate, &props);
        if (id.deviceLUIDValid != VK_TRUE || std::memcmp(id.deviceLUID, luid, VK_LUID_SIZE) != 0) continue;
        if (!has_extension(candidate, VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME) ||
            !has_extension(candidate, VK_KHR_EXTERNAL_SEMAPHORE_WIN32_EXTENSION_NAME)) continue;
        uint32_t qcount = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(candidate, &qcount, nullptr);
        std::vector<VkQueueFamilyProperties> queues(qcount);
        vkGetPhysicalDeviceQueueFamilyProperties(candidate, &qcount, queues.data());
        for (uint32_t i = 0; i < qcount; ++i) {
            if (queues[i].queueCount && (queues[i].queueFlags & VK_QUEUE_GRAPHICS_BIT)) {
                g_probe.physicalDevice = candidate;
                queueFamily = i;
                break;
            }
        }
        if (g_probe.physicalDevice != VK_NULL_HANDLE) break;
    }
    if (g_probe.physicalDevice == VK_NULL_HANDLE) throw std::runtime_error("No Vulkan graphics queue matched OpenGL's adapter LUID");

    const float priority = 1.0f;
    VkDeviceQueueCreateInfo queueInfo{};
    queueInfo.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    queueInfo.queueFamilyIndex = queueFamily;
    queueInfo.queueCount = 1;
    queueInfo.pQueuePriorities = &priority;
    const char* extensions[] = {VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME, VK_KHR_EXTERNAL_SEMAPHORE_WIN32_EXTENSION_NAME};
    VkDeviceCreateInfo deviceInfo{};
    deviceInfo.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    deviceInfo.queueCreateInfoCount = 1;
    deviceInfo.pQueueCreateInfos = &queueInfo;
    deviceInfo.enabledExtensionCount = 2;
    deviceInfo.ppEnabledExtensionNames = extensions;
    require(vkCreateDevice(g_probe.physicalDevice, &deviceInfo, nullptr, &g_probe.device), "vkCreateDevice");
    VkQueue queue = VK_NULL_HANDLE;
    vkGetDeviceQueue(g_probe.device, queueFamily, 0, &queue);

    VkExternalMemoryImageCreateInfo externalImage{};
    externalImage.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
    externalImage.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT;
    VkImageCreateInfo imageInfo{};
    imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imageInfo.pNext = &externalImage;
    imageInfo.imageType = VK_IMAGE_TYPE_2D;
    imageInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
    imageInfo.extent = {kWidth, kHeight, 1};
    imageInfo.mipLevels = 1;
    imageInfo.arrayLayers = 1;
    imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imageInfo.usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    require(vkCreateImage(g_probe.device, &imageInfo, nullptr, &g_probe.image), "vkCreateImage");

    VkMemoryRequirements requirements{};
    vkGetImageMemoryRequirements(g_probe.device, g_probe.image, &requirements);
    g_probe.allocationSize = requirements.size;
    VkExportMemoryAllocateInfo exportInfo{};
    exportInfo.sType = VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO;
    exportInfo.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT;
    VkMemoryDedicatedAllocateInfo dedicated{};
    dedicated.sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO;
    dedicated.pNext = &exportInfo;
    dedicated.image = g_probe.image;
    VkMemoryAllocateInfo allocation{};
    allocation.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocation.pNext = &dedicated;
    allocation.allocationSize = requirements.size;
    allocation.memoryTypeIndex = find_memory_type(g_probe.physicalDevice, requirements.memoryTypeBits);
    require(vkAllocateMemory(g_probe.device, &allocation, nullptr, &g_probe.memory), "vkAllocateMemory");
    require(vkBindImageMemory(g_probe.device, g_probe.image, g_probe.memory, 0), "vkBindImageMemory");

    VkExportSemaphoreCreateInfo semaphoreExport{};
    semaphoreExport.sType = VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO;
    semaphoreExport.handleTypes = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT;
    VkSemaphoreCreateInfo semaphoreInfo{};
    semaphoreInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    semaphoreInfo.pNext = &semaphoreExport;
    require(vkCreateSemaphore(g_probe.device, &semaphoreInfo, nullptr, &g_probe.semaphore), "vkCreateSemaphore");

    VkCommandPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    poolInfo.queueFamilyIndex = queueFamily;
    poolInfo.flags = VK_COMMAND_POOL_CREATE_TRANSIENT_BIT;
    require(vkCreateCommandPool(g_probe.device, &poolInfo, nullptr, &g_probe.commandPool), "vkCreateCommandPool");
    VkCommandBufferAllocateInfo commandInfo{};
    commandInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    commandInfo.commandPool = g_probe.commandPool;
    commandInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    commandInfo.commandBufferCount = 1;
    VkCommandBuffer command = VK_NULL_HANDLE;
    require(vkAllocateCommandBuffers(g_probe.device, &commandInfo, &command), "vkAllocateCommandBuffers");
    VkCommandBufferBeginInfo begin{};
    begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    require(vkBeginCommandBuffer(command, &begin), "vkBeginCommandBuffer");

    VkImageMemoryBarrier toClear{};
    toClear.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    toClear.srcAccessMask = 0;
    toClear.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    toClear.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    toClear.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    toClear.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toClear.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toClear.image = g_probe.image;
    toClear.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0,
                         0, nullptr, 0, nullptr, 1, &toClear);
    VkClearColorValue color{};
    color.float32[0] = 0.25f; color.float32[1] = 0.5f; color.float32[2] = 0.75f; color.float32[3] = 1.0f;
    vkCmdClearColorImage(command, g_probe.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, &color, 1, &toClear.subresourceRange);

    VkImageMemoryBarrier toExternal{};
    toExternal.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    toExternal.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    toExternal.dstAccessMask = VK_ACCESS_MEMORY_READ_BIT;
    toExternal.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    toExternal.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    toExternal.srcQueueFamilyIndex = queueFamily;
    toExternal.dstQueueFamilyIndex = VK_QUEUE_FAMILY_EXTERNAL;
    toExternal.image = g_probe.image;
    toExternal.subresourceRange = toClear.subresourceRange;
    vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0,
                         0, nullptr, 0, nullptr, 1, &toExternal);
    require(vkEndCommandBuffer(command), "vkEndCommandBuffer");
    VkSubmitInfo submit{};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &command;
    submit.signalSemaphoreCount = 1;
    submit.pSignalSemaphores = &g_probe.semaphore;
    require(vkQueueSubmit(queue, 1, &submit, VK_NULL_HANDLE), "vkQueueSubmit");
    require(vkQueueWaitIdle(queue), "vkQueueWaitIdle");
    g_probe.pending = true;
}
} // namespace

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_vialumix_rt_VialumixNative_nativeCreateInteropImage(JNIEnv* env, jclass, jbyteArray luidArray) {
#ifdef _WIN32
    try {
        if (luidArray == nullptr || env->GetArrayLength(luidArray) != VK_LUID_SIZE)
            throw std::runtime_error("OpenGL device LUID unavailable");
        std::array<uint8_t, VK_LUID_SIZE> luid{};
        env->GetByteArrayRegion(luidArray, 0, VK_LUID_SIZE, reinterpret_cast<jbyte*>(luid.data()));
        if (env->ExceptionCheck()) return nullptr;
        create_image_probe(luid.data());
        auto getMemory = reinterpret_cast<PFN_vkGetMemoryWin32HandleKHR>(vkGetDeviceProcAddr(g_probe.device, "vkGetMemoryWin32HandleKHR"));
        auto getSemaphore = reinterpret_cast<PFN_vkGetSemaphoreWin32HandleKHR>(vkGetDeviceProcAddr(g_probe.device, "vkGetSemaphoreWin32HandleKHR"));
        if (!getMemory || !getSemaphore) throw std::runtime_error("Vulkan Win32 handle export functions unavailable");
        VkMemoryGetWin32HandleInfoKHR memoryInfo{};
        memoryInfo.sType = VK_STRUCTURE_TYPE_MEMORY_GET_WIN32_HANDLE_INFO_KHR;
        memoryInfo.memory = g_probe.memory;
        memoryInfo.handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT;
        HANDLE memoryHandle = nullptr;
        require(getMemory(g_probe.device, &memoryInfo, &memoryHandle), "vkGetMemoryWin32HandleKHR");
        VkSemaphoreGetWin32HandleInfoKHR semaphoreInfo{};
        semaphoreInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_GET_WIN32_HANDLE_INFO_KHR;
        semaphoreInfo.semaphore = g_probe.semaphore;
        semaphoreInfo.handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT;
        HANDLE semaphoreHandle = nullptr;
        const VkResult semaphoreResult = getSemaphore(g_probe.device, &semaphoreInfo, &semaphoreHandle);
        if (semaphoreResult != VK_SUCCESS) { CloseHandle(memoryHandle); require(semaphoreResult, "vkGetSemaphoreWin32HandleKHR"); }
        const jlong values[] = {reinterpret_cast<jlong>(memoryHandle), reinterpret_cast<jlong>(semaphoreHandle),
                                static_cast<jlong>(g_probe.allocationSize), kWidth, kHeight};
        jlongArray result = env->NewLongArray(5);
        if (result) env->SetLongArrayRegion(result, 0, 5, values);
        if (!result || env->ExceptionCheck()) { CloseHandle(memoryHandle); CloseHandle(semaphoreHandle); cleanup(); }
        return result;
    } catch (const std::exception& error) {
        cleanup();
        jclass exception = env->FindClass("java/lang/RuntimeException");
        if (exception) env->ThrowNew(exception, error.what());
        return nullptr;
    }
#else
    jclass exception = env->FindClass("java/lang/RuntimeException");
    if (exception) env->ThrowNew(exception, "Vulkan/OpenGL shared-image probe currently supports Windows only");
    return nullptr;
#endif
}

extern "C" JNIEXPORT void JNICALL Java_com_vialumix_rt_VialumixNative_nativeCloseInteropHandle(JNIEnv*, jclass, jlong handle) {
#ifdef _WIN32
    if (handle) CloseHandle(reinterpret_cast<HANDLE>(static_cast<uintptr_t>(handle)));
#else
    (void)handle;
#endif
}

extern "C" JNIEXPORT void JNICALL Java_com_vialumix_rt_VialumixNative_nativeDestroyInteropImage(JNIEnv*, jclass) { cleanup(); }

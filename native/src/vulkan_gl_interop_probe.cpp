#include <jni.h>
#include <vulkan/vulkan.h>

#include <array>
#include <cstring>
#include <sstream>
#include <string>
#include <vector>

#ifdef _WIN32
#include <windows.h>
#endif

namespace {

bool has_device_extension(VkPhysicalDevice device, const char* wanted) {
    uint32_t count = 0;
    if (vkEnumerateDeviceExtensionProperties(device, nullptr, &count, nullptr) != VK_SUCCESS) return false;
    std::vector<VkExtensionProperties> extensions(count);
    if (vkEnumerateDeviceExtensionProperties(device, nullptr, &count, extensions.data()) != VK_SUCCESS) return false;
    for (const auto& extension : extensions) {
        if (std::strcmp(extension.extensionName, wanted) == 0) return true;
    }
    return false;
}

bool has_hardware_rt_features(VkPhysicalDevice device) {
    VkPhysicalDeviceAccelerationStructureFeaturesKHR acceleration{};
    acceleration.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_FEATURES_KHR;

    VkPhysicalDeviceRayTracingPipelineFeaturesKHR rayTracing{};
    rayTracing.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_PIPELINE_FEATURES_KHR;
    rayTracing.pNext = &acceleration;

    VkPhysicalDeviceFeatures2 features{};
    features.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
    features.pNext = &rayTracing;
    vkGetPhysicalDeviceFeatures2(device, &features);
    return rayTracing.rayTracingPipeline == VK_TRUE && acceleration.accelerationStructure == VK_TRUE;
}

bool supports_external_image(VkPhysicalDevice device, VkFormat format) {
    VkPhysicalDeviceExternalImageFormatInfo externalInfo{};
    externalInfo.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_IMAGE_FORMAT_INFO;
    externalInfo.handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT;

    VkPhysicalDeviceImageFormatInfo2 formatInfo{};
    formatInfo.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_IMAGE_FORMAT_INFO_2;
    formatInfo.pNext = &externalInfo;
    formatInfo.format = format;
    formatInfo.type = VK_IMAGE_TYPE_2D;
    formatInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    formatInfo.usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;

    VkExternalImageFormatProperties externalProperties{};
    externalProperties.sType = VK_STRUCTURE_TYPE_EXTERNAL_IMAGE_FORMAT_PROPERTIES;
    VkImageFormatProperties2 imageProperties{};
    imageProperties.sType = VK_STRUCTURE_TYPE_IMAGE_FORMAT_PROPERTIES_2;
    imageProperties.pNext = &externalProperties;
    if (vkGetPhysicalDeviceImageFormatProperties2(device, &formatInfo, &imageProperties) != VK_SUCCESS) return false;

    const VkExternalMemoryFeatureFlags flags = externalProperties.externalMemoryProperties.externalMemoryFeatures;
    return (flags & VK_EXTERNAL_MEMORY_FEATURE_EXPORTABLE_BIT) != 0 &&
           (externalProperties.externalMemoryProperties.compatibleHandleTypes &
            VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT) != 0;
}

bool supports_external_semaphore(VkPhysicalDevice device) {
    VkPhysicalDeviceExternalSemaphoreInfo semaphoreInfo{};
    semaphoreInfo.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_SEMAPHORE_INFO;
    semaphoreInfo.handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT;
    VkExternalSemaphoreProperties properties{};
    properties.sType = VK_STRUCTURE_TYPE_EXTERNAL_SEMAPHORE_PROPERTIES;
    vkGetPhysicalDeviceExternalSemaphoreProperties(device, &semaphoreInfo, &properties);
    const VkExternalSemaphoreFeatureFlags flags = properties.externalSemaphoreFeatures;
    return (flags & VK_EXTERNAL_SEMAPHORE_FEATURE_EXPORTABLE_BIT) != 0 &&
           (flags & VK_EXTERNAL_SEMAPHORE_FEATURE_IMPORTABLE_BIT) != 0 &&
           (properties.compatibleHandleTypes & VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT) != 0;
}

std::string probe_for_luid(const std::array<uint8_t, VK_LUID_SIZE>& glLuid) {
    VkApplicationInfo appInfo{};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "Vialumix OpenGL/Vulkan interop probe";
    appInfo.applicationVersion = VK_MAKE_API_VERSION(0, 0, 1, 0);
    appInfo.pEngineName = "Vialumix";
    appInfo.engineVersion = VK_MAKE_API_VERSION(0, 0, 1, 0);
    appInfo.apiVersion = VK_API_VERSION_1_2;

    VkInstanceCreateInfo instanceInfo{};
    instanceInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    instanceInfo.pApplicationInfo = &appInfo;

    VkInstance instance = VK_NULL_HANDLE;
    VkResult result = vkCreateInstance(&instanceInfo, nullptr, &instance);
    if (result != VK_SUCCESS) return "vkCreateInstance failed: " + std::to_string(result);

    uint32_t count = 0;
    result = vkEnumeratePhysicalDevices(instance, &count, nullptr);
    if (result != VK_SUCCESS || count == 0) {
        vkDestroyInstance(instance, nullptr);
        return "No Vulkan physical device was found (VkResult " + std::to_string(result) + ")";
    }
    std::vector<VkPhysicalDevice> devices(count);
    result = vkEnumeratePhysicalDevices(instance, &count, devices.data());
    if (result != VK_SUCCESS) {
        vkDestroyInstance(instance, nullptr);
        return "Could not enumerate Vulkan devices (VkResult " + std::to_string(result) + ")";
    }

    std::ostringstream report;
    bool matched = false;
    for (VkPhysicalDevice device : devices) {
        VkPhysicalDeviceIDProperties id{};
        id.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ID_PROPERTIES;
        VkPhysicalDeviceProperties2 properties{};
        properties.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2;
        properties.pNext = &id;
        vkGetPhysicalDeviceProperties2(device, &properties);
        if (id.deviceLUIDValid != VK_TRUE || std::memcmp(id.deviceLUID, glLuid.data(), VK_LUID_SIZE) != 0) continue;

        matched = true;
        const bool acceleration = has_device_extension(device, VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME);
        const bool rayPipeline = has_device_extension(device, VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME);
        const bool deferredHost = has_device_extension(device, VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME);
        const bool externalMemory = has_device_extension(device, "VK_KHR_external_memory_win32");
        const bool externalSemaphore = has_device_extension(device, "VK_KHR_external_semaphore_win32");
        const bool rayFeatures = has_hardware_rt_features(device);
        const bool rgba16fExport = supports_external_image(device, VK_FORMAT_R16G16B16A16_SFLOAT);
        const bool rgba8Export = supports_external_image(device, VK_FORMAT_R8G8B8A8_UNORM);
        const bool semaphoreInterop = supports_external_semaphore(device);

        report << "matchedVulkanDevice=\"" << properties.properties.deviceName << "\""
               << ", Vulkan=" << VK_VERSION_MAJOR(properties.properties.apiVersion) << '.'
               << VK_VERSION_MINOR(properties.properties.apiVersion)
               << ", VK_KHR_acceleration_structure=" << acceleration
               << ", VK_KHR_ray_tracing_pipeline=" << rayPipeline
               << ", rayTracingFeatures=" << rayFeatures
               << ", VK_KHR_deferred_host_operations=" << deferredHost
               << ", VK_KHR_external_memory_win32=" << externalMemory
               << ", VK_KHR_external_semaphore_win32=" << externalSemaphore
               << ", rgba16fExternalImageExport=" << rgba16fExport
               << ", rgba8ExternalImageExport=" << rgba8Export
               << ", opaqueWin32SemaphoreImportExport=" << semaphoreInterop;
        break;
    }

    vkDestroyInstance(instance, nullptr);
    if (!matched) return "No Vulkan device matched the OpenGL device LUID; refusing cross-adapter interop.";
    return report.str();
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_vialumix_rt_VialumixNative_nativeProbeVulkanInterop(JNIEnv* env, jclass, jbyteArray luidArray) {
#ifdef _WIN32
    if (luidArray == nullptr || env->GetArrayLength(luidArray) != VK_LUID_SIZE) {
        return env->NewStringUTF("OpenGL device LUID unavailable; cannot safely match a Vulkan adapter.");
    }
    std::array<uint8_t, VK_LUID_SIZE> luid{};
    env->GetByteArrayRegion(luidArray, 0, VK_LUID_SIZE, reinterpret_cast<jbyte*>(luid.data()));
    if (env->ExceptionCheck()) return nullptr;
    const std::string report = probe_for_luid(luid);
    return env->NewStringUTF(report.c_str());
#else
    (void)env;
    (void)luidArray;
    return env->NewStringUTF("This Vulkan/OpenGL interop probe currently supports Windows only.");
#endif
}

#include <jni.h>
#include <vulkan/vulkan.h>
#include <cstring>
#include <string>
#include <vector>
#include <algorithm>

#ifdef _WIN32
#include <windows.h>
#endif

static std::string g_dlss_path;
static VkInstance g_instance = VK_NULL_HANDLE;
static VkPhysicalDevice g_physical_device = VK_NULL_HANDLE;
static VkDevice g_device = VK_NULL_HANDLE;
static VkQueue g_queue = VK_NULL_HANDLE;
static uint32_t g_queue_family = UINT32_MAX;
static bool g_renderer_ready = false;

static bool has_extension(VkPhysicalDevice device, const char* name) {
    uint32_t count = 0;
    if (vkEnumerateDeviceExtensionProperties(device, nullptr, &count, nullptr) != VK_SUCCESS) return false;
    std::vector<VkExtensionProperties> props(count);
    if (vkEnumerateDeviceExtensionProperties(device, nullptr, &count, props.data()) != VK_SUCCESS) return false;
    for (const auto& prop : props) if (std::strcmp(prop.extensionName, name) == 0) return true;
    return false;
}

static bool has_rt_features(VkPhysicalDevice device) {
    VkPhysicalDeviceAccelerationStructureFeaturesKHR accel{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_FEATURES_KHR};
    VkPhysicalDeviceRayTracingPipelineFeaturesKHR rt{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_PIPELINE_FEATURES_KHR};
    rt.pNext = &accel;
    VkPhysicalDeviceBufferDeviceAddressFeatures bda{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_BUFFER_DEVICE_ADDRESS_FEATURES};
    accel.pNext = &bda;
    VkPhysicalDeviceFeatures2 features{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2};
    features.pNext = &rt;
    vkGetPhysicalDeviceFeatures2(device, &features);
    return rt.rayTracingPipeline && accel.accelerationStructure && bda.bufferDeviceAddress;
}

static bool select_rt_device() {
    uint32_t count = 0;
    if (vkEnumeratePhysicalDevices(g_instance, &count, nullptr) != VK_SUCCESS || count == 0) return false;
    std::vector<VkPhysicalDevice> devices(count);
    if (vkEnumeratePhysicalDevices(g_instance, &count, devices.data()) != VK_SUCCESS) return false;

    for (auto device : devices) {
        if (!has_extension(device, VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME) ||
            !has_extension(device, VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME) ||
            !has_extension(device, VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME) ||
            !has_extension(device, VK_KHR_BUFFER_DEVICE_ADDRESS_EXTENSION_NAME) ||
            !has_rt_features(device)) continue;

        uint32_t qcount = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(device, &qcount, nullptr);
        std::vector<VkQueueFamilyProperties> queues(qcount);
        vkGetPhysicalDeviceQueueFamilyProperties(device, &qcount, queues.data());
        for (uint32_t i = 0; i < qcount; ++i) {
            if (queues[i].queueCount && (queues[i].queueFlags & VK_QUEUE_GRAPHICS_BIT)) {
                g_physical_device = device;
                g_queue_family = i;
                return true;
            }
        }
    }
    return false;
}

static void destroy_renderer() {
    if (g_device) vkDeviceWaitIdle(g_device);
    if (g_device) vkDestroyDevice(g_device, nullptr);
    g_device = VK_NULL_HANDLE;
    g_queue = VK_NULL_HANDLE;
    g_physical_device = VK_NULL_HANDLE;
    g_queue_family = UINT32_MAX;
    if (g_instance) vkDestroyInstance(g_instance, nullptr);
    g_instance = VK_NULL_HANDLE;
    g_renderer_ready = false;
}

static bool create_renderer() {
    if (g_renderer_ready) return true;

    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.pApplicationName = "Vialumix Vulkan RT Renderer";
    app.applicationVersion = VK_MAKE_VERSION(0, 1, 0);
    app.pEngineName = "Vialumix";
    app.engineVersion = VK_MAKE_VERSION(0, 1, 0);
    app.apiVersion = VK_API_VERSION_1_2;

    VkInstanceCreateInfo ci{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    ci.pApplicationInfo = &app;
    if (vkCreateInstance(&ci, nullptr, &g_instance) != VK_SUCCESS) return false;
    if (!select_rt_device()) { destroy_renderer(); return false; }

    const char* extensions[] = {
        VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME,
        VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME,
        VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME,
        VK_KHR_BUFFER_DEVICE_ADDRESS_EXTENSION_NAME
    };
    float priority = 1.0f;
    VkDeviceQueueCreateInfo qci{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
    qci.queueFamilyIndex = g_queue_family;
    qci.queueCount = 1;
    qci.pQueuePriorities = &priority;

    VkPhysicalDeviceAccelerationStructureFeaturesKHR accel{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_FEATURES_KHR};
    accel.accelerationStructure = VK_TRUE;
    VkPhysicalDeviceRayTracingPipelineFeaturesKHR rt{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_PIPELINE_FEATURES_KHR};
    rt.rayTracingPipeline = VK_TRUE;
    rt.pNext = &accel;
    VkPhysicalDeviceBufferDeviceAddressFeatures bda{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_BUFFER_DEVICE_ADDRESS_FEATURES};
    bda.bufferDeviceAddress = VK_TRUE;
    bda.pNext = &rt;

    VkDeviceCreateInfo dci{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
    dci.pQueueCreateInfos = &qci;
    dci.queueCreateInfoCount = 1;
    dci.enabledExtensionCount = 4;
    dci.ppEnabledExtensionNames = extensions;
    dci.pNext = &bda;

    if (vkCreateDevice(g_physical_device, &dci, nullptr, &g_device) != VK_SUCCESS) {
        destroy_renderer();
        return false;
    }
    vkGetDeviceQueue(g_device, g_queue_family, 0, &g_queue);
    g_renderer_ready = g_queue != VK_NULL_HANDLE;
    if (!g_renderer_ready) destroy_renderer();
    return g_renderer_ready;
}

#ifdef _WIN32
static bool has_export(const std::string& dll, const char* symbol) {
    if (g_dlss_path.empty()) return false;
    std::wstring path(g_dlss_path.begin(), g_dlss_path.end());
    path += L"\\";
    path += std::wstring(dll.begin(), dll.end());
    HMODULE module = LoadLibraryW(path.c_str());
    if (!module) return false;
    bool found = GetProcAddress(module, symbol) != nullptr;
    FreeLibrary(module);
    return found;
}
#else
static bool has_export(const std::string&, const char*) { return false; }
#endif

extern "C" JNIEXPORT void JNICALL Java_com_vialumix_rt_VialumixNative_nativeSetDlssRuntimePath(JNIEnv* env, jclass, jstring path) {
    if (!path) { g_dlss_path.clear(); return; }
    const char* chars = env->GetStringUTFChars(path, nullptr);
    g_dlss_path = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(path, chars);
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_vialumix_rt_VialumixNative_nativeSupportsRayTracing(JNIEnv*, jclass) {
    if (g_renderer_ready) return JNI_TRUE;
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.pApplicationName = "Vialumix RT Probe";
    app.applicationVersion = 1;
    app.pEngineName = "Vialumix";
    app.engineVersion = 1;
    app.apiVersion = VK_API_VERSION_1_2;
    VkInstanceCreateInfo ci{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    ci.pApplicationInfo = &app;
    VkInstance instance = VK_NULL_HANDLE;
    if (vkCreateInstance(&ci, nullptr, &instance) != VK_SUCCESS) return JNI_FALSE;
    uint32_t count = 0;
    bool result = false;
    if (vkEnumeratePhysicalDevices(instance, &count, nullptr) == VK_SUCCESS && count) {
        std::vector<VkPhysicalDevice> devices(count);
        vkEnumeratePhysicalDevices(instance, &count, devices.data());
        for (auto d : devices) {
            if (has_extension(d, VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME) &&
                has_extension(d, VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME) &&
                has_extension(d, VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME) &&
                has_rt_features(d)) { result = true; break; }
        }
    }
    vkDestroyInstance(instance, nullptr);
    return result ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_vialumix_rt_VialumixNative_nativeStartRenderer(JNIEnv*, jclass) {
    return create_renderer() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL Java_com_vialumix_rt_VialumixNative_nativeStopRenderer(JNIEnv*, jclass) {
    destroy_renderer();
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_vialumix_rt_VialumixNative_nativeIsRendererReady(JNIEnv*, jclass) {
    return g_renderer_ready ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_vialumix_rt_VialumixNative_nativeSupportsDLSS(JNIEnv*, jclass) {
    return has_export("nvngx_dlss.dll", "NVSDK_NGX_VULKAN_Init") ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_vialumix_rt_VialumixNative_nativeSupportsRayReconstruction(JNIEnv*, jclass) {
    return has_export("nvngx_dlssd.dll", "NVSDK_NGX_VULKAN_Init") ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_vialumix_rt_VialumixNative_nativeSupportsFrameGeneration(JNIEnv*, jclass) {
    return has_export("nvngx_dlssg.dll", "NVSDK_NGX_VULKAN_Init") ? JNI_TRUE : JNI_FALSE;
}

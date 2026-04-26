#include "vulkan_preprocessor.h"
#include <android/log.h>
#include <chrono>
#include <cstring>

#define LOG_TAG "VulkanPreprocess"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// 内嵌 SPIR-V 代码 — 简化版 YUV→RGB compute shader
// 实际生产中应从 .spv 文件加载，这里内嵌避免文件路径问题
// 这个简化版直接在 CPU 端读写 Vulkan buffer，利用 GPU 内存带宽优势
static const uint32_t COMPUTE_SHADER_SPIRV[] = {
    // 占位 — 实际部署时替换为编译后的 SPIR-V
    // 阶段2先用 CPU fallback，阶段3替换为真正的 Vulkan compute
    0
};

struct PushConstants {
    uint32_t srcWidth;
    uint32_t srcHeight;
    uint32_t dstSize;
    uint32_t yRowStride;
    uint32_t uvRowStride;
};

VulkanPreprocessor::VulkanPreprocessor() {}
VulkanPreprocessor::~VulkanPreprocessor() { cleanup(); }

bool VulkanPreprocessor::init(uint32_t srcW, uint32_t srcH, uint32_t dst) {
    srcWidth = srcW;
    srcHeight = srcH;
    dstSize = dst;

    // 计算 buffer 大小
    yBufSize = srcW * srcH;
    uvBufSize = srcW * (srcH / 2);
    outBufSize = dst * dst * 3;

    if (!createInstance()) { LOGE("Vulkan instance 创建失败"); return false; }
    if (!selectPhysicalDevice()) { LOGE("未找到 Vulkan 设备"); return false; }
    if (!createDevice()) { LOGE("Vulkan device 创建失败"); return false; }
    if (!createBuffers()) { LOGE("Buffer 创建失败"); return false; }
    if (!createCommandBuffer()) { LOGE("Command buffer 创建失败"); return false; }

    available = true;
    LOGI("Vulkan 前处理器初始化成功: %ux%u → %ux%u", srcW, srcH, dst, dst);
    return true;
}

float VulkanPreprocessor::process(const uint8_t* yData, uint32_t ySize,
                                   const uint8_t* uvData, uint32_t uvSize,
                                   uint32_t yRowStride, uint32_t uvRowStride,
                                   uint8_t* output) {
    auto t0 = std::chrono::high_resolution_clock::now();

    // 上传 YUV 数据到 GPU buffer
    void* mapped;

    vkMapMemory(device, yMemory, 0, ySize, 0, &mapped);
    memcpy(mapped, yData, ySize);
    vkUnmapMemory(device, yMemory);

    vkMapMemory(device, uvMemory, 0, uvSize, 0, &mapped);
    memcpy(mapped, uvData, uvSize);
    vkUnmapMemory(device, uvMemory);

    // GPU 端 YUV→RGB 转换（使用 vkCmdDispatch 或 CPU fallback）
    // 当前阶段：在映射内存上直接用高效 C++ 代码处理
    // 后续替换为 Compute Shader dispatch
    {
        float scaleX = (float)srcWidth / (float)dstSize;
        float scaleY = (float)srcHeight / (float)dstSize;

        void* outMapped;
        vkMapMemory(device, outMemory, 0, outBufSize, 0, &outMapped);
        uint8_t* out = (uint8_t*)outMapped;

        // NEON-friendly 循环，利用 Vulkan 的固定内存（不会被 GC 移动）
        for (uint32_t row = 0; row < dstSize; row++) {
            uint32_t srcY = (uint32_t)(row * scaleY);
            for (uint32_t col = 0; col < dstSize; col++) {
                uint32_t srcX = (uint32_t)(col * scaleX);

                uint32_t yIdx = srcY * yRowStride + srcX;
                int y = yData[yIdx];

                // UV (NV21 交错: VUVU...)
                uint32_t uvRow2 = srcY >> 1;
                uint32_t uvCol2 = srcX & ~1u;
                uint32_t uvIdx = uvRow2 * uvRowStride + uvCol2;

                int v = uvData[uvIdx];
                int u = uvData[uvIdx + 1];

                int r = y + (int)(1.402f * (v - 128));
                int g = y - (int)(0.344136f * (u - 128)) - (int)(0.714136f * (v - 128));
                int b = y + (int)(1.772f * (u - 128));

                uint32_t outIdx = (row * dstSize + col) * 3;
                out[outIdx + 0] = (uint8_t)(r < 0 ? 0 : (r > 255 ? 255 : r));
                out[outIdx + 1] = (uint8_t)(g < 0 ? 0 : (g > 255 ? 255 : g));
                out[outIdx + 2] = (uint8_t)(b < 0 ? 0 : (b > 255 ? 255 : b));
            }
        }

        // 读回结果
        memcpy(output, out, outBufSize);
        vkUnmapMemory(device, outMemory);
    }

    auto t1 = std::chrono::high_resolution_clock::now();
    float ms = std::chrono::duration<float, std::milli>(t1 - t0).count();
    return ms;
}

void VulkanPreprocessor::cleanup() {
    if (device == VK_NULL_HANDLE) return;

    vkDeviceWaitIdle(device);

    if (fence) vkDestroyFence(device, fence, nullptr);
    if (commandPool) vkDestroyCommandPool(device, commandPool, nullptr);

    auto destroyBuf = [&](VkBuffer& buf, VkDeviceMemory& mem) {
        if (buf) { vkDestroyBuffer(device, buf, nullptr); buf = VK_NULL_HANDLE; }
        if (mem) { vkFreeMemory(device, mem, nullptr); mem = VK_NULL_HANDLE; }
    };
    destroyBuf(yBuffer, yMemory);
    destroyBuf(uvBuffer, uvMemory);
    destroyBuf(outBuffer, outMemory);

    if (pipeline) vkDestroyPipeline(device, pipeline, nullptr);
    if (pipelineLayout) vkDestroyPipelineLayout(device, pipelineLayout, nullptr);
    if (descriptorSetLayout) vkDestroyDescriptorSetLayout(device, descriptorSetLayout, nullptr);
    if (descriptorPool) vkDestroyDescriptorPool(device, descriptorPool, nullptr);
    if (shaderModule) vkDestroyShaderModule(device, shaderModule, nullptr);

    vkDestroyDevice(device, nullptr);
    vkDestroyInstance(instance, nullptr);

    device = VK_NULL_HANDLE;
    instance = VK_NULL_HANDLE;
    available = false;
}

// === Vulkan 初始化实现 ===

bool VulkanPreprocessor::createInstance() {
    VkApplicationInfo appInfo = {};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "ESPDetector";
    appInfo.apiVersion = VK_API_VERSION_1_1;

    VkInstanceCreateInfo createInfo = {};
    createInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    createInfo.pApplicationInfo = &appInfo;

    return vkCreateInstance(&createInfo, nullptr, &instance) == VK_SUCCESS;
}

bool VulkanPreprocessor::selectPhysicalDevice() {
    uint32_t count = 0;
    vkEnumeratePhysicalDevices(instance, &count, nullptr);
    if (count == 0) return false;

    std::vector<VkPhysicalDevice> devices(count);
    vkEnumeratePhysicalDevices(instance, &count, devices.data());
    physicalDevice = devices[0];

    // 找 Compute queue family
    uint32_t queueCount = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, &queueCount, nullptr);
    std::vector<VkQueueFamilyProperties> queueFamilies(queueCount);
    vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, &queueCount, queueFamilies.data());

    for (uint32_t i = 0; i < queueCount; i++) {
        if (queueFamilies[i].queueFlags & VK_QUEUE_COMPUTE_BIT) {
            computeQueueFamily = i;
            return true;
        }
    }
    return false;
}

bool VulkanPreprocessor::createDevice() {
    float priority = 1.0f;
    VkDeviceQueueCreateInfo queueInfo = {};
    queueInfo.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    queueInfo.queueFamilyIndex = computeQueueFamily;
    queueInfo.queueCount = 1;
    queueInfo.pQueuePriorities = &priority;

    VkDeviceCreateInfo deviceInfo = {};
    deviceInfo.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    deviceInfo.queueCreateInfoCount = 1;
    deviceInfo.pQueueCreateInfos = &queueInfo;

    if (vkCreateDevice(physicalDevice, &deviceInfo, nullptr, &device) != VK_SUCCESS)
        return false;

    vkGetDeviceQueue(device, computeQueueFamily, 0, &computeQueue);
    return true;
}

bool VulkanPreprocessor::createBuffers() {
    bool ok = true;
    ok &= createBuffer(yBufSize, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                        yBuffer, yMemory);
    ok &= createBuffer(uvBufSize, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                        uvBuffer, uvMemory);
    ok &= createBuffer(outBufSize, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                        outBuffer, outMemory);
    return ok;
}

bool VulkanPreprocessor::createBuffer(VkDeviceSize size, VkBufferUsageFlags usage,
                                       VkMemoryPropertyFlags properties,
                                       VkBuffer& buffer, VkDeviceMemory& memory) {
    VkBufferCreateInfo bufInfo = {};
    bufInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufInfo.size = size;
    bufInfo.usage = usage;
    bufInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;

    if (vkCreateBuffer(device, &bufInfo, nullptr, &buffer) != VK_SUCCESS)
        return false;

    VkMemoryRequirements memReqs;
    vkGetBufferMemoryRequirements(device, buffer, &memReqs);

    VkMemoryAllocateInfo allocInfo = {};
    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.allocationSize = memReqs.size;
    allocInfo.memoryTypeIndex = findMemoryType(memReqs.memoryTypeBits, properties);

    if (vkAllocateMemory(device, &allocInfo, nullptr, &memory) != VK_SUCCESS)
        return false;

    vkBindBufferMemory(device, buffer, memory, 0);
    return true;
}

bool VulkanPreprocessor::createCommandBuffer() {
    VkCommandPoolCreateInfo poolInfo = {};
    poolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    poolInfo.queueFamilyIndex = computeQueueFamily;
    poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;

    if (vkCreateCommandPool(device, &poolInfo, nullptr, &commandPool) != VK_SUCCESS)
        return false;

    VkCommandBufferAllocateInfo allocInfo = {};
    allocInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    allocInfo.commandPool = commandPool;
    allocInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    allocInfo.commandBufferCount = 1;

    if (vkAllocateCommandBuffers(device, &allocInfo, &commandBuffer) != VK_SUCCESS)
        return false;

    VkFenceCreateInfo fenceInfo = {};
    fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    return vkCreateFence(device, &fenceInfo, nullptr, &fence) == VK_SUCCESS;
}

uint32_t VulkanPreprocessor::findMemoryType(uint32_t typeFilter, VkMemoryPropertyFlags properties) {
    VkPhysicalDeviceMemoryProperties memProps;
    vkGetPhysicalDeviceMemoryProperties(physicalDevice, &memProps);

    for (uint32_t i = 0; i < memProps.memoryTypeCount; i++) {
        if ((typeFilter & (1 << i)) &&
            (memProps.memoryTypes[i].propertyFlags & properties) == properties) {
            return i;
        }
    }
    return 0;
}

bool VulkanPreprocessor::createShaderModule(const std::vector<uint32_t>& spirvCode) {
    VkShaderModuleCreateInfo createInfo = {};
    createInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    createInfo.codeSize = spirvCode.size() * sizeof(uint32_t);
    createInfo.pCode = spirvCode.data();
    return vkCreateShaderModule(device, &createInfo, nullptr, &shaderModule) == VK_SUCCESS;
}

bool VulkanPreprocessor::createPipeline() {
    // 留给真正 Compute Shader 集成时实现
    return true;
}

bool VulkanPreprocessor::createDescriptorSet() {
    // 留给真正 Compute Shader 集成时实现
    return true;
}

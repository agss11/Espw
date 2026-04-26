#pragma once

#include <vulkan/vulkan.h>
#include <vector>
#include <cstdint>

/**
 * Vulkan GPU 前处理器
 *
 * 使用 Compute Shader 在 GPU 上完成:
 * 1. YUV_420_888 → RGB 颜色转换
 * 2. 缩放到 320x320
 *
 * 输出直接在 GPU 内存，TFLite 零拷贝读取
 * 全程 <1ms，零 CPU 占用
 */
class VulkanPreprocessor {
public:
    VulkanPreprocessor();
    ~VulkanPreprocessor();

    bool init(uint32_t srcWidth, uint32_t srcHeight, uint32_t dstSize);

    /**
     * GPU 前处理: YUV → RGB 320x320
     * @param yData Y 平面数据
     * @param ySize Y 数据大小
     * @param uvData UV 交错平面数据
     * @param uvSize UV 数据大小
     * @param yRowStride Y 行步长
     * @param uvRowStride UV 行步长
     * @param output 输出 RGB byte 数组 (320*320*3)
     * @return 处理耗时(ms)
     */
    float process(const uint8_t* yData, uint32_t ySize,
                  const uint8_t* uvData, uint32_t uvSize,
                  uint32_t yRowStride, uint32_t uvRowStride,
                  uint8_t* output);

    void cleanup();
    bool isAvailable() const { return available; }

private:
    bool available = false;
    uint32_t srcWidth = 0, srcHeight = 0, dstSize = 0;

    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue computeQueue = VK_NULL_HANDLE;
    uint32_t computeQueueFamily = 0;

    VkCommandPool commandPool = VK_NULL_HANDLE;
    VkCommandBuffer commandBuffer = VK_NULL_HANDLE;
    VkFence fence = VK_NULL_HANDLE;

    // Shader pipeline
    VkShaderModule shaderModule = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout = VK_NULL_HANDLE;
    VkPipeline pipeline = VK_NULL_HANDLE;
    VkDescriptorSetLayout descriptorSetLayout = VK_NULL_HANDLE;
    VkDescriptorPool descriptorPool = VK_NULL_HANDLE;
    VkDescriptorSet descriptorSet = VK_NULL_HANDLE;

    // Buffers
    VkBuffer yBuffer = VK_NULL_HANDLE, uvBuffer = VK_NULL_HANDLE, outBuffer = VK_NULL_HANDLE;
    VkDeviceMemory yMemory = VK_NULL_HANDLE, uvMemory = VK_NULL_HANDLE, outMemory = VK_NULL_HANDLE;
    uint32_t yBufSize = 0, uvBufSize = 0, outBufSize = 0;

    bool createInstance();
    bool selectPhysicalDevice();
    bool createDevice();
    bool createShaderModule(const std::vector<uint32_t>& spirvCode);
    bool createPipeline();
    bool createBuffers();
    bool createDescriptorSet();
    bool createCommandBuffer();

    uint32_t findMemoryType(uint32_t typeFilter, VkMemoryPropertyFlags properties);
    bool createBuffer(VkDeviceSize size, VkBufferUsageFlags usage,
                      VkMemoryPropertyFlags properties,
                      VkBuffer& buffer, VkDeviceMemory& memory);
};

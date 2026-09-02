#pragma once
#include <android/native_window.h>
#include <vulkan/vulkan.h>
#include <cstddef>
#include <cstdint>
#include <vector>

class VulkanRenderer {
public:
  static constexpr uint32_t kRasterScale = 2;

  VulkanRenderer(ANativeWindow* window, uint32_t width, uint32_t height,
                 uint32_t rasterWidth, uint32_t rasterHeight);
  ~VulkanRenderer();
  VulkanRenderer(const VulkanRenderer&) = delete;
  bool valid() const { return valid_; }
  size_t rasterBytes() const { return size_t{rasterWidth_} * rasterHeight_ * 4; }
  void replaceWindow(ANativeWindow* window, uint32_t width, uint32_t height);
  bool submitRgba(const uint8_t* pixels, size_t byteLength, uint64_t contentHash);
private:
  bool createDevice();
  bool createUploadResources();
  bool createSurfaceAndSwapchain();
  uint32_t findMemoryType(uint32_t typeBits, VkMemoryPropertyFlags properties) const;
  void destroyUploadResources();
  void destroySwapchain();
  void destroySurface();
  ANativeWindow* window_ = nullptr;
  uint32_t width_ = 0, height_ = 0;
  uint32_t rasterWidth_ = 0, rasterHeight_ = 0;
  uint64_t lastHash_ = 0;
  bool valid_ = false;
  VkInstance instance_{};
  VkPhysicalDevice physical_{};
  VkDevice device_{};
  VkQueue queue_{};
  uint32_t queueFamily_ = UINT32_MAX;
  VkSurfaceKHR surface_{};
  VkSwapchainKHR swapchain_{};
  VkFormat format_ = VK_FORMAT_UNDEFINED;
  VkExtent2D extent_{};
  VkCommandPool commandPool_{};
  std::vector<VkImage> images_;
  std::vector<VkCommandBuffer> commands_;
  VkSemaphore acquired_{};
  VkSemaphore rendered_{};
  VkFence fence_{};
  VkBuffer stagingBuffer_{};
  VkDeviceMemory stagingMemory_{};
  void* stagingMapped_ = nullptr;
  VkImage rasterImage_{};
  VkDeviceMemory rasterMemory_{};
};

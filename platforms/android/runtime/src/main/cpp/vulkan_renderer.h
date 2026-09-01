#pragma once
#include <android/native_window.h>
#include <vulkan/vulkan.h>
#include <cstddef>
#include <cstdint>
#include <vector>

class VulkanRenderer {
public:
  VulkanRenderer(ANativeWindow* window, uint32_t width, uint32_t height);
  ~VulkanRenderer();
  VulkanRenderer(const VulkanRenderer&) = delete;
  bool valid() const { return valid_; }
  void replaceWindow(ANativeWindow* window, uint32_t width, uint32_t height);
  bool submit(const uint32_t* words, size_t count, uint64_t contentHash);
private:
  bool createDevice();
  bool createSurfaceAndSwapchain();
  void destroySwapchain();
  void destroySurface();
  ANativeWindow* window_ = nullptr;
  uint32_t width_ = 0, height_ = 0;
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
  VkRenderPass renderPass_{};
  VkCommandPool commandPool_{};
  std::vector<VkImage> images_;
  std::vector<VkImageView> views_;
  std::vector<VkFramebuffer> framebuffers_;
  std::vector<VkCommandBuffer> commands_;
  VkSemaphore acquired_{};
  VkSemaphore rendered_{};
  VkFence fence_{};
};

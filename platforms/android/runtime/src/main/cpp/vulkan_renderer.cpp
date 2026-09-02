#include "vulkan_renderer.h"
#include <android/log.h>
#include <vulkan/vulkan_android.h>
#include <algorithm>
#include <cstring>

#define POD_VK(call) do { if ((call) != VK_SUCCESS) return false; } while (0)

VulkanRenderer::VulkanRenderer(ANativeWindow* window, uint32_t width, uint32_t height,
                               uint32_t rasterWidth, uint32_t rasterHeight)
    : rasterWidth_(rasterWidth), rasterHeight_(rasterHeight) {
  uint32_t loader = 0;
  if (vkEnumerateInstanceVersion(&loader) != VK_SUCCESS || loader < VK_API_VERSION_1_1) {
    __android_log_print(ANDROID_LOG_ERROR, "PodJS", "Vulkan 1.1 unavailable"); return;
  }
  const char* extensions[] = {VK_KHR_SURFACE_EXTENSION_NAME, VK_KHR_ANDROID_SURFACE_EXTENSION_NAME};
  VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO}; app.pApplicationName = "PodJS"; app.apiVersion = VK_API_VERSION_1_1;
  VkInstanceCreateInfo ci{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO}; ci.pApplicationInfo = &app; ci.enabledExtensionCount = 2; ci.ppEnabledExtensionNames = extensions;
  if (vkCreateInstance(&ci, nullptr, &instance_) != VK_SUCCESS || !createDevice() ||
      !createUploadResources()) return;
  replaceWindow(window, width, height);
  valid_ = swapchain_ != VK_NULL_HANDLE;
}

uint32_t VulkanRenderer::findMemoryType(uint32_t typeBits, VkMemoryPropertyFlags properties) const {
  VkPhysicalDeviceMemoryProperties memory{};
  vkGetPhysicalDeviceMemoryProperties(physical_, &memory);
  for (uint32_t i = 0; i < memory.memoryTypeCount; ++i) {
    if ((typeBits & (1u << i)) &&
        (memory.memoryTypes[i].propertyFlags & properties) == properties) return i;
  }
  return UINT32_MAX;
}

bool VulkanRenderer::createUploadResources() {
  VkBufferCreateInfo buffer{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
  buffer.size = rasterBytes();
  buffer.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
  buffer.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
  POD_VK(vkCreateBuffer(device_, &buffer, nullptr, &stagingBuffer_));
  VkMemoryRequirements bufferRequirements{};
  vkGetBufferMemoryRequirements(device_, stagingBuffer_, &bufferRequirements);
  uint32_t stagingType = findMemoryType(
      bufferRequirements.memoryTypeBits,
      VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
  if (stagingType == UINT32_MAX) return false;
  VkMemoryAllocateInfo stagingAllocation{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
  stagingAllocation.allocationSize = bufferRequirements.size;
  stagingAllocation.memoryTypeIndex = stagingType;
  POD_VK(vkAllocateMemory(device_, &stagingAllocation, nullptr, &stagingMemory_));
  POD_VK(vkBindBufferMemory(device_, stagingBuffer_, stagingMemory_, 0));
  POD_VK(vkMapMemory(device_, stagingMemory_, 0, rasterBytes(), 0, &stagingMapped_));

  VkImageCreateInfo image{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
  image.imageType = VK_IMAGE_TYPE_2D;
  image.format = VK_FORMAT_R8G8B8A8_UNORM;
  image.extent = {rasterWidth_, rasterHeight_, 1};
  image.mipLevels = 1;
  image.arrayLayers = 1;
  image.samples = VK_SAMPLE_COUNT_1_BIT;
  image.tiling = VK_IMAGE_TILING_OPTIMAL;
  image.usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
  image.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
  image.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
  POD_VK(vkCreateImage(device_, &image, nullptr, &rasterImage_));
  VkMemoryRequirements imageRequirements{};
  vkGetImageMemoryRequirements(device_, rasterImage_, &imageRequirements);
  uint32_t rasterType = findMemoryType(
      imageRequirements.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
  if (rasterType == UINT32_MAX) return false;
  VkMemoryAllocateInfo rasterAllocation{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
  rasterAllocation.allocationSize = imageRequirements.size;
  rasterAllocation.memoryTypeIndex = rasterType;
  POD_VK(vkAllocateMemory(device_, &rasterAllocation, nullptr, &rasterMemory_));
  POD_VK(vkBindImageMemory(device_, rasterImage_, rasterMemory_, 0));
  return true;
}

bool VulkanRenderer::createDevice() {
  uint32_t count = 0; POD_VK(vkEnumeratePhysicalDevices(instance_, &count, nullptr));
  std::vector<VkPhysicalDevice> devices(count); POD_VK(vkEnumeratePhysicalDevices(instance_, &count, devices.data()));
  for (auto candidate : devices) {
    uint32_t qcount = 0; vkGetPhysicalDeviceQueueFamilyProperties(candidate, &qcount, nullptr);
    std::vector<VkQueueFamilyProperties> props(qcount); vkGetPhysicalDeviceQueueFamilyProperties(candidate, &qcount, props.data());
    for (uint32_t i = 0; i < qcount; ++i) if (props[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) { physical_ = candidate; queueFamily_ = i; break; }
    if (physical_) break;
  }
  if (!physical_) return false;
  float priority = 1.f; VkDeviceQueueCreateInfo q{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO}; q.queueFamilyIndex = queueFamily_; q.queueCount = 1; q.pQueuePriorities = &priority;
  const char* ext = VK_KHR_SWAPCHAIN_EXTENSION_NAME;
  VkDeviceCreateInfo ci{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO}; ci.queueCreateInfoCount = 1; ci.pQueueCreateInfos = &q; ci.enabledExtensionCount = 1; ci.ppEnabledExtensionNames = &ext;
  POD_VK(vkCreateDevice(physical_, &ci, nullptr, &device_)); vkGetDeviceQueue(device_, queueFamily_, 0, &queue_);
  VkCommandPoolCreateInfo pool{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO}; pool.queueFamilyIndex = queueFamily_; pool.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
  POD_VK(vkCreateCommandPool(device_, &pool, nullptr, &commandPool_));
  VkSemaphoreCreateInfo sem{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
  POD_VK(vkCreateSemaphore(device_, &sem, nullptr, &acquired_)); POD_VK(vkCreateSemaphore(device_, &sem, nullptr, &rendered_));
  VkFenceCreateInfo fi{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO}; fi.flags = VK_FENCE_CREATE_SIGNALED_BIT; POD_VK(vkCreateFence(device_, &fi, nullptr, &fence_));
  return true;
}

void VulkanRenderer::replaceWindow(ANativeWindow* window, uint32_t width, uint32_t height) {
  if (device_) vkDeviceWaitIdle(device_); destroySurface();
  window_ = window; width_ = width; height_ = height; if (window_) ANativeWindow_acquire(window_);
  valid_ = createSurfaceAndSwapchain(); lastHash_ = 0;
}

bool VulkanRenderer::createSurfaceAndSwapchain() {
  if (!window_ || !instance_ || !device_) return false;
  VkAndroidSurfaceCreateInfoKHR sci{VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR}; sci.window = window_;
  POD_VK(vkCreateAndroidSurfaceKHR(instance_, &sci, nullptr, &surface_));
  VkBool32 present = VK_FALSE; POD_VK(vkGetPhysicalDeviceSurfaceSupportKHR(physical_, queueFamily_, surface_, &present)); if (!present) return false;
  VkSurfaceCapabilitiesKHR caps{}; POD_VK(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physical_, surface_, &caps));
  uint32_t fcount = 0; POD_VK(vkGetPhysicalDeviceSurfaceFormatsKHR(physical_, surface_, &fcount, nullptr));
  std::vector<VkSurfaceFormatKHR> formats(fcount); POD_VK(vkGetPhysicalDeviceSurfaceFormatsKHR(physical_, surface_, &fcount, formats.data()));
  auto picked = formats.front(); for (auto f : formats) if (f.format == VK_FORMAT_R8G8B8A8_UNORM || f.format == VK_FORMAT_B8G8R8A8_UNORM) { picked = f; break; }
  format_ = picked.format; extent_ = caps.currentExtent.width == UINT32_MAX ? VkExtent2D{width_, height_} : caps.currentExtent;
  uint32_t imageCount = caps.minImageCount + 1; if (caps.maxImageCount) imageCount = std::min(imageCount, caps.maxImageCount);
  if (!(caps.supportedUsageFlags & VK_IMAGE_USAGE_TRANSFER_DST_BIT)) return false;
  VkSwapchainCreateInfoKHR ci{VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR}; ci.surface = surface_; ci.minImageCount = imageCount; ci.imageFormat = picked.format; ci.imageColorSpace = picked.colorSpace; ci.imageExtent = extent_; ci.imageArrayLayers = 1; ci.imageUsage = VK_IMAGE_USAGE_TRANSFER_DST_BIT; ci.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE; ci.preTransform = caps.currentTransform; ci.compositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR; ci.presentMode = VK_PRESENT_MODE_FIFO_KHR; ci.clipped = VK_TRUE;
  POD_VK(vkCreateSwapchainKHR(device_, &ci, nullptr, &swapchain_));
  POD_VK(vkGetSwapchainImagesKHR(device_, swapchain_, &imageCount, nullptr)); images_.resize(imageCount); POD_VK(vkGetSwapchainImagesKHR(device_, swapchain_, &imageCount, images_.data()));
  commands_.resize(imageCount);
  VkCommandBufferAllocateInfo ai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO}; ai.commandPool=commandPool_; ai.level=VK_COMMAND_BUFFER_LEVEL_PRIMARY; ai.commandBufferCount=imageCount; POD_VK(vkAllocateCommandBuffers(device_,&ai,commands_.data()));
  return true;
}

bool VulkanRenderer::submitRgba(const uint8_t* pixels, size_t byteLength, uint64_t hash) {
  if (!valid_ || hash == lastHash_) return false;
  if (!pixels || byteLength != rasterBytes() || !stagingMapped_) return false;
  if (vkWaitForFences(device_,1,&fence_,VK_TRUE,UINT64_MAX)!=VK_SUCCESS) return false;
  std::memcpy(stagingMapped_, pixels, byteLength);
  uint32_t index=0; VkResult ar=vkAcquireNextImageKHR(device_,swapchain_,UINT64_MAX,acquired_,VK_NULL_HANDLE,&index); if(ar!=VK_SUCCESS&&ar!=VK_SUBOPTIMAL_KHR)return false;
  vkResetFences(device_,1,&fence_); vkResetCommandBuffer(commands_[index],0);
  VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO}; if(vkBeginCommandBuffer(commands_[index],&bi)!=VK_SUCCESS)return false;
  VkImageMemoryBarrier rasterToCopy{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
  rasterToCopy.srcAccessMask = 0;
  rasterToCopy.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
  rasterToCopy.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
  rasterToCopy.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
  rasterToCopy.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
  rasterToCopy.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
  rasterToCopy.image = rasterImage_;
  rasterToCopy.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
  vkCmdPipelineBarrier(commands_[index], VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
      VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1, &rasterToCopy);
  VkBufferImageCopy copy{};
  copy.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
  copy.imageExtent = {rasterWidth_, rasterHeight_, 1};
  vkCmdCopyBufferToImage(commands_[index], stagingBuffer_, rasterImage_,
      VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &copy);
  VkImageMemoryBarrier rasterToBlit = rasterToCopy;
  rasterToBlit.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
  rasterToBlit.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
  rasterToBlit.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
  rasterToBlit.newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
  vkCmdPipelineBarrier(commands_[index], VK_PIPELINE_STAGE_TRANSFER_BIT,
      VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1, &rasterToBlit);
  VkImageMemoryBarrier swapToBlit{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
  swapToBlit.srcAccessMask = 0;
  swapToBlit.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
  swapToBlit.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
  swapToBlit.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
  swapToBlit.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
  swapToBlit.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
  swapToBlit.image = images_[index];
  swapToBlit.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
  vkCmdPipelineBarrier(commands_[index], VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
      VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1, &swapToBlit);
  VkImageBlit blit{};
  blit.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
  blit.srcOffsets[1] = {int32_t(rasterWidth_), int32_t(rasterHeight_), 1};
  blit.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
  blit.dstOffsets[1] = {int32_t(extent_.width), int32_t(extent_.height), 1};
  vkCmdBlitImage(commands_[index], rasterImage_, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
      images_[index], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &blit, VK_FILTER_LINEAR);
  VkImageMemoryBarrier swapToPresent = swapToBlit;
  swapToPresent.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
  swapToPresent.dstAccessMask = 0;
  swapToPresent.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
  swapToPresent.newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
  vkCmdPipelineBarrier(commands_[index], VK_PIPELINE_STAGE_TRANSFER_BIT,
      VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, nullptr, 0, nullptr, 1, &swapToPresent);
  if(vkEndCommandBuffer(commands_[index])!=VK_SUCCESS)return false;
  VkPipelineStageFlags wait=VK_PIPELINE_STAGE_TRANSFER_BIT;VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};si.waitSemaphoreCount=1;si.pWaitSemaphores=&acquired_;si.pWaitDstStageMask=&wait;si.commandBufferCount=1;si.pCommandBuffers=&commands_[index];si.signalSemaphoreCount=1;si.pSignalSemaphores=&rendered_;if(vkQueueSubmit(queue_,1,&si,fence_)!=VK_SUCCESS)return false;VkPresentInfoKHR pi{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};pi.waitSemaphoreCount=1;pi.pWaitSemaphores=&rendered_;pi.swapchainCount=1;pi.pSwapchains=&swapchain_;pi.pImageIndices=&index;VkResult pr=vkQueuePresentKHR(queue_,&pi);lastHash_=hash;return pr==VK_SUCCESS||pr==VK_SUBOPTIMAL_KHR;
}

void VulkanRenderer::destroySwapchain(){if(!device_)return;if(!commands_.empty())vkFreeCommandBuffers(device_,commandPool_,commands_.size(),commands_.data());commands_.clear();images_.clear();if(swapchain_)vkDestroySwapchainKHR(device_,swapchain_,nullptr);swapchain_={};}
void VulkanRenderer::destroySurface(){destroySwapchain();if(surface_)vkDestroySurfaceKHR(instance_,surface_,nullptr);surface_={};if(window_)ANativeWindow_release(window_);window_=nullptr;}
void VulkanRenderer::destroyUploadResources(){if(!device_)return;if(stagingMapped_){vkUnmapMemory(device_,stagingMemory_);stagingMapped_=nullptr;}if(rasterImage_)vkDestroyImage(device_,rasterImage_,nullptr);rasterImage_={};if(rasterMemory_)vkFreeMemory(device_,rasterMemory_,nullptr);rasterMemory_={};if(stagingBuffer_)vkDestroyBuffer(device_,stagingBuffer_,nullptr);stagingBuffer_={};if(stagingMemory_)vkFreeMemory(device_,stagingMemory_,nullptr);stagingMemory_={};}
VulkanRenderer::~VulkanRenderer(){if(device_)vkDeviceWaitIdle(device_);destroySurface();destroyUploadResources();if(device_){if(fence_)vkDestroyFence(device_,fence_,nullptr);if(acquired_)vkDestroySemaphore(device_,acquired_,nullptr);if(rendered_)vkDestroySemaphore(device_,rendered_,nullptr);if(commandPool_)vkDestroyCommandPool(device_,commandPool_,nullptr);vkDestroyDevice(device_,nullptr);}if(instance_)vkDestroyInstance(instance_,nullptr);}

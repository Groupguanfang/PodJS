#include "vulkan_renderer.h"
#include <android/log.h>
#include <vulkan/vulkan_android.h>
#include <algorithm>

#define POD_VK(call) do { if ((call) != VK_SUCCESS) return false; } while (0)

static bool validateDrawList(const uint32_t* w, size_t n) {
  size_t i = 0;
  while (i < n) {
    size_t len = 0;
    switch (w[i]) {
      case 1: len = 4; break; case 2: len = 6; break;
      case 3: if (i + 3 > n) return false; len = 3 + 2 * (w[i + 1] >> 16); break;
      case 4: len = 9; break; case 5: len = 3; break; case 6: len = 1; break;
      case 7: len = 7; break; case 8: len = 12; break;
      case 9: if (i + 8 > n) return false; len = 8 + (w[i + 7] + 3) / 4; break;
      case 10: len = 9; break; default: return false;
    }
    if (!len || len > n - i) return false;
    i += len;
  }
  return i == n;
}

VulkanRenderer::VulkanRenderer(ANativeWindow* window, uint32_t width, uint32_t height) {
  uint32_t loader = 0;
  if (vkEnumerateInstanceVersion(&loader) != VK_SUCCESS || loader < VK_API_VERSION_1_1) {
    __android_log_print(ANDROID_LOG_ERROR, "PodJS", "Vulkan 1.1 unavailable"); return;
  }
  const char* extensions[] = {VK_KHR_SURFACE_EXTENSION_NAME, VK_KHR_ANDROID_SURFACE_EXTENSION_NAME};
  VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO}; app.pApplicationName = "PodJS"; app.apiVersion = VK_API_VERSION_1_1;
  VkInstanceCreateInfo ci{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO}; ci.pApplicationInfo = &app; ci.enabledExtensionCount = 2; ci.ppEnabledExtensionNames = extensions;
  if (vkCreateInstance(&ci, nullptr, &instance_) != VK_SUCCESS || !createDevice()) return;
  replaceWindow(window, width, height);
  valid_ = swapchain_ != VK_NULL_HANDLE;
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
  VkSwapchainCreateInfoKHR ci{VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR}; ci.surface = surface_; ci.minImageCount = imageCount; ci.imageFormat = picked.format; ci.imageColorSpace = picked.colorSpace; ci.imageExtent = extent_; ci.imageArrayLayers = 1; ci.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT; ci.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE; ci.preTransform = caps.currentTransform; ci.compositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR; ci.presentMode = VK_PRESENT_MODE_FIFO_KHR; ci.clipped = VK_TRUE;
  POD_VK(vkCreateSwapchainKHR(device_, &ci, nullptr, &swapchain_));
  POD_VK(vkGetSwapchainImagesKHR(device_, swapchain_, &imageCount, nullptr)); images_.resize(imageCount); POD_VK(vkGetSwapchainImagesKHR(device_, swapchain_, &imageCount, images_.data()));
  VkAttachmentDescription attachment{}; attachment.format = format_; attachment.samples = VK_SAMPLE_COUNT_1_BIT; attachment.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR; attachment.storeOp = VK_ATTACHMENT_STORE_OP_STORE; attachment.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED; attachment.finalLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
  VkAttachmentReference ref{0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL}; VkSubpassDescription sub{}; sub.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS; sub.colorAttachmentCount = 1; sub.pColorAttachments = &ref;
  VkRenderPassCreateInfo rp{VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO}; rp.attachmentCount = 1; rp.pAttachments = &attachment; rp.subpassCount = 1; rp.pSubpasses = &sub; POD_VK(vkCreateRenderPass(device_, &rp, nullptr, &renderPass_));
  views_.resize(imageCount); framebuffers_.resize(imageCount); commands_.resize(imageCount);
  for (uint32_t i=0;i<imageCount;i++) { VkImageViewCreateInfo vi{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO}; vi.image=images_[i]; vi.viewType=VK_IMAGE_VIEW_TYPE_2D; vi.format=format_; vi.subresourceRange.aspectMask=VK_IMAGE_ASPECT_COLOR_BIT; vi.subresourceRange.levelCount=1; vi.subresourceRange.layerCount=1; POD_VK(vkCreateImageView(device_,&vi,nullptr,&views_[i])); VkFramebufferCreateInfo fb{VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO}; fb.renderPass=renderPass_; fb.attachmentCount=1; fb.pAttachments=&views_[i]; fb.width=extent_.width; fb.height=extent_.height; fb.layers=1; POD_VK(vkCreateFramebuffer(device_,&fb,nullptr,&framebuffers_[i])); }
  VkCommandBufferAllocateInfo ai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO}; ai.commandPool=commandPool_; ai.level=VK_COMMAND_BUFFER_LEVEL_PRIMARY; ai.commandBufferCount=imageCount; POD_VK(vkAllocateCommandBuffers(device_,&ai,commands_.data()));
  return true;
}

static VkClearColorValue color(uint32_t abgr) { return {{float(abgr&255)/255.f,float((abgr>>8)&255)/255.f,float((abgr>>16)&255)/255.f,float(abgr>>24)/255.f}}; }
bool VulkanRenderer::submit(const uint32_t* words, size_t count, uint64_t hash) {
  if (!valid_ || hash == lastHash_) return false;
  if (!validateDrawList(words,count)) { __android_log_print(ANDROID_LOG_ERROR,"PodJS","invalid DrawList"); return false; }
  if (vkWaitForFences(device_,1,&fence_,VK_TRUE,UINT64_MAX)!=VK_SUCCESS) return false;
  uint32_t index=0; VkResult ar=vkAcquireNextImageKHR(device_,swapchain_,UINT64_MAX,acquired_,VK_NULL_HANDLE,&index); if(ar!=VK_SUCCESS&&ar!=VK_SUBOPTIMAL_KHR)return false;
  vkResetFences(device_,1,&fence_); vkResetCommandBuffer(commands_[index],0);
  VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO}; if(vkBeginCommandBuffer(commands_[index],&bi)!=VK_SUCCESS)return false;
  VkClearValue black{}; VkRenderPassBeginInfo begin{VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO}; begin.renderPass=renderPass_; begin.framebuffer=framebuffers_[index]; begin.renderArea.extent=extent_; begin.clearValueCount=1; begin.pClearValues=&black; vkCmdBeginRenderPass(commands_[index],&begin,VK_SUBPASS_CONTENTS_INLINE);
  for(size_t i=0;i<count;){uint32_t op=words[i];size_t len=op==1?4:op==2?6:op==3?3+2*(words[i+1]>>16):op==4?9:op==5?3:op==6?1:op==7?7:op==8?12:op==9?8+(words[i+7]+3)/4:9;if(op==1||op==2){uint32_t xy=words[i+1],wh=words[i+2],c=words[i+3];int x=int16_t(xy&0xffff),y=int16_t(xy>>16);uint32_t w=wh&0xffff,h=wh>>16;VkClearAttachment a{};a.aspectMask=VK_IMAGE_ASPECT_COLOR_BIT;a.colorAttachment=0;a.clearValue.color=color(c);VkClearRect r{};r.rect.offset={int32_t(x*extent_.width/240),int32_t(y*extent_.height/240)};r.rect.extent={std::max(1u,w*extent_.width/240),std::max(1u,h*extent_.height/240)};r.layerCount=1;vkCmdClearAttachments(commands_[index],1,&a,1,&r);}i+=len;}
  vkCmdEndRenderPass(commands_[index]); if(vkEndCommandBuffer(commands_[index])!=VK_SUCCESS)return false;
  VkPipelineStageFlags wait=VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};si.waitSemaphoreCount=1;si.pWaitSemaphores=&acquired_;si.pWaitDstStageMask=&wait;si.commandBufferCount=1;si.pCommandBuffers=&commands_[index];si.signalSemaphoreCount=1;si.pSignalSemaphores=&rendered_;if(vkQueueSubmit(queue_,1,&si,fence_)!=VK_SUCCESS)return false;VkPresentInfoKHR pi{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};pi.waitSemaphoreCount=1;pi.pWaitSemaphores=&rendered_;pi.swapchainCount=1;pi.pSwapchains=&swapchain_;pi.pImageIndices=&index;VkResult pr=vkQueuePresentKHR(queue_,&pi);lastHash_=hash;return pr==VK_SUCCESS||pr==VK_SUBOPTIMAL_KHR;
}

void VulkanRenderer::destroySwapchain(){if(!device_)return;if(!commands_.empty())vkFreeCommandBuffers(device_,commandPool_,commands_.size(),commands_.data());commands_.clear();for(auto f:framebuffers_)vkDestroyFramebuffer(device_,f,nullptr);framebuffers_.clear();for(auto v:views_)vkDestroyImageView(device_,v,nullptr);views_.clear();images_.clear();if(renderPass_)vkDestroyRenderPass(device_,renderPass_,nullptr);renderPass_={};if(swapchain_)vkDestroySwapchainKHR(device_,swapchain_,nullptr);swapchain_={};}
void VulkanRenderer::destroySurface(){destroySwapchain();if(surface_)vkDestroySurfaceKHR(instance_,surface_,nullptr);surface_={};if(window_)ANativeWindow_release(window_);window_=nullptr;}
VulkanRenderer::~VulkanRenderer(){if(device_)vkDeviceWaitIdle(device_);destroySurface();if(device_){if(fence_)vkDestroyFence(device_,fence_,nullptr);if(acquired_)vkDestroySemaphore(device_,acquired_,nullptr);if(rendered_)vkDestroySemaphore(device_,rendered_,nullptr);if(commandPool_)vkDestroyCommandPool(device_,commandPool_,nullptr);vkDestroyDevice(device_,nullptr);}if(instance_)vkDestroyInstance(instance_,nullptr);}

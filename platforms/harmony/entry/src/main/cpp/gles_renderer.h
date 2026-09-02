#pragma once

#include <EGL/egl.h>
#include <cstddef>
#include <cstdint>

class GlesRenderer {
public:
  static constexpr uint32_t kRasterScale = 2;

  GlesRenderer() = default;
  ~GlesRenderer();
  GlesRenderer(const GlesRenderer&) = delete;

  bool attach(void* nativeWindow, uint32_t width, uint32_t height,
              uint32_t rasterWidth, uint32_t rasterHeight);
  void detach();
  bool submitRgba(const uint8_t* pixels, size_t byteLength, uint64_t contentHash);
  bool valid() const { return display_ != EGL_NO_DISPLAY && surface_ != EGL_NO_SURFACE; }
  size_t rasterBytes() const { return size_t{rasterWidth_} * rasterHeight_ * 4; }

private:
  EGLDisplay display_ = EGL_NO_DISPLAY;
  EGLContext context_ = EGL_NO_CONTEXT;
  EGLSurface surface_ = EGL_NO_SURFACE;
  uint32_t framebuffer_ = 0;
  uint32_t colorTexture_ = 0;
  uint32_t width_ = 0;
  uint32_t height_ = 0;
  uint32_t rasterWidth_ = 0;
  uint32_t rasterHeight_ = 0;
  uint64_t hash_ = 0;
};

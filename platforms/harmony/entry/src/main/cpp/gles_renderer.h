#pragma once

#include <EGL/egl.h>
#include <cstddef>
#include <cstdint>

class GlesRenderer {
public:
  GlesRenderer() = default;
  ~GlesRenderer();
  GlesRenderer(const GlesRenderer&) = delete;

  bool attach(void* nativeWindow, uint32_t width, uint32_t height);
  void detach();
  bool validate(const uint32_t* words, size_t count) const;
  bool submit(const uint32_t* words, size_t count, uint64_t contentHash);
  bool valid() const { return display_ != EGL_NO_DISPLAY && surface_ != EGL_NO_SURFACE; }

private:
  EGLDisplay display_ = EGL_NO_DISPLAY;
  EGLContext context_ = EGL_NO_CONTEXT;
  EGLSurface surface_ = EGL_NO_SURFACE;
  uint32_t width_ = 0;
  uint32_t height_ = 0;
  uint64_t hash_ = 0;
};

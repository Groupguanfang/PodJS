#include "gles_renderer.h"

#include <GLES3/gl3.h>
#include <algorithm>

namespace {
size_t commandLength(const uint32_t* words, size_t count, size_t index) {
  if (index >= count) return 0;
  switch (words[index]) {
    case 1: return 4;
    case 2: return 6;
    case 3: return index + 3 <= count ? 3 + 2 * (words[index + 1] >> 16) : 0;
    case 4: return 9;
    case 5: return 3;
    case 6: return 1;
    case 7: return 7;
    case 8: return 12;
    case 9: return index + 8 <= count ? 8 + (words[index + 7] + 3) / 4 : 0;
    case 10: return 9;
    default: return 0;
  }
}

void clearColor(uint32_t abgr) {
  glClearColor(float(abgr & 255) / 255.0f,
               float((abgr >> 8) & 255) / 255.0f,
               float((abgr >> 16) & 255) / 255.0f,
               float(abgr >> 24) / 255.0f);
}
}

GlesRenderer::~GlesRenderer() { detach(); }

bool GlesRenderer::attach(void* nativeWindow, uint32_t width, uint32_t height) {
  detach();
  if (!nativeWindow || width == 0 || height == 0) return false;
  display_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
  if (display_ == EGL_NO_DISPLAY || !eglInitialize(display_, nullptr, nullptr)) {
    detach();
    return false;
  }
  const EGLint configAttributes[] = {
      EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
      EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
      EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
      EGL_NONE,
  };
  EGLConfig config = nullptr;
  EGLint configCount = 0;
  if (!eglChooseConfig(display_, configAttributes, &config, 1, &configCount) || configCount != 1) {
    detach();
    return false;
  }
  const EGLint contextAttributes[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
  context_ = eglCreateContext(display_, config, EGL_NO_CONTEXT, contextAttributes);
  surface_ = eglCreateWindowSurface(display_, config,
                                    reinterpret_cast<EGLNativeWindowType>(nativeWindow), nullptr);
  if (context_ == EGL_NO_CONTEXT || surface_ == EGL_NO_SURFACE ||
      !eglMakeCurrent(display_, surface_, surface_, context_)) {
    detach();
    return false;
  }
  width_ = width;
  height_ = height;
  hash_ = 0;
  glViewport(0, 0, static_cast<GLsizei>(width_), static_cast<GLsizei>(height_));
  glDisable(GL_DEPTH_TEST);
  glDisable(GL_BLEND);
  return glGetError() == GL_NO_ERROR;
}

void GlesRenderer::detach() {
  if (display_ != EGL_NO_DISPLAY) {
    eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    if (surface_ != EGL_NO_SURFACE) eglDestroySurface(display_, surface_);
    if (context_ != EGL_NO_CONTEXT) eglDestroyContext(display_, context_);
    eglTerminate(display_);
  }
  display_ = EGL_NO_DISPLAY;
  context_ = EGL_NO_CONTEXT;
  surface_ = EGL_NO_SURFACE;
  width_ = 0;
  height_ = 0;
  hash_ = 0;
}

bool GlesRenderer::validate(const uint32_t* words, size_t count) const {
  if (!words && count != 0) return false;
  size_t index = 0;
  while (index < count) {
    const size_t length = commandLength(words, count, index);
    if (length == 0 || length > count - index) return false;
    index += length;
  }
  return index == count;
}

bool GlesRenderer::submit(const uint32_t* words, size_t count, uint64_t contentHash) {
  if (!valid() || contentHash == hash_ || !validate(words, count)) return false;
  if (!eglMakeCurrent(display_, surface_, surface_, context_)) return false;
  glDisable(GL_SCISSOR_TEST);
  clearColor(0xff000000);
  glClear(GL_COLOR_BUFFER_BIT);
  glEnable(GL_SCISSOR_TEST);
  for (size_t index = 0; index < count;) {
    const uint32_t op = words[index];
    const size_t length = commandLength(words, count, index);
    if (op == 1 || op == 2) {
      const uint32_t xy = words[index + 1];
      const uint32_t wh = words[index + 2];
      const int32_t x = static_cast<int16_t>(xy & 0xffff);
      const int32_t y = static_cast<int16_t>(xy >> 16);
      const uint32_t w = wh & 0xffff;
      const uint32_t h = wh >> 16;
      const int32_t left = x * static_cast<int32_t>(width_) / 240;
      const int32_t top = y * static_cast<int32_t>(height_) / 240;
      const int32_t pixelWidth = std::max(1, static_cast<int32_t>(w * width_ / 240));
      const int32_t pixelHeight = std::max(1, static_cast<int32_t>(h * height_ / 240));
      glScissor(left, static_cast<int32_t>(height_) - top - pixelHeight, pixelWidth, pixelHeight);
      clearColor(words[index + 3]);
      glClear(GL_COLOR_BUFFER_BIT);
    }
    index += length;
  }
  glDisable(GL_SCISSOR_TEST);
  if (glGetError() != GL_NO_ERROR || !eglSwapBuffers(display_, surface_)) return false;
  hash_ = contentHash;
  return true;
}

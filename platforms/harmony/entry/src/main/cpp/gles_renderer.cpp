#include "gles_renderer.h"

#include <GLES3/gl3.h>

GlesRenderer::~GlesRenderer() { detach(); }

bool GlesRenderer::attach(void* nativeWindow, uint32_t width, uint32_t height,
                          uint32_t rasterWidth, uint32_t rasterHeight) {
  detach();
  if (!nativeWindow || width == 0 || height == 0 || rasterWidth == 0 || rasterHeight == 0) return false;
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
  rasterWidth_ = rasterWidth;
  rasterHeight_ = rasterHeight;
  hash_ = 0;
  glViewport(0, 0, static_cast<GLsizei>(width_), static_cast<GLsizei>(height_));
  glDisable(GL_DEPTH_TEST);
  glDisable(GL_BLEND);
  glGenTextures(1, &colorTexture_);
  glBindTexture(GL_TEXTURE_2D, colorTexture_);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
  glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, static_cast<GLsizei>(rasterWidth_),
               static_cast<GLsizei>(rasterHeight_), 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
  glGenFramebuffers(1, &framebuffer_);
  glBindFramebuffer(GL_FRAMEBUFFER, framebuffer_);
  glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                         colorTexture_, 0);
  if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
    detach();
    return false;
  }
  glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
  glClear(GL_COLOR_BUFFER_BIT);
  glBindFramebuffer(GL_FRAMEBUFFER, 0);
  return glGetError() == GL_NO_ERROR;
}

void GlesRenderer::detach() {
  if (display_ != EGL_NO_DISPLAY) {
    if (context_ != EGL_NO_CONTEXT && surface_ != EGL_NO_SURFACE &&
        eglMakeCurrent(display_, surface_, surface_, context_)) {
      if (framebuffer_ != 0) glDeleteFramebuffers(1, &framebuffer_);
      if (colorTexture_ != 0) glDeleteTextures(1, &colorTexture_);
    }
    framebuffer_ = 0;
    colorTexture_ = 0;
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
  rasterWidth_ = 0;
  rasterHeight_ = 0;
  hash_ = 0;
}

bool GlesRenderer::submitRgba(const uint8_t* pixels, size_t byteLength,
                              uint64_t contentHash) {
  if (!valid() || !pixels || byteLength != rasterBytes() || contentHash == hash_) return false;
  if (!eglMakeCurrent(display_, surface_, surface_, context_)) return false;
  glBindTexture(GL_TEXTURE_2D, colorTexture_);
  glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
  glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, static_cast<GLsizei>(rasterWidth_),
                  static_cast<GLsizei>(rasterHeight_), GL_RGBA, GL_UNSIGNED_BYTE, pixels);
  glBindFramebuffer(GL_READ_FRAMEBUFFER, framebuffer_);
  glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
  // The runtime framebuffer is top-down; GLES framebuffer coordinates are
  // bottom-up, so reverse the source Y range during the presentation blit.
  glBlitFramebuffer(0, static_cast<GLint>(rasterHeight_),
                    static_cast<GLint>(rasterWidth_), 0,
                    0, 0, static_cast<GLint>(width_), static_cast<GLint>(height_),
                    GL_COLOR_BUFFER_BIT, GL_LINEAR);
  glBindFramebuffer(GL_FRAMEBUFFER, 0);
  if (glGetError() != GL_NO_ERROR || !eglSwapBuffers(display_, surface_)) return false;
  hash_ = contentHash;
  return true;
}

#ifndef PODJS_RUNTIME_H
#define PODJS_RUNTIME_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define PODJS_RUNTIME_ABI_VERSION 1
#define PODJS_DEFAULT_LOGICAL_WIDTH 240
#define PODJS_DEFAULT_LOGICAL_HEIGHT 240
#define PODJS_MAX_TOUCHES 8

typedef struct PodRuntime PodRuntime;

typedef enum PodLifecycleState {
  POD_LIFECYCLE_ACTIVE = 0,
  POD_LIFECYCLE_INACTIVE = 1,
  POD_LIFECYCLE_BACKGROUND = 2,
} PodLifecycleState;

typedef enum PodDisplayShape {
  POD_DISPLAY_ROUND = 0,
  POD_DISPLAY_RECT = 1,
} PodDisplayShape;

typedef struct PodRuntimeConfig {
  uint32_t struct_size;
  const char *target_id;
  uint32_t host_abi;
  uint32_t raster_density;
  uint32_t physical_width;
  uint32_t physical_height;
  float display_density;
  uint32_t display_shape;
  float safe_top;
  float safe_right;
  float safe_bottom;
  float safe_left;
  const char *data_dir;
  const char *capabilities_json;
} PodRuntimeConfig;

typedef struct PodTouch {
  uint32_t id;
  float x;
  float y;
} PodTouch;

typedef struct PodInputFrame {
  uint32_t struct_size;
  uint32_t buttons;
  uint32_t analog;
  const PodTouch *touches;
  uint32_t touch_count;
  int32_t rotary_primary_millidegrees;
  int32_t rotary_secondary_millidegrees;
} PodInputFrame;

typedef struct PodDrawList {
  const uint32_t *words;
  size_t word_count;
  uint64_t content_hash;
  uint64_t frame_number;
  int32_t changed;
} PodDrawList;

typedef struct PodTextureView {
  int32_t handle;
  uint64_t revision;
  const uint8_t *pixels;
  size_t byte_length;
  uint32_t width;
  uint32_t height;
  uint32_t pixel_format;
  const uint8_t *palette;
  size_t palette_length;
  int32_t linear;
} PodTextureView;

typedef struct PodFontView {
  uint32_t slot;
  uint32_t cell_width;
  uint32_t cell_height;
  uint32_t baseline;
  uint32_t line_height;
  uint32_t raster_density;
  uint32_t glyph_count;
  const uint8_t *bitmap;
  size_t bitmap_length;
} PodFontView;

uint32_t pod_runtime_abi_version(void);
const char *pod_runtime_last_error(void);
PodRuntime *pod_runtime_create(const PodRuntimeConfig *config);
uint32_t pod_runtime_logical_width(const PodRuntime *runtime);
uint32_t pod_runtime_logical_height(const PodRuntime *runtime);
int32_t pod_runtime_load_pak(PodRuntime *runtime, const uint8_t *bytes, size_t length);
int32_t pod_runtime_validate_package(PodRuntime *runtime, const char *manifest_json);
int32_t pod_runtime_eval_bundle(PodRuntime *runtime, const uint8_t *source, size_t length,
                                const char *label);
int32_t pod_runtime_set_lifecycle(PodRuntime *runtime, uint32_t state);
int32_t pod_runtime_set_theme(PodRuntime *runtime, const char *theme);
int32_t pod_runtime_post_event(PodRuntime *runtime, const char *json_object);
int32_t pod_runtime_frame(PodRuntime *runtime, const PodInputFrame *input);
int32_t pod_runtime_snapshot(PodRuntime *runtime, PodDrawList *out);
/* Rasterize the current DrawList to tightly packed RGBA8. `scale` is 1..4;
 * length must equal logical_width * scale * logical_height * scale * 4. */
int32_t pod_runtime_render_rgba(PodRuntime *runtime, uint32_t scale,
                                uint8_t *pixels, size_t length);
/* Incremental equivalent for a persistent framebuffer. The first call draws a
 * complete frame; later calls repaint only damage regions when possible. */
int32_t pod_runtime_render_rgba_incremental(PodRuntime *runtime, uint32_t scale,
                                            uint8_t *pixels, size_t length);
int32_t pod_runtime_texture(PodRuntime *runtime, uint32_t slot, PodTextureView *out);
int32_t pod_runtime_font(PodRuntime *runtime, uint32_t slot, PodFontView *out);

/* Native host effects and networking. Returned strings are owned by the
 * runtime and remain valid until the next poll of the same kind. */
const char *pod_runtime_poll_effect(PodRuntime *runtime);
const char *pod_runtime_poll_net_command(PodRuntime *runtime);
int32_t pod_runtime_complete_http(PodRuntime *runtime, int32_t handle, uint32_t status,
                                  const char *url, const char *headers_json,
                                  const uint8_t *body, size_t body_length);
int32_t pod_runtime_fail_http(PodRuntime *runtime, int32_t handle,
                              const char *code, const char *message);

const char *pod_runtime_receipt(PodRuntime *runtime);
void pod_runtime_destroy(PodRuntime *runtime);

#ifdef __cplusplus
}
#endif

#endif

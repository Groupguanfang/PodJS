#pragma once

#include <cstdint>
#include "gpu_draw_list.h"

namespace podjs_gpu {
struct alignas(16) RoundedClipData {
  float rect[4];
  float radii[4];
};
static_assert(sizeof(RoundedClipData) == 32, "rounded clip SSBO ABI mismatch");
struct alignas(16) PrimitivePush {
  int32_t bounds[4]; int32_t xy01[4]; int32_t xy2Mode[4]; uint32_t colors[4];
  float uv01[4]; float uv2Extra[4]; int32_t texInfo[4]; float screen[4];
};
static_assert(sizeof(PrimitivePush) == 128, "primitive push ABI must remain 128 bytes");

PrimitivePush makePrimitivePush(const GpuPrimitive& primitive,
                                uint32_t rasterWidth, uint32_t rasterHeight,
                                bool linear);
RoundedClipData makeRoundedClipData(const GpuRoundedClip& clip);
}

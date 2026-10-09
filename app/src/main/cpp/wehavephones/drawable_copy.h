#ifndef WEHAVEPHONES_DRAWABLE_COPY_H
#define WEHAVEPHONES_DRAWABLE_COPY_H

#include <stddef.h>
#include <stdint.h>
#include <string.h>

static void drawable_copy_area(uint8_t *src, uint8_t *dst, int src_x, int src_y,
                               int dst_x, int dst_y, int width, int height,
                               int src_stride, int dst_stride) {
    if (width <= 0 || height <= 0) return;
    src += (src_x + src_y * src_stride) * 4;
    dst += (dst_x + dst_y * dst_stride) * 4;
    size_t row_bytes = (size_t)width * 4;
    // Snapshot semantics for overlapping copies within the same image.
    if (src_stride == dst_stride && (uintptr_t)dst > (uintptr_t)src &&
        (uintptr_t)dst < (uintptr_t)src + (size_t)height * src_stride * 4) {
        for (int y = height - 1; y >= 0; y--)
            memmove(dst + (size_t)y * dst_stride * 4, src + (size_t)y * src_stride * 4, row_bytes);
    }
    else {
        for (int y = 0; y < height; y++)
            memmove(dst + (size_t)y * dst_stride * 4, src + (size_t)y * src_stride * 4, row_bytes);
    }
}

#endif

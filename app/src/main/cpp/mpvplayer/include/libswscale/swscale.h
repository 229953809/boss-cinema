#pragma once

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define SWS_BICUBIC 4

enum AVPixelFormat {
    AV_PIX_FMT_BGRA = 28,
    AV_PIX_FMT_RGB32 = AV_PIX_FMT_BGRA,
    AV_PIX_FMT_BGR0 = 121,
};

struct SwsContext;

struct SwsContext *sws_getContext(
        int srcW,
        int srcH,
        enum AVPixelFormat srcFormat,
        int dstW,
        int dstH,
        enum AVPixelFormat dstFormat,
        int flags,
        void *srcFilter,
        void *dstFilter,
        const double *param);

int sws_scale(
        struct SwsContext *c,
        const uint8_t *const srcSlice[],
        const int srcStride[],
        int srcSliceY,
        int srcSliceH,
        uint8_t *const dst[],
        const int dstStride[]);

void sws_freeContext(struct SwsContext *swsContext);

#ifdef __cplusplus
}
#endif

// Film grain on the finished capture.
//
// Camera 7.006 no longer has grain anywhere in its still pipeline. Every dex is free of
// filter_grain_* strings, the APS filter node (libAlgoProcess.so) does not reference the parameter
// either, and the one surviving reader in libAlgoInterface - setMesheFilterParam - is dead code on
// this firmware: a probe hook on its entry counted zero calls across a full master-mode capture.
// What the camera does still ship is the preview half of the Meishe grain, which is why the slider
// moves, the preview looks right and the shot comes back clean.
//
// The still pipeline on this build was then traced with the module's own hooks: libmsnativefilter
// (MsRenderHDRHostBuffer and every sibling) is never called for a master shot at all - the whole
// soft light group is dead here - while HDRTransform and BasicTone report every frame. So the grain
// goes where the capture is: HDRTransformImpl::processMasterV2 writes the NV21 SDR base that becomes
// the JPEG (see hdrt_dump.cpp for the same entry and the buffer geometry), and that buffer is a
// dma-buf this process owns. The grain is drawn into its luma plane once the transform returns,
// before the encoder reads it back.
//
// The Java half keeps writing the request metadata (the stock way: an aps_algo_filter node with the
// grain keys), and on this build that node runs with the grain silently ignored - the log shows the
// node inserted and the request accepted, the photo comes back clean.
//
// For reference, the X8U module built for camera 7.013.51 renders grain from exactly those request
// parameters, and it bind-mounts its own libmsnativefilter.so to do it. Neither half transfers here:
// 7.006's dex has no grain field at all, and its libmsnativefilter carries a different build.
//
// The look is film grain rather than sensor noise: a lattice whose spacing follows the "size"
// slider gives the clumping, a per-pixel hash keeps the micro texture, and both are weighted the way
// the stock grain weights its bands (near flat, slightly less at the highlights).
#include <android/log.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <linux/dma-buf.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <unistd.h>

#include <cerrno>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>

#include "native_hooks.h"

namespace {

constexpr char kTag[] = "MasterGrainOverlay";
// Written by the Java half whenever the master slider moves: "<amount> <size>", both 0..100.
constexpr char kStatePath[] = "/data/user/0/com.oplus.camera/files/master_grain";
// How far a full-strength slider may push the luma, as a fraction of the range. Film grain at a
// pushed ISO is a good deal less than this; the slider is the one that decides.
constexpr float kMaxAmplitude = 0.18f;
// The clumps carry most of the weight, the per-pixel hash keeps them from reading as smooth blobs.
constexpr float kClumpWeight = 0.62f;

struct Settings {
    float amount = 0;
    float size = 0.5f;
};

Settings readSettings() {
    Settings s;
    FILE *f = std::fopen(kStatePath, "r");
    if (f == nullptr) return s;
    int amount = 0, size = 50;
    const int read = std::fscanf(f, "%d %d", &amount, &size);
    std::fclose(f);
    if (read != 2) return s;
    if (amount < 0) amount = 0;
    if (amount > 100) amount = 100;
    if (size < 0) size = 0;
    if (size > 100) size = 100;
    s.amount = amount / 100.0f;
    s.size = size / 100.0f;
    return s;
}

inline uint32_t mix(uint32_t x) {
    x ^= x >> 16;
    x *= 0x7feb352dU;
    x ^= x >> 15;
    x *= 0x846ca68bU;
    x ^= x >> 16;
    return x;
}

// Lattice sample in -1..1. The two coordinates are hashed separately so a row keeps its own seed
// and the pattern never lines up vertically.
inline float node(int gx, int gy) {
    const uint32_t h = mix(uint32_t(gx) * 0x9e3779b1U ^ mix(uint32_t(gy) * 0x85ebca6bU ^ 0x51ed270bU));
    return float(h >> 16) * (2.0f / 65535.0f) - 1.0f;
}

// One pixel of noise in -1..1: the interpolated clump plus its own grain of texture.
inline float noiseAt(int x, int y, int cell) {
    const int gx = x / cell, gy = y / cell;
    const float fx = float(x - gx * cell) / float(cell);
    const float fy = float(y - gy * cell) / float(cell);
    const float n00 = node(gx, gy), n10 = node(gx + 1, gy);
    const float n01 = node(gx, gy + 1), n11 = node(gx + 1, gy + 1);
    const float top = n00 + (n10 - n00) * fx;
    const float bottom = n01 + (n11 - n01) * fx;
    const float clump = top + (bottom - top) * fy;
    const float pixel = float(mix(uint32_t(x) * 0x27d4eb2fU ^ mix(uint32_t(y) * 0x165667b1U)) >> 16)
                            * (2.0f / 65535.0f) - 1.0f;
    return clump * kClumpWeight + pixel * (1.0f - kClumpWeight);
}

// The stock grain's own band weights are near flat (shadows 0.89, midtones 0.90, highlights 0.85 of
// the requested amount), so this only leans the same way: full in the midtones, held back at both
// ends without letting the shadows go clean.
inline float weightAt(float t) { return 0.55f + 0.45f * (4.0f * t * (1.0f - t)); }

template <typename T>
void drawLuma(uint8_t *base, size_t stride, int w, int h, int cell, float amplitude, float maxValue) {
    for (int y = 0; y < h; y++) {
        auto *row = reinterpret_cast<T *>(base + size_t(y) * stride);
        for (int x = 0; x < w; x++) {
            const float t = float(row[x]) / maxValue;
            const float value = float(row[x]) + noiseAt(x, y, cell) * amplitude * weightAt(t) * maxValue;
            row[x] = T(value < 0 ? 0 : (value > maxValue ? maxValue : value));
        }
    }
}

int cellOf(const Settings &settings) {
    // A cell of one pixel is the finest grain the sensor would ever show; 100 on the slider lands
    // near seven pixels, which reads as coarse 800-speed grain.
    return 1 + int(std::lround(settings.size * 6.0f));
}

// ---- HDRTransform: the buffer that becomes the JPEG -------------------------------------------
//
// processMasterV2(this, InputImage *in, Image *out, void *param, void *bufs): out is the NV21 SDR
// base, out+0x20 width, +0x24 height, +0x28 row stride in bytes, +0x2c slice height; the output
// buffer's native handle sits at bufs+0x28 and its first fd is the whole image's dma-buf
// (hdrt_dump.cpp relies on the same layout).
struct NativeHandle {
    int version, numFds, numInts;
    int data[0];
};

template <typename T>
T fieldAt(const void *base, size_t offset) {
    T v;
    std::memcpy(&v, static_cast<const uint8_t *>(base) + offset, sizeof v);
    return v;
}

bool grainOnCaptureBuffer(void *out, void *bufs, const char *path) {
    const Settings settings = readSettings();
    if (settings.amount <= 0) return false;
    if (!out || !bufs) return false;
    const int w = fieldAt<int>(out, 0x20), h = fieldAt<int>(out, 0x24);
    const int stride = fieldAt<int>(out, 0x28), slice = fieldAt<int>(out, 0x2c);
    const int rows = slice > 0 ? slice : h;
    if (w < 64 || rows < 64 || w > 8192 || rows > 8192) return false;
    if (stride < w || stride > w * 4) return false;

    // processMaster hands the frame to process_photo on some builds (and the output buffer comes from
    // a pool), so the same pointer twice in the same moment is one shot, not two.
    static std::atomic<const void *> lastOut{nullptr};
    static std::atomic<int64_t> lastMs{0};
    const int64_t now = std::chrono::duration_cast<std::chrono::milliseconds>(
                            std::chrono::steady_clock::now().time_since_epoch()).count();
    if (lastOut.load() == out && now - lastMs.load() < 400) return false;
    lastOut.store(out);
    lastMs.store(now);

    const NativeHandle *handle = fieldAt<const NativeHandle *>(bufs, 0x28);
    if (!handle || handle->version != int(sizeof(NativeHandle)) || handle->numFds < 1 || handle->numFds > 8) {
        return false;
    }
    const int fd = handle->data[0];
    const off_t size = lseek(fd, 0, SEEK_END);
    const size_t lumaBytes = size_t(stride) * size_t(rows);
    if (size <= 0 || size_t(size) < lumaBytes) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "capture buffer %d: %lld bytes for %zu luma (%dx%d stride %d)",
                            fd, (long long)size, lumaBytes, w, rows, stride);
        return false;
    }
    void *base = mmap(nullptr, size_t(size), PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (base == MAP_FAILED) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "capture buffer %d not writable (%d)", fd, errno);
        return false;
    }
    dma_buf_sync sync{DMA_BUF_SYNC_START | DMA_BUF_SYNC_RW};
    ioctl(fd, DMA_BUF_IOCTL_SYNC, &sync);
    const auto start = std::chrono::steady_clock::now();
    drawLuma<uint8_t>(static_cast<uint8_t *>(base), size_t(stride), w, rows, cellOf(settings),
                      settings.amount * kMaxAmplitude, 255.0f);
    sync.flags = DMA_BUF_SYNC_END | DMA_BUF_SYNC_RW;
    ioctl(fd, DMA_BUF_IOCTL_SYNC, &sync);
    munmap(base, size_t(size));
    __android_log_print(ANDROID_LOG_INFO, kTag, "grain %dx%d cell=%d amount=%d size=%d %lldms via %s", w, rows,
                        cellOf(settings), int(settings.amount * 100 + 0.5f), int(settings.size * 100 + 0.5f),
                        (long long)std::chrono::duration_cast<std::chrono::milliseconds>(
                            std::chrono::steady_clock::now() - start).count(),
                        path ? path : "?");
    return true;
}

using ProcessMaster = int (*)(void *, void *, void *, void *, void *);

template <int Slot>
struct MasterHook {
    static ProcessMaster orig;
    static const char *path;
    static int call(void *self, void *in, void *out, void *param, void *bufs) {
        const int ret = orig(self, in, out, param, bufs);
        static std::atomic<int> count{0};
        if (count.fetch_add(1) < 3) {
            __android_log_print(ANDROID_LOG_INFO, kTag, "%s entry %dx%d ret=%d", path,
                                out ? fieldAt<int>(out, 0x20) : 0, out ? fieldAt<int>(out, 0x24) : 0, ret);
        }
        // 0 is the lib's success: a failed transform tells nothing about the buffer.
        if (ret == 0) grainOnCaptureBuffer(out, bufs, path);
        return ret;
    }
};
template <int Slot> ProcessMaster MasterHook<Slot>::orig = nullptr;
template <int Slot> const char *MasterHook<Slot>::path = nullptr;

template <int Slot>
void hookMaster(HookFunction hook, void *handle, const char *symbol, const char *path) {
    MasterHook<Slot>::path = path;
    hookExport(hook, handle, symbol, reinterpret_cast<void *>(&MasterHook<Slot>::call),
               reinterpret_cast<void **>(&MasterHook<Slot>::orig), kTag);
}

// Build 7.006 dropped processMaster{V2,Quick}: the still frame is written by processMaster, or by
// process_photo when the master path delegates to it. The camera, quick and portrait libraries are
// three copies of the same image, each its own mapping, so every one that loads is hooked.
constexpr char kMaster[] = "_ZN16HDRTransformImpl13processMasterEP10InputImageP5ImagePvS4_";
constexpr char kPhoto[] = "_ZN16HDRTransformImpl13process_photoEP10InputImageP5ImagePvS4_";

}  // namespace

// Called from the capture hook in soft_light.cpp once the camera has rendered the frame. On this
// build nothing calls it (libmsnativefilter is not part of the still pipeline), but the frame pass
// costs nothing when the slider is down and keeps the device-independent path intact.
bool grainApplyFrame(const MonoPanFrame &frame) {
    if (frame.luma == nullptr) return false;
    const Settings settings = readSettings();
    if (settings.amount <= 0) return false;
    const int w = frame.width, h = frame.height;
    if (w < 64 || h < 64 || w > 8192 || h > 8192) return false;
    if (frame.bits != 8 && frame.bits != 16) return false;
    if (frame.lumaStride < size_t(w) * (frame.bits == 8 ? 1u : 2u)) return false;
    const int cell = cellOf(settings);
    const float amplitude = settings.amount * kMaxAmplitude;
    if (frame.bits == 8) {
        drawLuma<uint8_t>(static_cast<uint8_t *>(frame.luma), frame.lumaStride, w, h, cell, amplitude, 255.0f);
    } else {
        drawLuma<uint16_t>(static_cast<uint8_t *>(frame.luma), frame.lumaStride, w, h, cell, amplitude,
                           frame.fullRange ? 1023.0f : 1023.0f);
    }
    __android_log_print(ANDROID_LOG_INFO, kTag, "grain %ux%u %d bit amount=%d size=%d via %s", w, h, frame.bits,
                        int(settings.amount * 100 + 0.5f), int(settings.size * 100 + 0.5f),
                        frame.route ? frame.route : "?");
    return true;
}

// HDRTransform carries the finished capture on this build, so the grain group lives there.
void installGrainOverlay(HookFunction hook, const char *name, void *handle) {
    if (!name || !std::strstr(name, "HDRTransform")) return;
    static void *seen[4] = {};
    static int loaded = 0;
    for (int i = 0; i < loaded; i++) {
        if (seen[i] == handle) return;
    }
    if (loaded >= 4) return;
    const int slot = loaded;
    seen[loaded++] = handle;
    // Two slots per library: each mapping needs its own trampoline, since the originals live at
    // different addresses even though the three images are identical.
    switch (slot) {
        case 0: hookMaster<0>(hook, handle, kMaster, "master"); hookMaster<1>(hook, handle, kPhoto, "photo"); break;
        case 1: hookMaster<2>(hook, handle, kMaster, "master"); hookMaster<3>(hook, handle, kPhoto, "photo"); break;
        case 2: hookMaster<4>(hook, handle, kMaster, "master"); hookMaster<5>(hook, handle, kPhoto, "photo"); break;
        default: hookMaster<6>(hook, handle, kMaster, "master"); hookMaster<7>(hook, handle, kPhoto, "photo"); break;
    }
}

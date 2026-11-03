// Reads pixels out of the camera's real-scene composition preview buffer.
//
// That buffer is HAL_PIXEL_FORMAT_RGB_888 with usage 0x203 (CPU_READ_OFTEN | GPU_FRAMEBUFFER),
// which is precisely the combination Bitmap.wrapHardwareBuffer refuses:
//
//   IllegalArgumentException: usage flags must contain USAGE_GPU_SAMPLED_IMAGE
//
// It cannot be sampled as a texture either (no GPU_SAMPLED_IMAGE), so AHardwareBuffer_lock is the
// only way in -- and it is allowed, the producer hands out CPU_READ_OFTEN.
//
// Everything is resolved with dlsym: the module builds against minSdk 28, and this platform's
// libnativewindow exports the API 26 shape (describe / lock / unlock). getDesc and lockAndGetInfo
// are newer and simply not here, which is why the first version came back empty-handed.
//
// The function is spelled with its JNI-mangled name so no RegisterNatives/JNI_OnLoad plumbing is
// needed: ModuleEntry already loads this library from the module's own classloader, and ART
// resolves native methods of that classloader's classes against it by name.

#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <cstdint>

namespace {

constexpr char kTag[] = "CompFollow";

using DescribeFn = void (*)(const AHardwareBuffer*, AHardwareBuffer_Desc*);
using LockFn = int (*)(AHardwareBuffer*, uint64_t, int32_t, const ARect*, void**);
using UnlockFn = int (*)(AHardwareBuffer*, int32_t*);

// AHardwareBuffer_* is not in libandroid.so on this platform; the NDK stub spreads it over
// libnativewindow.so as well, so both are probed before giving up.
const char* const kCandidates[] = {"libnativewindow.so", "libandroid.so"};

void* symbol(const char* name) {
    constexpr int kCount = int(sizeof(kCandidates) / sizeof(kCandidates[0]));
    static void* handles[kCount] = {};
    static bool opened[kCount] = {};
    for (int i = 0; i < kCount; i++) {
        if (!opened[i]) {
            handles[i] = dlopen(kCandidates[i], RTLD_NOW);
            opened[i] = true;
            if (!handles[i]) {
                __android_log_print(ANDROID_LOG_INFO, kTag, "dlopen %s: %s", kCandidates[i],
                                    dlerror());
            }
        }
        if (!handles[i]) continue;
        if (void* address = dlsym(handles[i], name)) return address;
    }
    __android_log_print(ANDROID_LOG_INFO, kTag, "missing symbol %s", name);
    return nullptr;
}

DescribeFn describeFn() {
    static DescribeFn fn = reinterpret_cast<DescribeFn>(symbol("AHardwareBuffer_describe"));
    return fn;
}

LockFn lockFn() {
    static LockFn fn = reinterpret_cast<LockFn>(symbol("AHardwareBuffer_lock"));
    return fn;
}

UnlockFn unlockFn() {
    static UnlockFn fn = reinterpret_cast<UnlockFn>(symbol("AHardwareBuffer_unlock"));
    return fn;
}

// Note: AHardwareBuffer_fromHardwareBuffer below deliberately has no matching release. The NDK
// documents it as NOT acquiring a reference ("This method does not acquire any additional reference
// ... use AHardwareBuffer_acquire() to acquire an additional reference"), so releasing here would
// drop the reference the camera's own Java HardwareBuffer still holds and free a buffer in use.

int bytesPerPixel(uint32_t format) {
    switch (format) {
        case AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM:
        case AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM:
            return 4;
        case 3:  // HAL_PIXEL_FORMAT_RGB_888, what this camera hands out
            return 3;
        case AHARDWAREBUFFER_FORMAT_R5G6B5_UNORM:
            return 2;
        default:
            return 0;
    }
}

}  // namespace

extern "C" __attribute__((visibility("default"))) jint
Java_local_jiege_hook_aicomposition_NativeFrames_readPixels(
    JNIEnv* env, jclass, jobject bufferObject, jintArray outArray, jint outWidth, jint outHeight) {
    if (!bufferObject || !outArray || outWidth <= 0 || outHeight <= 0) return -1;
    DescribeFn describe = describeFn();
    LockFn lock = lockFn();
    UnlockFn unlock = unlockFn();
    if (!describe || !lock || !unlock) return -2;

    AHardwareBuffer* buffer = AHardwareBuffer_fromHardwareBuffer(env, bufferObject);
    if (!buffer) return -3;

    AHardwareBuffer_Desc desc{};
    describe(buffer, &desc);
    if (desc.width == 0 || desc.height == 0) return -3;
    const int bpp = bytesPerPixel(desc.format);
    if (bpp == 0) {
        static int once = 0;
        if (once++ == 0) {
            __android_log_print(ANDROID_LOG_INFO, kTag, "unsupported format %u",
                                unsigned(desc.format));
        }
        return -6;
    }
    // describe() fills stride in pixels; some producers leave it zero, then the width is the row.
    const uint32_t stride = desc.stride != 0 ? desc.stride : desc.width;

    void* data = nullptr;
    int rc = lock(buffer, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, -1, nullptr, &data);
    if (rc != 0 || !data) {
        static int reported = 0;
        if (reported++ < 3) {
            __android_log_print(ANDROID_LOG_INFO, kTag, "lock failed rc=%d fmt=%u", rc,
                                unsigned(desc.format));
        }
        return -4;
    }

    jint* out = env->GetIntArrayElements(outArray, nullptr);
    if (!out) {
        unlock(buffer, nullptr);
        return -5;
    }
    const auto* base = static_cast<const uint8_t*>(data);
    for (int y = 0; y < outHeight; ++y) {
        const int sourceY = int(int64_t(y) * desc.height / outHeight);
        const uint8_t* row = base + size_t(sourceY) * stride * bpp;
        jint* line = out + size_t(y) * outWidth;
        for (int x = 0; x < outWidth; ++x) {
            const int sourceX = int(int64_t(x) * desc.width / outWidth);
            const uint8_t* pixel = row + size_t(sourceX) * bpp;
            if (bpp == 2) {
                const uint16_t value = uint16_t(pixel[0] | (pixel[1] << 8));
                const jint r = (value >> 11) & 0x1f;
                const jint g = (value >> 5) & 0x3f;
                const jint b = value & 0x1f;
                line[x] = 0xff000000 | ((r * 255 / 31) << 16) | ((g * 255 / 63) << 8) | (b * 255 / 31);
            } else {
                line[x] = 0xff000000 | (pixel[0] << 16) | (pixel[1] << 8) | pixel[2];
            }
        }
    }
    env->ReleaseIntArrayElements(outArray, out, 0);
    unlock(buffer, nullptr);
    return 0;
}

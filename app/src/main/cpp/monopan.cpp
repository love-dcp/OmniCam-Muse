// MONOPAN 50 / MONOPAN 50 迫冲 (Leica M3 port) - the capture side of the two filters.
//
// The filters themselves are the two monochrome LUTs in payload/meishe_lut (baked by
// tools/bake_monopan_luts.py), so the camera's own filter node gives the viewfinder its
// monochrome look. Everything the M3 adds on top of a grey image happens here, once per shot:
//
//   * the M3 tone curve and the film grain, from libmialgo_monopan.so. That library is a vendor
//     binary, so it is not reimplemented: monopan_helper (flown in by service.sh to
//     /data/local/tmp/monopan, away from /data/adb where the linker refuses the vendor OpenCL)
//     keeps a handle to it and runs one job per request over a small file protocol:
//         job/in.y   8-bit luma, width * height bytes
//         job/req    "%u %u %u" = width, height, variant, written last
//         job/out.y  the processed luma, same size
//         job/done   written by the helper once out.y is complete
//     The variant picks the parameter set inside the helper (see docs/MONOPAN.md).
//   * the vignetting, here in this file (see applyVignette below).
//
// Which variant a shot uses comes from the app: MonoPan.java writes
// files/jiege/monopan/active just before the capture metadata is built, and this pass consumes
// that file (it deletes it, so a stale one can never leak into another filter's shot).
//
// The pass runs on the capture's host buffer, the same render soft_light.cpp works on
// (MsRenderHDRHostBuffer on this device), so it sees the finished YUV frame: it takes the luma as
// the helper's input and writes the result back with neutral chroma, which is what makes the
// shot monochrome. Failures are silent by design - a missing helper or a timeout leaves the shot
// exactly as the camera rendered it.
#include <android/log.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/system_properties.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <thread>
#include <vector>

#include "native_hooks.h"

namespace {

constexpr char TAG[] = "MonoPan";
constexpr char kDir[] = "/data/user/0/com.oplus.camera/files/jiege/monopan";
constexpr char kActive[] = "/data/user/0/com.oplus.camera/files/jiege/monopan/active";
constexpr char kJobDir[] = "/data/user/0/com.oplus.camera/files/jiege/monopan/job";

// The two filters. 2 is MONOPAN 50, 1 is MONOPAN 50 迫冲; both numbers are the variant the vendor
// library keys its parameter set on, and the two below are the only values the app writes.
constexpr int kVariantPlain = 2;
constexpr int kVariantPush = 1;

// A shot's frame is dispatched right after the shutter, so the wait has to be short: past this
// the picture is left alone rather than held back. Override with persist.omnicam.monopan.timeout.
constexpr int kDefaultTimeoutMs = 4000;
// The app writes the variant file a moment before the render; anything older than this is a
// leftover from a filter the user has since left.
constexpr int kActiveMaxAgeSec = 120;

// Mirrors SMsHostBufferInfo in soft_light.cpp (plane[4], stride[4], width, height, ...). Only the
// plane descriptors and the geometry are read here, so a layout copy is enough.
struct HostBufferInfo {
    void *plane[4];
    uint32_t stride[4];
    uint32_t width;
    uint32_t height;
    uint32_t pixFmt;
    uint32_t primaries;
    uint32_t transfer;
    uint32_t matrix;
    uint32_t range;  // 1 = full
};

int property(const char *name, int fallback) {
    char value[PROP_VALUE_MAX] = {};
    if (__system_property_get(name, value) <= 0) return fallback;
    return std::atoi(value);
}

bool readWholeFile(const std::string &path, std::vector<uint8_t> &out) {
    FILE *f = std::fopen(path.c_str(), "rb");
    if (!f) return false;
    std::fseek(f, 0, SEEK_END);
    const long size = std::ftell(f);
    std::fseek(f, 0, SEEK_SET);
    if (size <= 0) {
        std::fclose(f);
        return false;
    }
    out.resize(size_t(size));
    const size_t got = std::fread(out.data(), 1, out.size(), f);
    std::fclose(f);
    return got == out.size();
}

bool writeWholeFile(const std::string &path, const void *data, size_t size) {
    FILE *f = std::fopen(path.c_str(), "wb");
    if (!f) return false;
    const size_t put = std::fwrite(data, 1, size, f);
    std::fclose(f);
    return put == size;
}

// The app's current selection, consumed so that no other filter's shot can pick it up.
int takeVariant() {
    struct stat st {};
    if (stat(kActive, &st) != 0) return 0;
    const auto now = std::chrono::system_clock::to_time_t(std::chrono::system_clock::now());
    std::vector<uint8_t> raw;
    if (!readWholeFile(kActive, raw)) return 0;
    unlink(kActive);
    if (now - st.st_mtime > kActiveMaxAgeSec) return 0;
    const int variant = std::atoi(reinterpret_cast<const char *>(raw.data()));
    return (variant == kVariantPlain || variant == kVariantPush) ? variant : 0;
}

// 10-bit codes -> 8-bit values, and back. The mode (top of the word or low ten bits) is detected
// from the plane itself, as the scatter in soft_light.cpp does.
struct Coding {
    bool full = true;
    bool msb = false;

    float codeOf(uint16_t raw) const { return float(msb ? raw >> 6 : raw & 0x3ff); }
    uint16_t rawOf(float code) const {
        const int q = int(std::lround(std::clamp(code, 0.0f, 1023.0f)));
        return uint16_t(msb ? q << 6 : q);
    }
    // 8-bit luma the helper works on: the encoded signal the camera would have stored.
    int toByte(float code) const {
        const float unit = full ? code / 1023.0f : (code - 64.0f) / 876.0f;
        return std::clamp(int(std::lround(unit * 255.0f)), 0, 255);
    }
    float fromByte(int value) const {
        const float unit = value / 255.0f;
        return full ? unit * 1023.0f : unit * 876.0f + 64.0f;
    }
};

// The vignette: the M3 look darkens the corners by about a stop. The camera's own table for it
// (payload/monopan/leica_filter_param_m3.bin, per lux and colour temperature) is a vendor tuning
// blob that is not decoded yet - what is applied here is the same radial falloff with a fixed
// shape, tunable through persist.omnicam.monopan.vig (0 disables it, 1 is about -1 EV in the
// corner) until the table lands (docs/MONOPAN.md).
void applyVignette(uint16_t *row, int w, int y, int h, const Coding &coding, float strength) {
    const float cy = (h - 1) * 0.5f;
    const float half = 0.5f * std::sqrt(float(w) * w + float(h) * h) * 0.5f;  // half diagonal, centred
    const float dy = (y - cy) / half;
    for (int x = 0; x < w; x++) {
        const float dx = (x - (w - 1) * 0.5f) / half;
        const float r = std::sqrt(dx * dx + dy * dy);  // 0 centre, ~1 corner
        // Flat in the middle, falling from 0.45 to 1.0 of the way out.
        const float t = std::clamp((r - 0.45f) / 0.55f, 0.0f, 1.0f);
        const float gain = 1.0f - strength * t * t;
        row[x] = coding.rawOf(coding.codeOf(row[x]) * gain);
    }
}

// One job: hand the helper the luma, wait for the processed one. Returns false when the helper is
// not running or did not answer in time, and the caller leaves the shot untouched.
bool runJob(int w, int h, int variant, const std::vector<uint8_t> &in, std::vector<uint8_t> &out, int timeoutMs) {
    const std::string req = std::string(kJobDir) + "/req";
    const std::string done = std::string(kJobDir) + "/done";
    const std::string inPath = std::string(kJobDir) + "/in.y";
    const std::string outPath = std::string(kJobDir) + "/out.y";
    unlink(done.c_str());
    if (!writeWholeFile(inPath, in.data(), in.size())) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "job input not writable");
        return false;
    }
    // The request is what makes the job visible to the helper, so it goes last and in one rename:
    // a half written request would be read as a job with a zero size.
    const std::string staging = req + ".tmp";
    char text[64];
    const int len = std::snprintf(text, sizeof text, "%d %d %d\n", w, h, variant);
    if (!writeWholeFile(staging, text, size_t(len)) || rename(staging.c_str(), req.c_str()) != 0) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "job request not writable");
        return false;
    }
    const auto start = std::chrono::steady_clock::now();
    bool ready = false;
    for (;;) {
        if (access(done.c_str(), F_OK) == 0) {
            ready = true;
            break;
        }
        if (std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - start).count() > timeoutMs) {
            break;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(2));
    }
    const long long ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - start).count();
    if (!ready) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "the helper did not answer in %d ms (is monopan_helper running?)", timeoutMs);
        unlink(req.c_str());
        return false;
    }
    const bool ok = readWholeFile(outPath, out) && out.size() >= in.size();
    __android_log_print(ANDROID_LOG_INFO, TAG, "%dx%d variant %d: %s, %lld ms", w, h, variant, ok ? "ok" : "no output", ms);
    unlink(req.c_str());
    unlink(done.c_str());
    unlink(inPath.c_str());
    unlink(outPath.c_str());
    return ok;
}

}  // namespace

// Called from the capture hook in soft_light.cpp after the camera has rendered the frame.
// Returns true when the shot was rewritten.
bool monopanApplyCapture(const void *buffer, int matrix, int range) {
    if (!buffer) return false;
    const int variant = takeVariant();
    if (!variant) return false;
    const auto *b = static_cast<const HostBufferInfo *>(buffer);
    const int w = int(b->width) & ~1, h = int(b->height) & ~1;
    if (!b->plane[0] || w < 64 || h < 64 || w > 8192 || h > 8192 || b->stride[0] < uint32_t(w) * 2) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "buffer rejected %p %ux%u stride %u", b->plane[0], b->width, b->height, b->stride[0]);
        return false;
    }
    Coding coding;
    coding.full = (range == 1);
    auto *y0 = reinterpret_cast<uint16_t *>(b->plane[0]);
    // 10 bits at the top of each word leave the low six empty; a stray low bit means the other mode.
    bool lowBits = false;
    for (int r = 0; r < h && !lowBits; r += 97) {
        const uint16_t *row = reinterpret_cast<const uint16_t *>(reinterpret_cast<uint8_t *>(b->plane[0]) + size_t(r) * b->stride[0]);
        for (int x = 0; x < w; x += 13) lowBits |= (row[x] & 0x3f) != 0;
    }
    coding.msb = !lowBits;

    const auto start = std::chrono::steady_clock::now();
    std::vector<uint8_t> in(size_t(w) * h), out;
    for (int y = 0; y < h; y++) {
        const uint16_t *row = reinterpret_cast<const uint16_t *>(reinterpret_cast<uint8_t *>(y0) + size_t(y) * b->stride[0]);
        uint8_t *dst = &in[size_t(y) * w];
        for (int x = 0; x < w; x++) dst[x] = uint8_t(coding.toByte(coding.codeOf(row[x])));
    }
    const int timeout = property("persist.omnicam.monopan.timeout", kDefaultTimeoutMs);
    if (!runJob(w, h, variant, in, out, timeout)) return false;

    const int vigPercent = property("persist.omnicam.monopan.vig", 35);
    const float vig = std::clamp(vigPercent, 0, 100) / 100.0f;
    uint32_t neutral = coding.rawOf(512.0f);
    if (!b->plane[1]) neutral = 0;  // single plane buffers: nothing to neutralise
    for (int y = 0; y < h; y++) {
        uint16_t *row = reinterpret_cast<uint16_t *>(reinterpret_cast<uint8_t *>(y0) + size_t(y) * b->stride[0]);
        const uint8_t *src = &out[size_t(y) * w];
        for (int x = 0; x < w; x++) row[x] = coding.rawOf(coding.fromByte(src[x]));
        if (vig > 0.0f) applyVignette(row, w, y, h, coding, vig);
        if (b->plane[1] && (y & 1) == 0) {
            // Neutral chroma for a half resolution plane: both U and V sit at the midpoint.
            auto *uv = reinterpret_cast<uint16_t *>(reinterpret_cast<uint8_t *>(b->plane[1]) + size_t(y / 2) * b->stride[1]);
            for (int x = 0; x < w / 2; x++) {
                uv[2 * x] = uint16_t(neutral);
                uv[2 * x + 1] = uint16_t(neutral);
            }
        }
    }
    __android_log_print(ANDROID_LOG_INFO, TAG, "capture %ux%u variant %d, vignette %d%%, %lld ms", w, h, variant, vigPercent,
                        (long long)std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - start).count());
    (void)matrix;
    return true;
}

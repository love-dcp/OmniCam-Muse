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
//   * the vignetting, here in this file (see vignetteAt below).
//
// Which variant a shot uses comes from the app: MonoPan.java rewrites
// files/jiege/monopan/active just before the capture metadata is built, and this pass reads it.
//
// The pass runs on whichever capture route the frame takes (soft_light.cpp hands it a MonoPanFrame
// for each of them, see native_hooks.h). Meishe renders the same shot through different entries -
// a P010 host buffer, an 8-bit host buffer, an AHardwareBuffer - depending on how the camera
// decided to run the effect, and the shot only looks right if every one of them is covered.
// The frame arrives as the finished YUV, so this takes the luma as the helper's input and writes
// the result back with neutral chroma, which is what makes the shot monochrome. Failures are
// silent by design - a missing helper or a timeout leaves the shot exactly as the camera rendered
// it.
#include <android/log.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/system_properties.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "native_hooks.h"

namespace {

constexpr char TAG[] = "MonoPan";
constexpr char kActive[] = "/data/user/0/com.oplus.camera/files/jiege/monopan/active";
constexpr char kJobDir[] = "/data/user/0/com.oplus.camera/files/jiege/monopan/job";
// Shared with the shot trace in soft_light.cpp: one file per shot says which entry the frames came
// in on and whether this pass took them.
constexpr char kTraceFile[] = "/data/user/0/com.oplus.camera/files/jiege/monopan/trace.txt";

// The two filters. The vendor helper keys its parameter set on this number: variant 1 is density 20 /
// grain 0.8 / kernel 3 / sigma 0.2 / mode 0, variant 2 is density 4 / grain 1.8 / kernel 7 / sigma
// 0.5 / mode 1. Measured on a 640x480 ramp (2026-11-06): variant 2 lifts the high frequency energy
// to 8.10 against 7.03, drops the mean to 101.7 against 113.4 and raises the spread to 64.6 against
// 56.5. That is the coarser, darker, harder one, so 2 is 迫冲 (push processed) and 1 is the plain 50.
constexpr int kVariantPlain = 1;
constexpr int kVariantPush = 2;

// A shot's frame is dispatched right after the shutter, so the wait has to be short: past this
// the picture is left alone rather than held back. Override with persist.omnicam.monopan.timeout.
constexpr int kDefaultTimeoutMs = 4000;

// One shot hands several frames through the hooks: the 1280x960 thumbnail first, the full frame
// after it. A marker is good for that many frames, and only while it is fresh - the grain pass
// costs a few hundred milliseconds and would stall the viewfinder if a later preview frame picked
// a marker up. The app rewrites the marker for every shot, so its timestamp starts each count.
//
// The window has to cover the slowest main image: the full frame arrives a second or two after the
// thumbnail on a quick shot, but a 哈苏超清 shot builds its 12.6MP render from a burst and can take
// much longer, and a main image that misses the window silently keeps the stock look. The frame
// count is what keeps the viewfinder safe, so the time limit can be generous.
constexpr int kMaxFramesPerShot = 16;
constexpr int kShotWindowSec = 120;

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

// Which frame of which shot is being handled. The marker's nanosecond timestamp is the shot's
// identity: MonoPan.java rewrites it for every shot, so a new timestamp means a new count.
struct Shot {
    int64_t stamp = 0;
    int frames = 0;
    struct Seen {
        const void *luma;
        int w, h;
    } seen[kMaxFramesPerShot] = {};
    int seenCount = 0;
};
Shot gShot;
std::mutex gShotLock;

// A frame that is left alone looks exactly like a frame the camera never handed over, and telling
// those two apart was the whole cost of tracking down a missing main image. Every refusal says why.
void noteSkip(const char *why, int w, int h) {
    static int64_t gLastMs = 0;
    static const char *gLastWhy = nullptr;
    const int64_t ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                           std::chrono::steady_clock::now().time_since_epoch())
                           .count();
    if (gLastWhy == why && ms - gLastMs < 200) return;
    gLastWhy = why;
    gLastMs = ms;
    __android_log_print(ANDROID_LOG_INFO, TAG, "skip %s %dx%d", why, w, h);
    if (FILE *f = std::fopen(kTraceFile, "a")) {
        std::fprintf(f, "   skip %s %dx%d\n", why, w, h);
        std::fclose(f);
    }
}

// The variant this frame uses, or 0 for every other filter and for a frame already handled. Reading
// the marker on every frame is what makes a frame that was skipped by one route still get its
// grain when it comes back through another; the seen list keeps that from happening twice.
int takeVariant(const void *luma, int w, int h) {
    struct stat st {};
    if (stat(kActive, &st) != 0) return 0;
    const int64_t stamp = int64_t(st.st_mtim.tv_sec) * 1000000000LL + int64_t(st.st_mtim.tv_nsec);
    const auto now = std::chrono::system_clock::to_time_t(std::chrono::system_clock::now());
    std::lock_guard<std::mutex> guard(gShotLock);
    if (stamp != gShot.stamp) {
        gShot.stamp = stamp;
        gShot.frames = 0;
        gShot.seenCount = 0;
    }
    int budget = property("persist.omnicam.monopan.frames", kMaxFramesPerShot);
    if (budget < 1 || budget > kMaxFramesPerShot) budget = kMaxFramesPerShot;
    if (now - st.st_mtime > kShotWindowSec) {
        noteSkip("stale", w, h);
        return 0;
    }
    if (gShot.frames >= budget) {
        noteSkip("budget", w, h);
        return 0;
    }
    for (int i = 0; i < gShot.seenCount; i++) {
        if (gShot.seen[i].luma == luma && gShot.seen[i].w == w && gShot.seen[i].h == h) {
            noteSkip("dup", w, h);
            return 0;
        }
    }
    std::vector<uint8_t> raw;
    if (!readWholeFile(kActive, raw) || raw.empty()) return 0;
    const int variant = std::atoi(reinterpret_cast<const char *>(raw.data()));
    if (variant != kVariantPlain && variant != kVariantPush) return 0;
    if (gShot.seenCount < kMaxFramesPerShot) gShot.seen[gShot.seenCount++] = {luma, w, h};
    gShot.frames++;
    return variant;
}

// Codes <-> 8-bit values for both sample sizes the routes carry: P010 (10 bits at the top of or in
// the low ten of a 16-bit word) and 8-bit NV12/NV21.
struct Coding {
    int bits = 16;
    bool msb = false;
    bool full = true;

    float maxCode() const { return bits == 8 ? 255.0f : 1023.0f; }
    float offCode() const { return bits == 8 ? 16.0f : 64.0f; }
    float spanCode() const { return bits == 8 ? 219.0f : 876.0f; }
    // The chroma midpoint: 128 for 8 bit, 512 for both P010 ranges.
    int neutral() const { return bits == 8 ? 128 : 512; }

    // One sample -> its code value.
    float codeOf(uint8_t raw) const { return float(raw); }
    float codeOf(uint16_t raw) const { return float(msb ? raw >> 6 : raw & 0x3ff); }
    // The encoded signal the camera would have stored, 0..255.
    int toByte(float code) const {
        const float unit = full ? code / maxCode() : (code - offCode()) / spanCode();
        return int(std::lround(std::clamp(unit, 0.0f, 1.0f) * 255.0f));
    }
    float fromByte(int value) const {
        const float unit = value / 255.0f;
        return full ? unit * maxCode() : unit * spanCode() + offCode();
    }
    void store(uint8_t &slot, float code) const { slot = uint8_t(std::lround(std::clamp(code, 0.0f, 255.0f))); }
    void store(uint16_t &slot, float code) const {
        const int q = int(std::lround(std::clamp(code, 0.0f, 1023.0f)));
        slot = uint16_t(msb ? q << 6 : q);
    }
};

// 0 centre, ~1 at the corners, flat until 45% of the way out.
float vignetteAt(int x, int y, int w, int h) {
    const float half = 0.5f * std::sqrt(float(w) * w + float(h) * h) * 0.5f;
    const float dx = (x - (w - 1) * 0.5f) / half;
    const float dy = (y - (h - 1) * 0.5f) / half;
    const float r = std::sqrt(dx * dx + dy * dy);
    const float t = std::clamp((r - 0.45f) / 0.55f, 0.0f, 1.0f);
    return t * t;
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

template <typename Sample>
Sample *rowAt(void *base, size_t stride, int y) {
    return reinterpret_cast<Sample *>(static_cast<uint8_t *>(base) + size_t(y) * stride);
}

// The whole pass for one frame: luma out to the helper and back, vignetting and neutral chroma on
// the way in.
template <typename Sample>
bool process(const MonoPanFrame &frame, int variant, const Coding &coding, int vigPercent, int w, int h) {
    const float strength = std::clamp(vigPercent, 0, 100) / 100.0f;
    std::vector<uint8_t> in(size_t(w) * h), out;
    for (int y = 0; y < h; y++) {
        const Sample *row = rowAt<Sample>(frame.luma, frame.lumaStride, y);
        uint8_t *dst = &in[size_t(y) * w];
        for (int x = 0; x < w; x++) dst[x] = uint8_t(coding.toByte(coding.codeOf(row[x])));
    }
    const int timeout = property("persist.omnicam.monopan.timeout", kDefaultTimeoutMs);
    if (!runJob(w, h, variant, in, out, timeout)) return false;

    for (int y = 0; y < h; y++) {
        Sample *row = rowAt<Sample>(frame.luma, frame.lumaStride, y);
        const uint8_t *src = &out[size_t(y) * w];
        for (int x = 0; x < w; x++) {
            float code = coding.fromByte(src[x]);
            if (strength > 0.0f) code *= 1.0f - strength * vignetteAt(x, y, w, h);
            coding.store(row[x], code);
        }
        if (frame.chroma && (y & 1) == 0) {
            // Neutral chroma: one U and one V per luma pair, interleaved.
            auto *uv = static_cast<uint8_t *>(frame.chroma) + size_t(y / 2) * frame.chromaStride;
            if (frame.chromaPixelStride == 1) {
                std::memset(uv, coding.neutral(), size_t(w));
            } else {
                auto *samples = reinterpret_cast<uint16_t *>(uv);
                for (int i = 0; i < w; i++) samples[i] = uint16_t(coding.neutral());
            }
        }
    }
    return true;
}

}  // namespace

// Called from the capture hooks in soft_light.cpp once the camera has rendered the frame.
// Returns true when the shot was rewritten.
bool monopanApplyFrame(const MonoPanFrame &frame) {
    if (!frame.luma || (frame.bits != 8 && frame.bits != 16)) return false;
    const int w = frame.width & ~1, h = frame.height & ~1;
    const size_t bytes = frame.bits == 8 ? 1 : 2;
    if (w < 64 || h < 64 || w > 8192 || h > 8192 || frame.lumaStride < size_t(w) * bytes) return false;
    const int variant = takeVariant(frame.luma, w, h);
    if (!variant) return false;

    Coding coding;
    coding.bits = frame.bits;
    coding.full = frame.fullRange;
    if (frame.bits == 16) {
        // 10 bits at the top of each word leave the low six empty; a stray low bit means the other
        // mode. Sampling the plane costs nothing next to the helper job.
        bool lowBits = false;
        for (int r = 0; r < h && !lowBits; r += 97) {
            const auto *row = reinterpret_cast<const uint16_t *>(static_cast<const uint8_t *>(frame.luma) + size_t(r) * frame.lumaStride);
            for (int x = 0; x < w; x += 13) lowBits |= (row[x] & 0x3f) != 0;
        }
        coding.msb = !lowBits;
    }
    // The table is applied at full strength: 22% measured about -0.4 stops in the corners, the M3
    // look. 35% read as -1.3 stops and looked like a lens fault rather than a filter.
    const int vigPercent = property("persist.omnicam.monopan.vig", 22);
    const auto start = std::chrono::steady_clock::now();
    const bool ok = frame.bits == 8 ? process<uint8_t>(frame, variant, coding, vigPercent, w, h)
                                    : process<uint16_t>(frame, variant, coding, vigPercent, w, h);
    if (ok) {
        const long long ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - start).count();
        __android_log_print(ANDROID_LOG_INFO, TAG, "capture %ux%u %d bit variant %d via %s, vignette %d%%, %lld ms", w, h, frame.bits,
                            variant, frame.route ? frame.route : "?", vigPercent, ms);
        // The same line goes into the shot trace so one file says both where the frame came in and
        // whether the pass took it (soft_light.cpp).
        if (FILE *f = std::fopen(kTraceFile, "a")) {
            std::fprintf(f, "   applied %ux%u %d bit variant %d via %s vig %d%% %lldms\n", w, h, frame.bits, variant,
                         frame.route ? frame.route : "?", vigPercent, ms);
            std::fclose(f);
        }
    }
    // A frame that was taken but not processed is the one case that has to speak up: the shot keeps
    // the stock look, which is indistinguishable from the pass never being offered the frame.
    if (!ok) {
        const long long ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - start).count();
        __android_log_print(ANDROID_LOG_ERROR, TAG, "fail %ux%u %d bit via %s after %lld ms", w, h, frame.bits,
                            frame.route ? frame.route : "?", ms);
        if (FILE *f = std::fopen(kTraceFile, "a")) {
            std::fprintf(f, "   fail %ux%u %d bit via %s %lldms\n", w, h, frame.bits, frame.route ? frame.route : "?", ms);
            std::fclose(f);
        }
    }
    return ok;
}

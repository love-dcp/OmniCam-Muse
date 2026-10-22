// POP fish-eye at capture (see docs/MODULE.md, "POP 鱼眼").
//
// The retro fish-eye exists in the camera already: the lens button drives
// KEY_RETRO_FILTER_FISH_EYE_ENALE for the preview, the Meishe renderer carries a whole
// CNvFishEyeEdgeBlur pass, and our payload's libmsnativefilter.so is the build that has it. The still
// loses the effect because the renderer's fish-eye flag is only ever set from the preview path.
//
// The renderer's parameter entry is the string dispatcher at file offset 0x761900 in that library:
//
//   void setParam(This *self, const char *name, int value)   // value is masked to one bit
//
// and for "Fish Eye Enable" it stores that bit at self + 0x9c. Nothing else in the capture path can
// turn the pass on, which is why the metadata key alone did nothing. The hook below runs after the
// dispatcher and sets the same byte once the lens is on, so the capture renderer applies the pass
// exactly like the preview one does.
//
// The offset is safe to pin: the library is ours (payload/lib64/libmsnativefilter.so, staged by the
// KSU module), not the device's, so the build the offset refers to is the build that is loaded.
#include <android/log.h>
#include <cstdint>
#include <cstring>
#include <dlfcn.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/system_properties.h>
#include <unistd.h>

#include "native_hooks.h"

namespace {

constexpr char kTag[] = "PopFishEye";
constexpr char kProp[] = "persist.omnicam.aura.nfish";
constexpr char kLib[] = "libmsnativefilter.so";
// Dropped by PopFisheyeSupport (Java) when the lens button changes state.
constexpr char kStatePath[] = "/data/user/0/com.oplus.camera/files/fisheye_lens";

// Renderer parameter dispatcher and its fish-eye byte, from the payload library.
constexpr uintptr_t kSetParamOffset = 0x761900;
constexpr uintptr_t kFishEyeByte = 0x9c;

enum class State { kUnknown, kOn, kOff };

State gState = State::kUnknown;
uint64_t gCheckedAtMs = 0;
bool gForced = false;
bool gInstalled = false;

uint64_t nowMs() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return uint64_t(ts.tv_sec) * 1000ull + uint64_t(ts.tv_nsec) / 1000000ull;
}

// The lens toggle lives in the camera's own data, and this hook has no JNI handle of its own, so the
// Java half drops a one-byte file next to the app's other port state. Missing file means off.
bool lensOn() {
    const uint64_t now = nowMs();
    if (gCheckedAtMs && now - gCheckedAtMs < 200) return gState == State::kOn;
    gCheckedAtMs = now;
    int fd = open(kStatePath, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        gState = State::kOff;
        return false;
    }
    char value = 0;
    const ssize_t n = read(fd, &value, 1);
    close(fd);
    gState = (n == 1 && value == '1') ? State::kOn : State::kOff;
    return gState == State::kOn;
}

using SetParamFn = void (*)(void *, const char *, int);
SetParamFn gSetParam = nullptr;

void afterSetParam(void *self, const char *name, int value) {
    if (!self) return;
    if (!gForced) {
        if (!lensOn()) return;
        __android_log_print(ANDROID_LOG_INFO, kTag,
                            "fish-eye byte set for capture (param %s)", name ? name : "?");
        gForced = true;
    }
    *reinterpret_cast<uint8_t *>(reinterpret_cast<uintptr_t>(self) + kFishEyeByte) = 1;
}

// The inline hook hands us the registers; keep the original signature so the trampoline works.
void setParamHook(void *self, const char *name, int value) {
    if (gSetParam) gSetParam(self, name, value);
    afterSetParam(self, name, value);
}

// Opt-in while the trampoline is being built: the LSPosed hook leaves a jump at the entry but hands
// back no backup pointer, so the original dispatcher body would not run. Nothing is installed unless
// the property is explicitly 1, which keeps the camera on stock behaviour by default.
bool moduleOn() {
    char value[PROP_VALUE_MAX] = {};
    if (__system_property_get(kProp, value) <= 0) return false;
    return value[0] == '1';
}

uintptr_t libraryBase(void *handle) {
    if (!handle) return 0;
    // Any exported symbol gives the load base through dladdr. Only the Meishe entry points keep their
    // C names in this library (the C++ ones are mangled), so try them in turn.
    void *symbol = dlsym(handle, "MsDestoryEffect");
    if (!symbol) symbol = dlsym(handle, "MsDoRenderHostBuffer");
    if (!symbol) symbol = dlsym(handle, "NvCleanupResources_videoeffect");
    Dl_info info{};
    if (!symbol || !dladdr(symbol, &info) || !info.dli_fbase) return 0;
    return uintptr_t(info.dli_fbase);
}

}  // namespace

void onLibraryLoadedForPopFisheye(HookFunction hook, const char *name, void *handle) {
    if (gInstalled || !name || !std::strstr(name, kLib)) return;
    if (!moduleOn()) {
        __android_log_print(ANDROID_LOG_INFO, kTag, "off (%s=0)", kProp);
        return;
    }
    const uintptr_t base = libraryBase(handle);
    if (!base) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "no base for %s", kLib);
        return;
    }
    void *target = reinterpret_cast<void *>(base + kSetParamOffset);
    // The first word tells whether the offset really lands on the dispatcher: the prologue is
    // "sub sp, sp, #0xa0" (0xd10283ff) followed by the frame push. A wrong base shows up here.
    __android_log_print(ANDROID_LOG_INFO, kTag, "base=%p target=%p first=0x%08x",
                        reinterpret_cast<void *>(base), target,
                        *reinterpret_cast<uint32_t *>(target));
    // The hook entry may report a non-zero code while still installing its jump (measured: the
    // veneer points into this library afterwards), so the backup pointer is what decides.
    const int rc = hook ? hook(target, reinterpret_cast<void *>(&setParamHook),
                               reinterpret_cast<void **>(&gSetParam)) : -1;
    __android_log_print(ANDROID_LOG_INFO, kTag, "hook rc=%d backup=%p", rc,
                        reinterpret_cast<void *>(gSetParam));
    if (!gSetParam) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "hook failed at %p", target);
        return;
    }
    gInstalled = true;
    __android_log_print(ANDROID_LOG_INFO, kTag, "renderer param hook at %p", target);
}

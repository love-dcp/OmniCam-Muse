// POP fish-eye at capture (see docs/MODULE.md, "POP 鱼眼").
//
// The retro fish-eye exists in the camera already: the lens button drives the preview through the
// texture request, the Meishe renderer carries a whole CNvFishEyeEdgeBlur pass, and our payload's
// libmsnativefilter.so is the build that has it. The still loses the effect because the renderer's
// fish-eye flag is only ever set from the preview path.
//
// The renderer's parameter entry is the string dispatcher at file offset 0x761900 in that library:
//
//   void setParam(This *self, const char *name, int value)   // value is masked to one bit
//
// and for "Fish Eye Enable" it stores that bit at self + 0x9c. Measured on device: a live photo plays
// back with the effect, its video path sends the name; the still path never does, and the metadata
// and request-tag flags the Java half sets are not enough on their own.
//
// The LSPosed hook entry point cannot wrap this address (it reports an error, hands back no backup
// and leaves a jump behind), so the trampoline below is ours: the first four instructions of the
// dispatcher are position independent, they are copied into the stub, the stub sets the byte and
// replays them before jumping back into the function body.
#include <android/log.h>
#include <cstdint>
#include <cstring>
#include <dlfcn.h>
#include <fcntl.h>
#include <linux/mman.h>
#include <sys/mman.h>
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
constexpr size_t kPrologueBytes = 16;  // sub sp, stp x29, add x29, str x25

// AArch64 encodings used by the stub and the entry patch.
constexpr uint32_t kStpPre(uint32_t rt, uint32_t rt2) {
    return 0xA9BF0000u | (rt2 << 10) | (31u << 5) | rt;  // [sp, #-16]!
}
constexpr uint32_t kLdpPost(uint32_t rt, uint32_t rt2) {
    return 0xA8C00000u | (rt2 << 10) | (31u << 5) | rt;  // [sp], #16
}
constexpr uint32_t kLdrX17Pc8 = 0x58000051u;  // ldr x17, #8
constexpr uint32_t kBrX17 = 0xD61F0220u;
constexpr uint32_t kBlrX17 = 0xD63F0220u;

enum class State { kUnknown, kOn, kOff };

State gState = State::kUnknown;
uint64_t gCheckedAtMs = 0;
bool gReported = false;
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

// Called from the stub with the dispatcher's own arguments; the stub saves and restores every
// register the call could clobber.
extern "C" void fisheyeStub(void *self, const char *name, int value) {
    if (!self) return;
    if (!lensOn()) return;
    *reinterpret_cast<uint8_t *>(reinterpret_cast<uintptr_t>(self) + kFishEyeByte) = 1;
    if (!gReported) {
        gReported = true;
        __android_log_print(ANDROID_LOG_INFO, kTag, "fish-eye byte set (param %s)",
                            name ? name : "?");
    }
}

// Opt-in: the trampoline runs, but writing the fish-eye byte from it crashes the renderer, so the
// stock path stays in charge unless the property is explicitly 1.
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

bool makeWritable(void *address, size_t length) {
    const long page = sysconf(_SC_PAGESIZE);
    const uintptr_t start = reinterpret_cast<uintptr_t>(address) & ~uintptr_t(page - 1);
    const uintptr_t end = (reinterpret_cast<uintptr_t>(address) + length + page - 1) &
                          ~uintptr_t(page - 1);
    return mprotect(reinterpret_cast<void *>(start), end - start,
                    PROT_READ | PROT_WRITE | PROT_EXEC) == 0;
}

// movz/movk build any 64-bit address in four instructions, so the stub never stores a literal next
// to code it could fall into (measured: a literal after "blr" is executed on return, SIGILL).
void emitMovImm64(uint32_t *&w, uint32_t reg, uint64_t value) {
    *w++ = 0xD2800000u | (uint32_t(value & 0xFFFFu) << 5) | reg;
    *w++ = 0xF2A00000u | (uint32_t((value >> 16) & 0xFFFFu) << 5) | reg;
    *w++ = 0xF2C00000u | (uint32_t((value >> 32) & 0xFFFFu) << 5) | reg;
    *w++ = 0xF2E00000u | (uint32_t((value >> 48) & 0xFFFFu) << 5) | reg;
}

// Builds the stub, patches the dispatcher entry to jump to it, and keeps what the stub needs.
bool installTrampoline(uintptr_t target) {
    uint8_t saved[kPrologueBytes];
    std::memcpy(saved, reinterpret_cast<void *>(target), kPrologueBytes);

    const size_t stubSize = 256;
    auto *stubBase = static_cast<uint32_t *>(mmap(nullptr, stubSize, PROT_READ | PROT_WRITE | PROT_EXEC,
                                                  MAP_PRIVATE | MAP_ANONYMOUS, -1, 0));
    if (stubBase == MAP_FAILED) return false;

    uint32_t *w = stubBase;
    // Save every caller-saved register the helper may clobber, keeping the arguments intact in x0-x2.
    const uint32_t pairs[][2] = {{0, 1}, {2, 3}, {4, 5}, {6, 7}, {8, 9},
                                 {10, 11}, {12, 13}, {14, 15}, {16, 17}, {18, 30}};
    for (const auto &pair : pairs) *w++ = kStpPre(pair[0], pair[1]);
    emitMovImm64(w, 17, reinterpret_cast<uint64_t>(&fisheyeStub));
    *w++ = kBlrX17;
    for (int i = int(sizeof(pairs) / sizeof(pairs[0])) - 1; i >= 0; --i) {
        *w++ = kLdpPost(pairs[i][0], pairs[i][1]);
    }
    // The dispatcher's own prologue, then straight into the rest of the function.
    std::memcpy(w, saved, kPrologueBytes);
    w += kPrologueBytes / 4;
    emitMovImm64(w, 17, target + kPrologueBytes);
    *w++ = kBrX17;

    const size_t stubBytes = size_t(reinterpret_cast<char *>(w) - reinterpret_cast<char *>(stubBase));
    __builtin___clear_cache(reinterpret_cast<char *>(stubBase),
                            reinterpret_cast<char *>(stubBase) + stubBytes);

    if (!makeWritable(reinterpret_cast<void *>(target), kPrologueBytes)) return false;
    auto *entry = reinterpret_cast<uint32_t *>(target);
    entry[0] = kLdrX17Pc8;
    entry[1] = kBrX17;
    *reinterpret_cast<uint64_t *>(&entry[2]) = reinterpret_cast<uint64_t>(stubBase);
    __builtin___clear_cache(reinterpret_cast<char *>(target),
                            reinterpret_cast<char *>(target) + kPrologueBytes);

    __android_log_print(ANDROID_LOG_INFO, kTag, "trampoline stub=%p target=%p", stubBase,
                        reinterpret_cast<void *>(target));
    return true;
}

}  // namespace

void onLibraryLoadedForPopFisheye(HookFunction hook, const char *name, void *handle) {
    (void)hook;  // the LSPosed entry point cannot wrap this address; the stub above does
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
    const uintptr_t target = base + kSetParamOffset;
    gInstalled = installTrampoline(target);
    if (!gInstalled) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "install failed at %p",
                            reinterpret_cast<void *>(target));
    }
}

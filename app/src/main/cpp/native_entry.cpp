// LSPosed native module entry; hooks only camera processes (listed in assets/native_init).
#include <android/log.h>
#include <cstdint>
#include <cstring>
#include <dlfcn.h>
#include <fcntl.h>
#include <sys/system_properties.h>
#include <unistd.h>

#include "capture_dump.h"
#include "native_hooks.h"

namespace {

// One switch per native half, read when a library loads: persist.omnicam.aura.n<id> set to 0
// leaves that half out for the next camera start. Absent means on, so nothing changes by default.
constexpr char kPropRemap[] = "persist.omnicam.aura.npop";     // libc path remap (POP/decision/tuning)
constexpr char kPropNatural[] = "persist.omnicam.aura.nnat";   // natural mode: tuning json + softening
constexpr char kPropGr[] = "persist.omnicam.aura.ngr";         // GR: LMT / vig / BasicTone core
constexpr char kPropLut[] = "persist.omnicam.aura.nlut";       // master LUT slots
constexpr char kPropSoft[] = "persist.omnicam.aura.nsl";       // soft light (MsRenderHDR*)

bool moduleOn(const char *prop) {
    char value[PROP_VALUE_MAX] = {};
    if (__system_property_get(prop, value) <= 0) return true;
    return value[0] != '0';
}

struct NativeApiEntries {
    uint32_t version;
    HookFunction hook;
    int (*unhook)(void *);
};

using LibraryLoadedCallback = void (*)(const char *name, void *handle);

HookFunction installHook;

void onLibraryLoaded(const char *name, void *handle) {
    if (!name) return;  // dlopen(NULL) hands back the main program
    // Upstream's capture dump carries its own switches and runs ungated.
    onLibraryLoadedForCaptureDump(installHook, name, handle);
    if (moduleOn(kPropNatural)) onLibraryLoadedForNatural(name);
    if (moduleOn(kPropGr)) onLibraryLoadedForGr(installHook, name, handle);
    if (moduleOn(kPropSoft)) onLibraryLoadedForSoftLight(installHook, name, handle);
    if (moduleOn(kPropLut)) onLibraryLoadedForLutSlots(installHook, name, handle);
    onLibraryLoadedForPopFisheye(installHook, name, handle);
}

// The library is also loaded in system_server and the gallery (release license check). Patching libc
// there races threads already inside it, so the hooks are installed only in camera processes.
bool inCameraProcess() {
    char name[128] = {};
    int fd = open("/proc/self/cmdline", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;
    ssize_t n = read(fd, name, sizeof(name) - 1);
    close(fd);
    if (n <= 0) return false;
    static constexpr char kCamera[] = "com.oplus.camera";
    size_t len = sizeof(kCamera) - 1;
    return std::strncmp(name, kCamera, len) == 0 && (name[len] == '\0' || name[len] == ':');
}

}  // namespace

void hookExport(HookFunction hook, void *handle, const char *symbol, void *replacement,
                void **backup, const char *tag) {
    void *address = dlsym(handle, symbol);
    if (!address || !hook || hook(address, replacement, backup) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, tag, "native hook failed: %s", symbol);
    }
}

extern "C" __attribute__((visibility("default"), used))
LibraryLoadedCallback native_init(const NativeApiEntries *entries) {
    if (!inCameraProcess()) return nullptr;
    installHook = entries->hook;
    const bool remap = moduleOn(kPropRemap);
    if (remap) installPopPathRemap(installHook);
    __android_log_print(ANDROID_LOG_INFO, "MuseNative",
                        "groups: remap=%d natural=%d gr=%d lut=%d soft=%d", int(remap),
                        int(moduleOn(kPropNatural)), int(moduleOn(kPropGr)),
                        int(moduleOn(kPropLut)), int(moduleOn(kPropSoft)));
    return onLibraryLoaded;
}

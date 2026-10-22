// POP fish-eye at capture (see docs/MODULE.md, "POP 鱼眼").
//
// The retro fish-eye exists three times in the camera:
//
//  * the preview draws it through the Meishe renderer (our soft-light hook sets the renderer's
//    "Fish Eye Enable" boolean, which is why the lens button works on screen);
//  * the APS capture engine carries a whole fish-eye node, APSFisheyeNode, in libAlgoInterface.so;
//  * both engines' parameter dispatcher knows the same name, "Fish Eye Enable".
//
// The still loses the effect because the APS node stays disabled: its getEnable() reads the
// parameter "fisheye_enable" from the process data (AlgoPreviewProcessData + 0x370) and the camera
// never sets it. Enabling the node through oplus_camera_algo_switch_config instead blackens the
// preview, because the node is initialised inside the shared APS engine. So the answer is given
// here: getEnable is an exported symbol, and it is forced on while the POP lens is on.
//
// The X9U release was checked for a different implementation: its libAlgoInterface.so has the same
// five fish-eye call sites, the same APSFisheyeNode and the same "fisheye_enable" parameter, so
// there was nothing device specific to copy.
#include <android/log.h>
#include <cstdint>
#include <cstring>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/system_properties.h>
#include <unistd.h>

#include "native_hooks.h"

namespace {

constexpr char kTag[] = "PopFishEye";
constexpr char kProp[] = "persist.omnicam.aura.nfish";
constexpr char kLib[] = "libAlgoInterface.so";
constexpr char kSymbol[] = "_ZN7android14APSFisheyeNode9getEnableEPNS_22AlgoPreviewProcessDataE";
// Dropped by PopFisheyeSupport (Java) when the lens button changes state.
constexpr char kStatePath[] = "/data/user/0/com.oplus.camera/files/fisheye_lens";

using GetEnableFn = bool (*)(void *self, void *data);
GetEnableFn gGetEnable = nullptr;
bool gInstalled = false;
bool gReported = false;

// The lens toggle lives in the camera's own data, and this hook has no JNI handle of its own, so the
// Java half drops a one-byte file next to the app's other port state. Missing file means off.
bool lensOn() {
    int fd = open(kStatePath, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;
    char value = 0;
    const ssize_t n = read(fd, &value, 1);
    close(fd);
    return n == 1 && value == '1';
}

bool getEnableHook(void *self, void *data) {
    const bool stock = gGetEnable ? gGetEnable(self, data) : false;
    if (!lensOn()) return stock;
    if (!gReported) {
        gReported = true;
        __android_log_print(ANDROID_LOG_INFO, kTag, "APS fish-eye node enabled (stock=%d)",
                            int(stock));
    }
    return true;
}

bool moduleOn() {
    char value[PROP_VALUE_MAX] = {};
    if (__system_property_get(kProp, value) <= 0) return true;
    return value[0] != '0';
}

}  // namespace

void onLibraryLoadedForPopFisheye(HookFunction hook, const char *name, void *handle) {
    if (gInstalled || !name || !std::strstr(name, kLib)) return;
    if (!moduleOn()) {
        __android_log_print(ANDROID_LOG_INFO, kTag, "off (%s=0)", kProp);
        return;
    }
    // The symbol is exported, so this goes through the module's ordinary symbol hook.
    hookExport(hook, handle, kSymbol, reinterpret_cast<void *>(&getEnableHook),
               reinterpret_cast<void **>(&gGetEnable), kTag);
    gInstalled = gGetEnable != nullptr;
    if (gInstalled) {
        __android_log_print(ANDROID_LOG_INFO, kTag, "APS fish-eye getEnable hooked (%p)",
                            reinterpret_cast<void *>(gGetEnable));
    }
}

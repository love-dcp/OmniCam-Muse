// Normal-mode 25MP unlock, native half (switch and marker: Unlock25mp.java).
//
// The unlock is ODM config files with relaxed gates. service.sh stages them under files/jiege;
// while the marker file "enabled" is there, reads of the stock paths are served from the staged
// copies. The files are read when the camera algorithms initialise, so a change applies after the
// camera process restarts.
//
// Profile-specific serving (files/jiege/profile, written by service.sh):
//   PMA110 (X9 Ultra)  -- six files from payload/unlock25mp, generated from the X9U stock /odm
//                         (tools/patch_25mp.py): four lighthouse lux/ISO tables, the APS thermal
//                         tables and the decision config.
//   PKJ110 (X8U)       -- only the decision config, from payload_pkj110/unlock25mp
//                         (tools/patch_x8u_unlock25mp.py): the two HYBRIDRAW 2DOL full-QBC plans
//                         with the ISO / thermal / motion / burst vetoes removed. The X9U
//                         lighthouse and APS copies must never be served here — they describe
//                         sensor modes this device does not have and would kill every shutter.
//
// Only this process is redirected, so nothing on /odm changes and switching off restores stock
// behaviour.
//
// Runs inside the path hooks: no libc stat/access/open here (the hooks would call back in), only
// raw syscalls.
#include <cstddef>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <sys/syscall.h>
#include <unistd.h>

#include "native_hooks.h"

namespace {

constexpr char kOdmConfig[] = "/odm/etc/camera/config/";
constexpr char kRoot[] = "/data/user/0/com.oplus.camera/files/jiege/unlock25mp/";
constexpr char kRootPkj[] = "/data/user/0/com.oplus.camera/files/jiege/unlock25mp_pkj/";
constexpr char kMarker[] = "/data/user/0/com.oplus.camera/files/jiege/unlock25mp/enabled";
// Profile file written by module/service.sh ("PMA110" / "PKJ110"). The staged copies differ per
// device: the payload/unlock25mp set is generated from the X9 Ultra stock /odm, so a device that
// is not PMA110 must never read those (its capture gate asks for sensor modes these files do not
// carry and the shutter dies on a null array); PKJ110 gets its own decision-only copy.
constexpr char kProfile[] = "/data/user/0/com.oplus.camera/files/jiege/profile";
constexpr char kDecision[] = "oplus_camera_preview_decision_config.json";
constexpr const char *kNames[] = {
    "lighthousemain",
    "lighthousewide",
    "lighthousetele",
    "lighthouseultratele",
    kDecision,
    "oplus_camera_aps_config",
};

bool readable(const char *path) {
    return syscall(SYS_faccessat, AT_FDCWD, path, R_OK, 0) == 0;
}

/** Reads the profile file into buf (raw syscalls: the path hooks must not recurse). */
bool readProfile(char *buf, size_t n) {
    int fd = (int)syscall(SYS_openat, AT_FDCWD, kProfile, O_RDONLY | O_CLOEXEC, 0);
    if (fd < 0) return false;
    ssize_t got = syscall(SYS_read, fd, buf, n - 1);
    syscall(SYS_close, fd);
    if (got <= 0) return false;
    buf[got < (ssize_t)n - 1 ? got : (ssize_t)n - 1] = '\0';
    return true;
}

}  // namespace

const char *unlock25mpRemapPath(const char *path, char *buf, size_t n) {
    constexpr size_t prefix = sizeof(kOdmConfig) - 1;
    if (std::strncmp(path, kOdmConfig, prefix) != 0) return nullptr;
    const char *name = path + prefix;
    if (!readable(kMarker)) return nullptr;

    char profile[16] = {0};
    if (!readProfile(profile, sizeof(profile))) return nullptr;
    const char *root = nullptr;
    if (std::strncmp(profile, "PMA110", 6) == 0) {
        for (const char *candidate : kNames) {
            if (std::strcmp(name, candidate) == 0) {
                root = kRoot;
                break;
            }
        }
    } else if (std::strncmp(profile, "PKJ110", 6) == 0) {
        // X8U: only the relaxed decision config; the lighthouse / APS copies stay stock.
        if (std::strcmp(name, kDecision) == 0) root = kRootPkj;
    }
    if (root == nullptr) return nullptr;
    std::snprintf(buf, n, "%s%s", root, name);
    return readable(buf) ? buf : nullptr;
}

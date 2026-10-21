package local.jiege.hook.highpixel;

import java.io.File;
import local.jiege.hook.common.MarkerSwitch;
import local.jiege.settings.ModuleSettings;

/**
 * 哈苏超清 200MP without the big-face gate. The native side (face200m.cpp) serves the decision copy
 * staged by service.sh while the marker is there; applies after the camera process restarts.
 *
 * PMA110 (X9 Ultra) only. The staged copies are X9 Ultra decision trees, and 50MP capture on PKJ110
 * reads that same decision path: the X9 branches never complete a defer job, so the shutter holds the
 * APSDeferJobGoverner lock and the viewfinder freezes for good. The 200MP readout does not exist on
 * PKJ110, so the marker is cleared here even when an earlier build left the setting switched on.
 */
public final class HighPixel200mFace {
    private static final File ROOT = new File("/data/user/0/com.oplus.camera/files/jiege/face200m");

    private HighPixel200mFace() {}

    public static void installCamera(ClassLoader classLoader) {
        if (!ModuleSettings.isPma110()) {
            new File(ROOT, "enabled").delete();
            return;
        }
        MarkerSwitch.install("HighPixel200mFace", ModuleSettings.HIGH_PIXEL_200M_NO_FACE, ROOT);
    }
}

package local.jiege.hook;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import local.jiege.hook.common.CameraLogMirror;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Skip;
import local.jiege.hook.common.Symbols;
import local.jiege.hook.diag.EntryButtonProbe;
import local.jiege.hook.diag.GrStylePanelProbe;
import local.jiege.hook.diag.HighPixelProbe;
import local.jiege.hook.filters.FilterGroupInjector;
import local.jiege.hook.gr.GrEffectValues;
import local.jiege.hook.gr.GrPort;
import local.jiege.hook.gallery.AddressNavigation;
import local.jiege.hook.gallery.BatchWatermark;
import local.jiege.hook.gallery.WatermarkSeriesUnlock;
import local.jiege.hook.guard.UnmountedGuard;
import local.jiege.hook.highpixel.HighPixel200mFace;
import local.jiege.hook.highpixel.HighPixelEffects;
import local.jiege.hook.highpixel.HighPixelFilters;
import local.jiege.hook.highpixel.HighPixelSupport;
import local.jiege.hook.launch.KeyLaunchMode;
import local.jiege.hook.master.MasterPalette;
import local.jiege.hook.masterfilter.MasterFilterCompletion;
import local.jiege.hook.mastercolor.MasterColor;
import local.jiege.hook.diag.MainThreadWatchdog;
import local.jiege.hook.masterlut.MasterContrast;
import local.jiege.hook.masterlut.MasterLutSlots;
import local.jiege.hook.modestate.ModeStateSplit;
import local.jiege.hook.natural.NaturalMode;
import local.jiege.hook.palette.GalleryColorPalette;
import local.jiege.hook.pop.PopPort;
import local.jiege.hook.seamless.SeamlessBlurGuard;
import local.jiege.hook.shutter.CameraSoundForced;
import local.jiege.hook.shutter.ShutterSound;
import local.jiege.hook.softlight.SoftLight;
import local.jiege.hook.tilt.TiltShiftCapture;
import local.jiege.hook.unlock25mp.Unlock25mp;
import local.jiege.hook.watermark.ModelSpoof;
import local.jiege.hook.watermark.VideoWatermarkGuard;
import local.jiege.hook.watermark.XpanWatermarkIsolation;
import local.jiege.hook.xpan.XpanLivePhoto;
import local.jiege.hook.xpan.XpanViewfinderClean;
import local.jiege.hook.video.VideoFilterHighSpec;
import local.jiege.hook.x10.PaletteFeatureSwitch;
import local.jiege.hook.x10.X10FilterPort;

/**
 * LSPosed entry (assets/xposed_init). The native part is libjiege_native.so, declared in
 * assets/native_init.
 *
 * Obfuscated camera and gallery code is reached through Symbols (fingerprints in
 * assets/symbols.json), resolved once per app build before any feature installs.
 *
 * Features:
 *   filters       filter catalogs, normal and master scope managed apart; group injection
 *   gr            Ricoh GR port (camera + gallery watermark)
 *   mastercolor   master mode colour: live look tables (HNCS, Lumix, film, ...) in place of the stock master LMT
 *   pop           POP (retro camera) port and POP high pixel
 *   x10           X10 filters and palette
 *   palette       palette editing in the gallery photo editor
 *   gallery       gallery photo details: the address opens a map app; hasselblad / LUMO / GR watermarks for every photo;
 *                 batch watermark add / undo from the multi-select menu
 *   highpixel     哈苏超清 (high pixel mode) filters and palette entries
 *   guard         camera / gallery without the module's /odm files: features off, GR / POP not reopened
 *   tilt          tilt-shift capture algorithm in the saved photo
 *   seamless      guard for the seamless-UI blur bitmap (front POP thumbnail crash)
 *   masterfilter  master filter completion (extra LUTs, neon glow, Portra halation)
 *   shutter       shutter sound in silent / vibrate mode, own shutter volume (camera + system)
 *   modestate     flash and live photo state per mode
 *   video         normal filters at 4K / 60fps in normal video
 *   launch        quick launch key opens a chosen mode
 *   natural       natural photo mode: AI detail enhancement and LCE off, extra softening (tuning json, tone transfer)
 */
public final class ModuleEntry implements IXposedHookLoadPackage, IXposedHookZygoteInit {
    private static final String CAMERA = "com.oplus.camera";
    private static final String GALLERY = "com.coloros.gallery3d";
    /** System framework (system_server) scope. */
    private static final String SYSTEM = "android";

    @Override public void initZygote(StartupParam param) {
        Symbols.setModulePath(param.modulePath);
    }

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        ClassLoader classLoader = param.classLoader;
        if (SYSTEM.equals(param.packageName)) {
            CameraSoundForced.installSystem(classLoader);
            BatchWatermark.installSystem(classLoader);
        } else if (GALLERY.equals(param.packageName)) {
            Symbols.install("gallery", classLoader, param.appInfo.sourceDir, param.appInfo.dataDir);
            AddressNavigation.installGallery(classLoader);
            if (!UnmountedGuard.filesMounted()) {
                UnmountedGuard.installGallery();
                return;
            }
            GalleryColorPalette.installGallery(classLoader);
            GrPort.installGallery(classLoader);
            WatermarkSeriesUnlock.installGallery(classLoader);
            BatchWatermark.installGallery(classLoader);
        } else if (CAMERA.equals(param.packageName)) {
            // Without the module's /odm files the native hooks would feed the stock libraries the
            // ported POP settings and LUTs, so the guard decides first.
            if (!UnmountedGuard.filesMounted()) {
                Symbols.install("camera", classLoader, param.appInfo.sourceDir, param.appInfo.dataDir);
                UnmountedGuard.installCamera(classLoader);
                return;
            }
            // LSPosed calls native_init when this library is loaded; it must be registered before
            // the camera loads libAlgoInterface / libBasicTone*, so it goes before the features.
            // Every install below can be taken out at runtime through persist.omnicam.aura.skip
            // (see Skip) so a hang can be bisected by group without a rebuild.
            if (!Skip.off("native")) System.loadLibrary("jiege_native");
            if (CAMERA.equals(param.processName)) MainThreadWatchdog.install();
            Log.i("Skip", "hook groups skipped: " + Skip.raw());
            Symbols.install("camera", classLoader, param.appInfo.sourceDir, param.appInfo.dataDir);
            if (!Skip.off("pop")) PopPort.installCamera(classLoader, CAMERA.equals(param.processName));
            if (!Skip.off("filter")) FilterGroupInjector.installCamera(classLoader);
            if (!Skip.off("filter")) X10FilterPort.installCamera(classLoader);
            // X8U port: the palette feature switch is answered in Java (the OCS config container
            // cannot be synthesised on PKJ110) -- see x10/PaletteFeatureSwitch.
            if (!Skip.off("filter")) {
                Log.guard("Palette", "feature switch", () -> PaletteFeatureSwitch.installCamera(classLoader));
            }
            // X8U port: our own palette panel (master, video, POP and the GR page share it).
            if (!Skip.off("palette")) {
                Log.guard("Palette", "master palette", () -> MasterPalette.install(classLoader));
            }
            if (!Skip.off("hpeffects")) HighPixelEffects.installCamera(classLoader);
            // X8U port: the hi-res mode's own husselblad label switch (PKJ110 answers false).
            if (!Skip.off("hpentry")) {
                Log.guard("HighPixel", "hasselblad entry", () -> HighPixelSupport.installCamera(classLoader));
            }
            // The feature the mode has no presenter for: the filter row, its gate and its capture tag.
            if (!Skip.off("hpfilter")) {
                Log.guard("HighPixelFilters", "install", () -> HighPixelFilters.install(classLoader));
            }
            if (!Skip.off("capture")) TiltShiftCapture.installCamera(classLoader);
            if (!Skip.off("capture")) SeamlessBlurGuard.installCamera(classLoader);
            if (!Skip.off("launch")) KeyLaunchMode.installCamera(classLoader);
            if (!Skip.off("diag")) {
                Log.guard("CameraLog", "camera log mirror", () -> CameraLogMirror.install(classLoader));
            }
            if (!Skip.off("capture")) MasterFilterCompletion.installCamera(classLoader);
            if (!Skip.off("gr")) GrPort.installCamera(classLoader);
            if (!Skip.off("master")) MasterColor.installCamera(classLoader);
            if (!Skip.off("sound")) ShutterSound.installCamera(classLoader);
            if (!Skip.off("modestate")) ModeStateSplit.installCamera(classLoader);
            if (!Skip.off("video")) VideoFilterHighSpec.installCamera(classLoader);
            if (!Skip.off("natural")) NaturalMode.installCamera(classLoader);
            if (!Skip.off("unlock")) Unlock25mp.installCamera(classLoader);
            if (!Skip.off("hp200m")) HighPixel200mFace.installCamera(classLoader);
            if (!Skip.off("spoof")) ModelSpoof.installCamera(classLoader);
            if (!Skip.off("xpan")) XpanWatermarkIsolation.installCamera(classLoader);
            if (!Skip.off("xpan")) XpanLivePhoto.installCamera(classLoader);
            if (!Skip.off("xpan")) XpanViewfinderClean.installCamera(classLoader);
            if (!Skip.off("watermark")) VideoWatermarkGuard.installCamera(classLoader);
            if (!Skip.off("softlight")) SoftLight.installCamera(classLoader);
            if (!Skip.off("master")) MasterLutSlots.installCamera(classLoader);
            if (!Skip.off("master")) MasterContrast.installCamera(classLoader);
            // X8U port: the GR page's own parameter dial (drives the camera's GR rows), plus the
            // read-only probes left over from the port work. The probes are off unless their
            // persist.omnicam.aura.* property names one; none of them rides a hot path.
            if (!Skip.off("gr")) {
                Log.guard("GrEffects", "install", () -> GrEffectValues.install(classLoader));
                Log.guard("GrProbe", "install", () -> GrStylePanelProbe.install(classLoader));
            }
            if (!Skip.off("diag")) {
                Log.guard("HpProbe", "install", () -> HighPixelProbe.install(classLoader));
                Log.guard("EntryProbe", "install", () -> EntryButtonProbe.install(classLoader));
            }
        }
    }
}

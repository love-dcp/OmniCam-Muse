package local.jiege.hook.xpan;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Symbols;

/**
 * XPAN's own watermark is not the photo-watermark pipeline at all.
 *
 * <p>{@code XPanPresenter.onPrepareCaptureRequest} takes the 65:24 crop region, grows its bottom
 * edge by {@code CameraRequestTag.n1}, hands the region to the HAL as
 * {@code HASSELBLAD_XPAN_CROP_REGION} and the height itself as
 * {@code XPAN_HASSELBLAD_WATERMARK_HEIGHT}. The strip is then composited in the camera HAL, under
 * the photo, with no APS watermark pass involved. Field log of a normal XPAN capture:
 *
 * <pre>
 *   OCAM_XPanPresenter: onPrepareCaptureRequest, cropRegion:[360, 64, 2912, 7872], XPAN add height:0.0
 * </pre>
 *
 * <p>{@code n1} stays 0 here because the mode never sets the Hasselblad watermark height on this
 * port. The photo-watermark tile is the wrong switch for it: that slot holds a 4:3 border style
 * ({@code lumo_style_2}) which, as soon as it is open, drops the XPAN crop (1920x710 -> 1920x864)
 * and makes APS reject the composition ("watermark size not valid"), which is the all-white cover.
 *
 * <p>This hook only writes {@code n1}, i.e. it asks the HAL for the native footer strip and leaves
 * the photo-watermark slot alone. {@code persist.omnicam.xpan.hswm} is the strip height in output
 * pixels (0 = off).
 */
public final class XpanHsWatermark {
    private static final String TAG = "XpanHsWatermark";
    private static final String HEIGHT_PROPERTY = "persist.omnicam.xpan.hswm";
    /** {@code EventMessageCaptureRequestStage.B} is the request tag; {@code n1} is the add height. */
    private static final String TAG_FIELD = "B";
    private static final String HEIGHT_FIELD = "n1";

    private XpanHsWatermark() {}

    public static void installCamera(ClassLoader classLoader) {
        Log.guard(TAG, "install", () -> {
            final Symbols symbols = Symbols.get();
            XposedBridge.hookMethod(symbols.method("XpanCapturePresenter.prepareRequest"), new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    float height = height();
                    if (height <= 0f || param.args.length == 0 || param.args[0] == null) return;
                    try {
                        Object tag = XposedHelpers.getObjectField(param.args[0], TAG_FIELD);
                        if (tag == null) {
                            if (!tagMissingLogged) {
                                tagMissingLogged = true;
                                Log.i(TAG, "request tag is not on the stage, footer not applied");
                            }
                            return;
                        }
                        XposedHelpers.setFloatField(tag, HEIGHT_FIELD, height);
                        if (height != appliedHeight) {
                            appliedHeight = height;
                            Log.i(TAG, "XPAN hasselblad footer height set to " + height);
                        }
                    } catch (Throwable error) {
                        if (!applyErrorLogged) {
                            applyErrorLogged = true;
                            Log.e(TAG, "footer height not applied", error);
                        }
                    }
                }
            });
        });
    }

    private static volatile float appliedHeight = -1f;
    private static volatile boolean tagMissingLogged;
    private static volatile boolean applyErrorLogged;

    private static float height() {
        try {
            Class<?> type = Class.forName("android.os.SystemProperties");
            Object value = type.getMethod("get", String.class, String.class)
                .invoke(null, HEIGHT_PROPERTY, "0");
            float height = Float.parseFloat(String.valueOf(value));
            return height > 0f && height < 4000f ? height : 0f;
        } catch (Throwable error) {
            return 0f;
        }
    }
}

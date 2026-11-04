package local.jiege.hook.aicomposition;

import android.view.View;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import local.jiege.hook.common.Log;

/**
 * Moves the composition entry icon up by one slot so it stops colliding with the icon below it.
 *
 * <p>Two approaches were tried and rejected before this one:
 *
 * <ul>
 *   <li>Editing the margins moved the neighbouring icon as well. The icon column keeps several
 *       entries sharing one layout-parameter object, so the change applied to all of them and one
 *       icon ended up hidden behind another.
 *   <li>Plain {@code setTranslationY} lasted one frame: the entry animates its own translation
 *       when it appears and reset the offset immediately.
 * </ul>
 *
 * <p>So the offset is a translation -- it moves nothing but this one view's drawing -- and the
 * camera's own resets are intercepted so they keep the offset instead of discarding it. A layout
 * listener must not be used to re-apply it: setting a translation inside a layout callback
 * requests another layout, and that loop once starved the camera's icon drawing badly enough that
 * icons disappeared from the screen.
 */
final class CompositionIconShift {

    private static final String TAG = "CompIcon";
    /** Kotlin class in classes33.dex of this camera build. */
    private static final String BUTTON =
        "com.oplus.camera.feature.aicomposition.view.AICompositionEnterButton";
    /** One icon slot in this build's column: entry top 1476, next icon top 1638. */
    private static final int SLOT_PX = 162;

    /** The one view being shifted; the translation hook ignores everything else. */
    private static volatile View target;
    private static volatile float offset;
    /**
     * The entry button as built, kept even when no shift is applied. The touch hook needs it to tell
     * a press on the icon from a press on the viewfinder, and a view-tree search cannot find it:
     * the icon lives in a layer the preview's own tree does not expose.
     */
    private static volatile View entry;

    /** True while this module is the one keeping the icon hidden, so only that is undone again. */
    private static volatile boolean hiddenByUs;
    private static final java.util.concurrent.atomic.AtomicBoolean hideLogged =
        new java.util.concurrent.atomic.AtomicBoolean();

    /** Composition belongs to the stills mode; {@code persist.omnicam.compiconall=1} shows it everywhere. */
    private static boolean allowed() {
        if ("1".equals(prop("persist.omnicam.compiconall", "0"))) return true;
        return RealsceneProbe.currentModeSupportsComposition();
    }

    /**
     * Show or hide the entry icon for the mode the camera just switched into.
     *
     * <p>Only the hide direction is remembered: when the icon comes back to 照片 it is restored
     * exclusively if this module was the one that hid it, so the camera keeps control of its own
     * icon state everywhere else.
     */
    static void applyModeVisibility() {
        View view = entry;
        if (view == null) return;
        try {
            if (!allowed()) {
                hiddenByUs = true;
                view.setVisibility(View.GONE);
                return;
            }
            if (hiddenByUs) {
                hiddenByUs = false;
                view.setVisibility(View.VISIBLE);
            }
        } catch (Throwable error) {
            Log.i(TAG, "entry icon visibility failed: " + error);
        }
    }

    private CompositionIconShift() {}

    static void install(ClassLoader classLoader) {
        Class<?> button = XposedHelpers.findClassIfExists(BUTTON, classLoader);
        if (button == null) {
            Log.i(TAG, "entry button not in this build, icon left where it is");
            return;
        }
        // The entry re-applies its translation on every appear animation. Adding the offset here
        // means the last writer wins with the shift still in place.
        XposedBridge.hookAllMethods(View.class, "setTranslationY", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                View view = target;
                if (view == null || param.thisObject != view) return;
                if (!(param.args[0] instanceof Float)) return;
                param.args[0] = (Float) param.args[0] + offset;
            }
        });
        XposedBridge.hookAllConstructors(button, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!(param.thisObject instanceof View)) return;
                final View view = (View) param.thisObject;
                // No size and no parent at construction; post runs after the first layout pass.
                view.post(() -> {
                    entry = view;
                    apply(view);
                    applyModeVisibility();
                });
            }
        });
        // The camera shows this icon on its own schedule, and it does so in every mode that has
        // the composition module loaded -- which is all of them, because the feature list is built
        // once per process. Composition itself is a stills feature, so the icon is dropped while
        // the camera is anywhere else (视频 / 人像 / POP / 哈苏超清 / 大师 ...). Done at the drawing
        // layer rather than at the feature gates: gating those once took composition away from 照片.
        XposedBridge.hookAllMethods(View.class, "setVisibility", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                View view = entry;
                if (view == null || param.thisObject != view) return;
                if (param.args.length != 1 || !(param.args[0] instanceof Integer)) return;
                if ((Integer) param.args[0] != View.VISIBLE) return;
                if (allowed()) return;
                param.args[0] = View.GONE;
                hiddenByUs = true;
                if (hideLogged.compareAndSet(false, true)) {
                    Log.i(TAG, "entry icon hidden in " + RealsceneProbe.currentMode() + " mode");
                }
            }
        });
        Log.i(TAG, "entry icon shift installed");
    }

    /**
     * No gesture of our own on the icon any more: a tap opens composition the way it always did,
     * and the channel is decided by the viewfinder instead. Tapping the preview runs the on-device
     * detector on the point that was tapped -- the camera draws its focus box there, which is the
     * clearest statement of "this is my subject" the feature gets -- and doing nothing leaves the
     * framing to the model. Tap versus hold on the icon was a distinction nobody could see, and
     * the press that switched it back looked like it had done nothing at all.
     */
    private static void apply(View view) {
        String raw = prop("persist.omnicam.compiconlift", "");
        int move;
        try {
            move = raw.isEmpty() ? -SLOT_PX : Integer.parseInt(raw.trim());
        } catch (Throwable ignored) {
            move = -SLOT_PX;
        }
        if (move == 0) {
            target = null;
            offset = 0f;
            view.setTranslationY(0f);
            Log.i(TAG, "icon left in place (compiconlift=0)");
            return;
        }
        target = view;
        offset = move;
        // Goes through the hook above, so this lands at exactly `move` rather than twice it.
        view.setTranslationY(0f);
        Log.i(TAG, "icon shifted " + move + "px (top " + view.getTop()
            + ", h " + view.getHeight() + ", id " + name(view) + ")");
    }

    /**
     * Is this screen point on the composition entry icon?
     *
     * <p>Used to keep a long press on the icon from being read as a long press on the viewfinder:
     * the preview surface covers the whole screen, so a press on the icon also lands inside it and
     * pinned the subject to whatever sat under the icon -- the box ending up on the desk or the
     * folder at the right edge. The margin is a finger's worth, because the icon is small and a
     * near miss is still a press on it.
     */
    /**
     * Is this view the composition entry icon, or something inside it?
     *
     * <p>Asked by identity rather than by rectangle. The icon is shifted up with a translation, and
     * a screen rectangle computed from its layout position missed the shifted spot -- so a tap on
     * the icon was read as a tap on the viewfinder and pinned the subject to whatever was under it.
     */
    static boolean isEntry(View view) {
        View target = entry;
        if (target == null || view == null) return false;
        for (View walk = view; walk != null; ) {
            if (walk == target) return true;
            android.view.ViewParent parent = walk.getParent();
            walk = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    static boolean overEntry(float x, float y) {
        View view = entry;
        if (view == null) return false;
        // Only while the icon is actually on screen. Modes without composition never show it, but
        // the view survives from the last photo session, so its rectangle kept claiming a spot at
        // the right edge of whatever mode the user switched to -- and the zoom ladder's last stop
        // sits inside that spot, which made zooming open composition.
        try {
            if (view.getVisibility() != View.VISIBLE || !view.isShown()) return false;
        } catch (Throwable error) {
            return false;
        }
        int[] at = new int[2];
        try {
            view.getLocationOnScreen(at);
        } catch (Throwable error) {
            return false;
        }
        float width = view.getWidth() * view.getScaleX();
        float height = view.getHeight() * view.getScaleY();
        if (width <= 0f || height <= 0f) return false;
        // A finger's worth, not a hand's: this used to be 48px on every side, which grew the hit
        // area to ~190px tall and swallowed the zoom ladder's last two stops.
        float pad = 6f;
        return x >= at[0] - pad && x <= at[0] + width + pad
            && y >= at[1] - pad && y <= at[1] + height + pad;
    }

    private static String name(View view) {
        int id = view.getId();
        if (id == View.NO_ID) return "no-id";
        try {
            return view.getResources().getResourceEntryName(id);
        } catch (Throwable error) {
            return "0x" + Integer.toHexString(id);
        }
    }

    static String prop(String key, String fallback) {
        try {
            Class<?> system = Class.forName("android.os.SystemProperties");
            Object value = system.getMethod("get", String.class, String.class)
                .invoke(null, key, fallback);
            return value == null ? fallback : value.toString();
        } catch (Throwable error) {
            return fallback;
        }
    }
}

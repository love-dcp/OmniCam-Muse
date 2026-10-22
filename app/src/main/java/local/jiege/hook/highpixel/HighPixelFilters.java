package local.jiege.hook.highpixel;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Settings;
import local.jiege.hook.common.Symbols;
import local.jiege.hook.filters.FilterCatalog;
import local.jiege.hook.filters.FilterEntry;
import local.jiege.hook.filters.FilterScope;
import local.jiege.settings.ModuleSettings;

/**
 * The filter row 哈苏超清 does not get from the camera.
 *
 * The mode is in the normal filter scope (FilterScope.ofMode: everything but master), so its filters
 * are photo mode's: the same group, the same order, the same selection the camera keeps for it. What
 * the mode has no UI for is picking one - the camera never builds its effect panel there - so the row
 * is drawn here and each tap goes through the camera's own presenter, FilterPresenter.select(index),
 * which is exactly what the stock strip calls. Preview and capture then take the filter through the
 * camera's filter node, with nothing added on our side.
 *
 * Filter names come from the camera's own filter group, so they are the localized stock names.
 */
public final class HighPixelFilters {
    private static final String TAG = "HighPixelFilters";
    private static final String MODE = "highPixel";
    /** The feature that builds the filter presenter, its strip and its capture path. */
    private static final String FILTER_FEATURE = "com.oplus.camera.feature.filter";
    /** The capture stage that applies the filter LUT. */
    private static final String FILTER_ALGORITHM = "aps_algo_filter";

    /** The stage that applies the palette (tone / saturation) to a capture. */
    private static final String PALETTE_ALGORITHM = "aps_algo_basic_tone";
    /**
     * Whether the capture request also gets the filter stage. The 50 MP pipeline is built without it
     * and it is not yet proven that adding it back is accepted, so it can be switched off without a
     * rebuild: setprop persist.omnicam.aura.hpfiltercapture 0.
     */
    private static final String PROP_CAPTURE = "persist.omnicam.aura.hpfiltercapture";

    /** Filters the mode could not be given a presenter for. */
    private static volatile Object presenter;
    /**
     * The most recently constructed filter presenter, whatever mode it serves. The camera builds one
     * filter presenter per process and re-points it at the mode on screen by calling
     * {@code FeaturePresenter.R(cameraId, modeName)} when it enters a mode that has the filter feature.
     * 哈苏超清 gets the feature only through this module, so that re-point is never made for it; when the
     * mode is entered by switching (not a cold start into it) the presenter is still bound to the mode it
     * was built for (retroCamera, common, ...), and this module saw no presenter for highPixel. Keeping
     * the last instance lets {@link #ensurePresenter} re-point it to highPixel itself.
     */
    private static volatile Object lastPresenter;
    private static volatile List<String> types = Collections.emptyList();
    private static volatile int current = -1;
    private static volatile long lastPickAt;
    /** Guards the camera reporting a pick back into select() (see there). */
    private static boolean applying;
    /** Rewrites the fixed card and its dots after a swipe selected another filter. */
    private static volatile Runnable panelRefresh;
    /** Plays the card/dot switch animation for a swipe in this direction (+1 next, -1 previous). */
    private static volatile SwipeAnim panelSwipe;

    /** The panel's switch animation, one direction per swipe. */
    private interface SwipeAnim {
        void run(int direction);
    }
    private static final java.util.Set<String> seen =
        java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    private HighPixelFilters() {}

    /** The camera app loader: the LUT manager is an app class, the request tag comes from the SDK's. */
    private static volatile ClassLoader appLoader;

    public static void install(ClassLoader classLoader) {
        appLoader = classLoader;
        final Symbols symbols = Symbols.get();
        try {
            XposedBridge.hookAllConstructors(symbols.cls("FilterPresenter"), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    adopt(symbols, param.thisObject);
                }
            });
        } catch (Throwable error) {
            Log.e(TAG, "filter presenter hook failed", error);
        }
        try {
            XposedBridge.hookMethod(symbols.method("FilterPresenter.select"), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (adopt(symbols, param.thisObject) && param.args.length > 0
                        && param.args[0] instanceof Integer) {
                        Log.i(TAG, "camera selected filter " + param.args[0] + " on this mode");
                    }
                }
            });
        } catch (Throwable error) {
            Log.e(TAG, "filter select hook failed", error);
        }
        Log.i(TAG, "installed, presenter class " + symbols.cls("FilterPresenter").getName());
        hookFeatureList(classLoader);
        hookFeatureGate(classLoader);
        hookCaptureTag(classLoader);
        HighPixelPaletteCapture.install(classLoader);
    }

    /**
     * The mode's features are decided by the feature config: FeatureManager asks
     * FeatureConfigureAdapter for the mode's feature names and builds one presenter per name. The
     * filter feature is not in 哈苏超清's list, which is why the mode has no filter presenter and no
     * filter UI at all. This logs each list so the mode's own set can be told apart.
     */
    private static void hookFeatureList(ClassLoader classLoader) {
        try {
            Class<?> adapter = XposedHelpers.findClass(
                "com.oplus.ocs.camera.config.FeatureConfigureAdapter", classLoader);
            XposedBridge.hookAllMethods(adapter, "getFeatureInfoList", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Object value = param.getResult();
                    if (!(value instanceof java.util.Set)) return;
                    java.util.Set<?> set = (java.util.Set<?>) value;
                    StringBuilder text = new StringBuilder();
                    int kept = 0;
                    for (Object name : set) {
                        if (kept++ == 60) { text.append(" ..."); break; }
                        text.append(' ').append(name);
                    }
                    String line = "feature list[" + (param.args.length > 0 ? param.args[0] : "?") + "] n="
                        + set.size() + ":" + text;
                    if (seen.add(line)) Log.i(TAG, line);
                    // 哈苏超清's list has no filter feature, so FeatureManager never builds a filter
                    // presenter for it: no filter strip, no capture filter, nothing to open. The mode's
                    // list is a fixed, very small one - it is told apart from photo mode's by what it
                    // does not have (no beauty, no ai scene, no quick video) rather than by name, which
                    // is not available at this point.
                    if (!isHighPixelList(set) || !Settings.get(ModuleSettings.HIGH_PIXEL_EFFECTS)) return;
                    java.util.Set<Object> withFilter = new java.util.LinkedHashSet<>();
                    withFilter.addAll((java.util.Set<Object>) set);
                    withFilter.add(FILTER_FEATURE);
                    param.setResult(withFilter);
                    Log.i(TAG, "filter feature added to the 哈苏超清 feature list (" + set.size() + " -> "
                        + withFilter.size() + ")");
                }
            });
            Log.i(TAG, "feature list hooked");
        } catch (Throwable error) {
            Log.e(TAG, "feature list hook failed", error);
        }
    }

    /**
     * The factory decides per feature and mode whether the feature is built:
     * {@code FeatureFactory.d(cameraId, modeName, featureName)} answers true for a feature the mode
     * gets. Everything the feature needs is in the factory already - the strip, the presenter and the
     * capture side - so the only missing answer is this one.
     */
    private static void hookFeatureGate(ClassLoader classLoader) {
        try {
            Class<?> factory = XposedHelpers.findClass("gb.d0", classLoader);
            XposedBridge.hookAllMethods(factory, "d", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length != 3 || !(param.args[1] instanceof String)
                        || !(param.args[2] instanceof String)) return;
                    if (!MODE.equals(param.args[1]) || !FILTER_FEATURE.equals(param.args[2])) return;
                    if (!Settings.get(ModuleSettings.HIGH_PIXEL_EFFECTS)) return;
                    param.setResult(Boolean.TRUE);
                    Log.i(TAG, "filter feature enabled for the mode");
                }
            });
            Log.i(TAG, "feature gate hooked");
        } catch (Throwable error) {
            Log.e(TAG, "feature gate hook failed", error);
        }
    }

    /** Whether this is 哈苏超清's feature list (a small one, without the features photo mode has). */
    private static boolean isHighPixelList(java.util.Set<?> set) {
        if (set.size() > 20 || set.contains(FILTER_FEATURE)) return false;
        for (String marker : HIGH_PIXEL_MARKERS) if (!set.contains(marker)) return false;
        return !set.contains("com.oplus.camera.feature.beauty")
            && !set.contains("com.oplus.camera.feature.ai_scene")
            && !set.contains("com.oplus.camera.feature.quick_video")
            && !set.contains("com.oplus.preview.capture.ai");
    }

    /** Features 哈苏超清 has and no other mode's small list does, all at once. */
    private static final String[] HIGH_PIXEL_MARKERS = {
        "com.oplus.camera.feature.ten_bit",
        "com.oplus.camera.feature.hdr_all_route",
        "com.oplus.camera.feature.face_detect",
        "com.oplus.camera.feature.frame_ratio",
        "com.oplus.camera.feature.time_shutter",
        "com.oplus.camera.feature.dark_detect",
        "com.oplus.camera.feature.live_photo",
        "com.oplus.camera.feature.zoom",
    };

    /** Keeps the presenter of this mode, and rereads its list. */
    private static boolean adopt(Symbols symbols, Object instance) {
        String mode;
        try {
            mode = String.valueOf(symbols.get("FeaturePresenter.modeName", instance));
        } catch (Throwable error) {
            if (seen.add("adoptFail")) Log.i(TAG, "adopt: modeName unreadable: " + error);
            return false;
        }
        lastPresenter = instance;
        if (!MODE.equals(mode)) {
            // Diagnostic for the missing-row regression: tell every non-highPixel presenter apart
            // (and whether the mode name is ever null here) so a silently-dropped highPixel
            // presenter is not mistaken for "the camera never built one".
            if (seen.add("adopt:" + mode)) Log.i(TAG, "adopt: presenter is mode \"" + mode + "\", not highPixel");
            return false;
        }
        presenter = instance;
        read(symbols);
        return true;
    }

    /**
     * Re-points the camera's own filter presenter at 哈苏超清 when the mode has none. The camera builds
     * one filter presenter per process and normally re-points it with
     * {@code FeaturePresenter.R(cameraId, modeName)} on mode switch, but 哈苏超清 has no filter feature in
     * the camera's own feature graph, so the re-point is never made and the presenter stays bound to the
     * mode it was built for. Called when the mode is up and {@link #presenter} is still null: it reuses the
     * presenter built for another mode, switches its modeName/cameraId to highPixel, and re-reads its list.
     *
     * @return true when the presenter is now available for the mode.
     */
    public static synchronized boolean ensurePresenter() {
        if (presenter != null) return true;
        Object instance = lastPresenter;
        if (instance == null) return false;
        Symbols symbols = Symbols.get();
        try {
            // FeaturePresenter.R(int cameraId, String modeName) is the camera's own re-point method
            // (lc.w.R); cameraId is replayed from the instance's own field so the derived config stays
            // the one the camera chose for it.
            int cameraId = XposedHelpers.getIntField(instance, "m");
            XposedHelpers.callMethod(instance, "R", Integer.valueOf(cameraId), MODE);
            if (seen.add("repoint")) {
                Log.i(TAG, "presenter re-pointed to highPixel (cameraId " + cameraId + ")");
            }
        } catch (Throwable error) {
            if (seen.add("repointFail")) {
                Log.i(TAG, "presenter re-point failed: " + error);
            }
            return false;
        }
        return adopt(symbols, instance);
    }

    /** The mode's filter types and their names, as the presenter has them. */
    private static void read(Symbols symbols) {
        try {
            Object list = symbols.call("FilterPresenter.types", presenter);
            if (!(list instanceof List)) return;
            List<String> read = new ArrayList<>();
            for (Object type : (List<?>) list) read.add(String.valueOf(type));
            types = Collections.unmodifiableList(read);
            // The presenter's own index counts differently from the strip list (it reported one past
            // the filter that was picked), so it is only used until the row has a selection of its own.
            if (current < 0) {
                current = ((Number) symbols.call("FilterPresenter.currentIndex", presenter)).intValue();
            }
            if (seen.add("types:" + read.size() + ":" + read.get(0))) {
                Log.i(TAG, "filters for the mode: " + read.size() + ", current " + current);
            }
        } catch (Throwable error) {
            Log.i(TAG, "filter list unreadable: " + error);
        }
    }

    /**
     * Display names for the filter types: the camera's own strings from the normal filter group,
     * which the camera already localizes (标准, 清透, 琥珀 ...). A type the group does not name is
     * taken from the module catalog, and a type neither knows keeps the file name, minus ".bin".
     */
    private static List<String> labels(Activity activity, List<String> types) {
        List<String> out = new ArrayList<>();
        java.util.Map<String, String> fromGroup = groupNames(activity);
        for (String type : types) {
            String name = fromGroup.get(type);
            if (name == null || name.isEmpty()) {
                FilterEntry entry = FilterCatalog.get(FilterScope.NORMAL).find(type);
                name = entry != null && entry.title != null ? entry.title : null;
            }
            if (name == null || name.isEmpty()) {
                name = type == null ? "" : type;
                int dot = name.indexOf('.');
                if (dot > 0) name = name.substring(0, dot);
            }
            out.add(name);
        }
        return out;
    }

    /** Type to display name, as the normal filter group has them (names are resource IDs). */
    private static java.util.Map<String, String> groupNames(Activity activity) {
        java.util.Map<String, String> names = new java.util.HashMap<>();
        try {
            Class<?> manager = XposedHelpers.findClass(
                "com.oplus.camera.filter.FilterGroupManager", activity.getClassLoader());
            Object group = XposedHelpers.getStaticObjectField(manager, "sFilterGroup");
            List<?> groupTypes = (List<?>) XposedHelpers.callMethod(group, "getTypeList", Boolean.FALSE);
            List<?> ids = (List<?>) XposedHelpers.callMethod(group, "getNameIdList", Boolean.FALSE);
            for (int i = 0; i < groupTypes.size() && i < ids.size(); i++) {
                Object id = ids.get(i);
                String name = id instanceof Integer ? activity.getString((Integer) id) : String.valueOf(id);
                names.put(String.valueOf(groupTypes.get(i)), name);
            }
        } catch (Throwable error) {
            Log.i(TAG, "filter names from the group failed: " + error);
        }
        return names;
    }

    /**
     * The photo mode sends the filter with every capture through the request tag (mbFilterOpen and
     * mFilterType, read by the SDK's filter stage). The 哈苏超清 page never fills them in - it had no
     * filter feature - so the picked filter only reached the preview. Same two fields photo mode sets.
     */
    private static void hookCaptureTag(ClassLoader classLoader) {
        try {
            Class<?> processor = XposedHelpers.findClass(
                "com.oplus.ocs.camera.consumer.ApsProcessor", classLoader);
            Class<?> tagClass = XposedHelpers.findClass(
                "com.oplus.ocs.camera.common.util.CameraRequestTag", classLoader);
            final Class<?> keys = XposedHelpers.findClass(
                "com.oplus.ocs.camera.common.util.ParameterKeys", classLoader);
            final Object algorithmKey = XposedHelpers.getStaticObjectField(keys, "KEY_CAPTURE_ALGO_LIST");
            XposedHelpers.findAndHookMethod(processor, "createMetaItemInfo", tagClass, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!Settings.get(ModuleSettings.HIGH_PIXEL_EFFECTS)) return;
                    if ("0".equals(prop(PROP_CAPTURE))) return;
                    try {
                        Object tag = param.args[0];
                        String mode = String.valueOf(XposedHelpers.getObjectField(tag, "mCaptureMode"));
                        boolean open = XposedHelpers.getBooleanField(tag, "mbFilterOpen");
                        // Diagnostic for "the jpeg has no filter": says whether the meta hook runs at
                        // all and what the tag looked like at that moment.
                        if (seen.add("metaAfter:" + mode + ":" + open)) {
                            Log.i(TAG, "meta after: mode=" + mode + " filterOpen=" + open
                                + " type=" + XposedHelpers.getObjectField(tag, "mFilterType")
                                + " key=" + algorithmKey);
                        }
                        if (!"high_pixel_mode".equals(mode)) return;
                        // No palette stage is inserted any more: with aps_algo_basic_tone in
                        // the list (or the color_palette pair) this mode's 50 MP request came
                        // back as a 3244x5132 image (2026-10-23 07:03 / 07:06), so the chain
                        // stays exactly as the camera built it and only the capture params
                        // carry the dial (HighPixelPaletteCapture).
                        if (!open) return;
                        addFilterNode(param.getResult(), algorithmKey);
                    } catch (Throwable error) {
                        Log.i(TAG, "algorithm list: " + error);
                    }
                }

                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!Settings.get(ModuleSettings.HIGH_PIXEL_EFFECTS)) return;
                    Object tag = param.args[0];
                    try {
                        if (!"high_pixel_mode".equals(XposedHelpers.getObjectField(tag, "mCaptureMode"))) return;
                        String type = currentType();
                        if (type == null || "default".equals(type)) return;
                        // Same gate as stampTag: without it this hook re-opens the filter on a tag that
                        // stampTag deliberately left alone, and a filter with no capture ID destroys the
                        // picture. Kept out of the request entirely so the shot stays a plain 50 MP.
                        if (!hasCaptureId(type)) return;
                        String capture = type;
                        XposedHelpers.setBooleanField(tag, "mbFilterOpen", true);
                        XposedHelpers.setObjectField(tag, "mFilterType", capture);
                        // The algorithm list is chosen from the APS request tag, not from this one:
                        // set the same two fields there so the filter stage is selected at all.
                        Object aps = XposedHelpers.getObjectField(tag, "mApsRequestTag");
                        if (aps != null) {
                            if (seen.add("apsFields")) {
                                StringBuilder fields = new StringBuilder();
                                for (java.lang.reflect.Field field : aps.getClass().getDeclaredFields()) {
                                    if (!field.getName().toLowerCase().contains("filter")) continue;
                                    fields.append(field.getName()).append(' ');
                                }
                                Log.i(TAG, "aps tag filter fields: " + fields);
                            }
                            try { XposedHelpers.setBooleanField(aps, "mbFilterOpen", true); } catch (Throwable ignored) { }
                            try { XposedHelpers.setObjectField(aps, "mFilterType", capture); } catch (Throwable ignored) { }
                            try { XposedHelpers.setObjectField(aps, "mFilterId", null); } catch (Throwable ignored) { }
                        }
                        if (seen.add("capture:" + type)) {
                            Log.i(TAG, "capture request carries the filter: " + type);
                        }
                    } catch (Throwable error) {
                        Log.i(TAG, "capture tag: " + error);
                    }
                }
            });
            // The algorithm list is chosen before createMetaItemInfo runs, so the flag has to be on
            // the tag from the moment the SDK looks at it: stamp every request method of the processor
            // that is handed a tag.
            int stamped = 0;
            for (java.lang.reflect.Method method : processor.getDeclaredMethods()) {
                Class<?>[] params = method.getParameterTypes();
                if (params.length == 0 || params[0] != tagClass) continue;
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        stampTag(param.args[0]);
                    }
                });
                stamped++;
            }
            Log.i(TAG, "capture tag hooked on " + stamped + " request methods");
        } catch (Throwable error) {
            Log.e(TAG, "capture tag hook failed", error);
        }
    }

    /** Puts the picked filter on a request tag while the request is still being built. */
    private static void stampTag(Object tag) {
        if (!Settings.get(ModuleSettings.HIGH_PIXEL_EFFECTS)) return;
        if (tag == null || "0".equals(prop(PROP_CAPTURE))) return;
        try {
            if (!"high_pixel_mode".equals(XposedHelpers.getObjectField(tag, "mCaptureMode"))) return;
            String type = currentType();
            if (type == null || "default".equals(type)) return;
            // A filter the capture side has no ID for cannot be applied there: the filter stage would
            // run with nothing to look up and hand back a broken picture (和光/meicam 那类就是这样，
            // 成片是 92 MB 的满屏噪点). Those filters stay preview-only.
            if (!hasCaptureId(type)) return;
            XposedHelpers.setBooleanField(tag, "mbFilterOpen", true);
            XposedHelpers.setObjectField(tag, "mFilterType", type);
            // 大师模式's snapshot showed 和光 carried as an HDR bracket *pair* of LUTs
            // (portra400_hdr_master_a_1.bin,portra400_hdr_master_d_1.bin) with enum 111 and intensity 0;
            // 哈苏超清 was given a single portra400_normal.bin with enum 1 / intensity 100, which the
            // filter node cannot place across the 6-frame HDR merge - the jpeg is grain. Hand it the
            // pair the mode's bracket expects, plus the enum / intensity 大师 uses for the same look.
            masterizeForCapture(tag, type);
        } catch (Throwable ignored) {
            // a tag without those fields simply keeps its own values
        }
    }

    /**
     * The palette's basic-tone stage. The retro page's request carries it and its dial reaches the
     * capture; 哈苏超清's list does not have it at all, which is why the dial only moved the preview.
     * Inserted after the raw stage, the place the retro page puts it.
     */
    private static void addPaletteNode(Object meta, Object algorithmKey) {
        if (meta == null) { Log.i(TAG, "palette stage: no meta"); return; }
        Object value = XposedHelpers.callMethod(meta, "get", algorithmKey);
        if (!(value instanceof String[])) {
            if (seen.add("paletteType")) {
                Log.i(TAG, "palette stage: algorithm list is "
                    + (value == null ? "null" : value.getClass().getName()));
            }
            return;
        }
        String[] current = (String[]) value;
        // Only the tone stage: adding aps_algo_color_palette next to it made the 50 MP request
        // come back as a 12 MP image (measured 2026-10-23 07:03), so it stays out.
        for (String name : current) if (PALETTE_ALGORITHM.equals(name)) return;
        // The tone stage only: the capture params now carry KEY_MASTER_MODE_CONTRAST /
        // SATURATION and KEY_PALETTE_CAPTURE_ENABLE (see HighPixelPaletteCapture) and that is
        // what the node reads.
        java.util.List<String> next = new java.util.ArrayList<>();
        int at = 0;
        for (int i = 0; i < current.length; i++) {
            next.add(current[i]);
            if ("aps_algo_hybridraw".equals(current[i])) {
                next.add(PALETTE_ALGORITHM);
                at = next.size();
            }
        }
        if (at == 0) {
            next.add(0, PALETTE_ALGORITHM);
        }
        String[] result = next.toArray(new String[0]);
        boolean set = setAlgorithmList(meta, algorithmKey, result);
        if (seen.add("paletteNode:" + set)) {
            Log.i(TAG, "palette stage " + (set ? "added" : "rejected") + ": "
                + java.util.Arrays.toString(result));
        }
    }

    /** True while the palette dial is off its neutral position. */
    /** The dial's current tone/saturation from the LUT manager, or null when unreadable. */
    static int[] paletteValues() {
        try {
            Class<?> holder = XposedHelpers.findClass(
                "com.oplus.camera.common.utils.Lut3dDataManager$b", appLoader);
            Object manager = XposedHelpers.getStaticObjectField(holder, "a");
            if (manager == null) return null;
            return new int[] {XposedHelpers.getIntField(manager, "w"),
                XposedHelpers.getIntField(manager, "v")};
        } catch (Throwable error) {
            return null;
        }
    }

    private static boolean paletteActive() {
        try {
            Class<?> holder = XposedHelpers.findClass(
                "com.oplus.camera.common.utils.Lut3dDataManager$b", appLoader);
            Object manager = XposedHelpers.getStaticObjectField(holder, "a");
            if (manager == null) return false;
            return XposedHelpers.getIntField(manager, "w") != 0
                || XposedHelpers.getIntField(manager, "v") != 0;
        } catch (Throwable error) {
            if (seen.add("paletteActive")) Log.i(TAG, "palette state unreadable: " + error);
            return false;
        }
    }

    /** Hands the meta item a new algorithm list; returns whether a setter took it. */
    private static boolean setAlgorithmList(Object meta, Object algorithmKey, String[] next) {
        Object[] candidates = { next, java.util.Arrays.asList(next) };
        for (Object candidate : candidates) {
            for (String setter : new String[] {"setValue", "set", "put"}) {
                try {
                    XposedHelpers.callMethod(meta, setter, algorithmKey, candidate);
                    return true;
                } catch (Throwable ignored) {
                    // try the next spelling
                }
            }
        }
        // No setter: the item may still be a thin wrapper over a map we can edit directly.
        if (seen.add("metaFields")) {
            StringBuilder fields = new StringBuilder("meta fields of " + meta.getClass().getName() + ":");
            for (java.lang.reflect.Field field : meta.getClass().getDeclaredFields()) {
                fields.append(' ').append(field.getName()).append(':').append(field.getType().getSimpleName());
            }
            Log.i(TAG, fields.toString());
        }
        for (java.lang.Class<?> type = meta.getClass(); type != null; type = type.getSuperclass()) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                if (!java.util.Map.class.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    Object map = field.get(meta);
                    if (!(map instanceof java.util.Map)) continue;
                    ((java.util.Map<Object, Object>) map).put(algorithmKey, next);
                    if (seen.add("mapPut")) Log.i(TAG, "algorithm list replaced through map field " + field.getName());
                    return true;
                } catch (Throwable ignored) {
                    // try the next field
                }
            }
        }
        return false;
    }

    /**
     * The 50 MP request is built without the filter stage (its algorithm list ends at watermark), so
     * the filter type the tag carries would reach a pipeline that has nothing to apply it with. The
     * stage is the same one photo mode uses; add it to the list the request was built with.
     */
    private static void addFilterNode(Object meta, Object algorithmKey) {
        if (meta == null) { Log.i(TAG, "algorithm list: no meta"); return; }
        Object value = XposedHelpers.callMethod(meta, "get", algorithmKey);
        if (!(value instanceof String[])) {
            if (seen.add("metaType")) {
                Log.i(TAG, "algorithm list: value is " + (value == null ? "null" : value.getClass().getName())
                    + " on " + meta.getClass().getName());
            }
            return;
        }
        String[] current = (String[]) value;
        boolean present = false;
        for (String name : current) if (FILTER_ALGORITHM.equals(name)) present = true;
        if (present) {
            // The stage is already there (the mode's own list has it). 大师模式 additionally runs
            // aps_algo_basic_tone in front of it and comes out clean at 50 MP, but the list cannot be
            // extended and taking the watermark's slot for it changed nothing, so the chain is left as
            // the mode built it.
            return;
        }
        String[] next = java.util.Arrays.copyOf(current, current.length + 1);
        next[current.length] = FILTER_ALGORITHM;
        if (seen.add("metaApi")) {
            StringBuilder api = new StringBuilder();
            for (java.lang.reflect.Method method : meta.getClass().getDeclaredMethods()) {
                if (!method.getName().toLowerCase().startsWith("set")) continue;
                api.append(method.getName()).append(java.util.Arrays.toString(method.getParameterTypes()))
                    .append(' ');
            }
            Log.i(TAG, "meta api: " + api);
        }
        Object[] candidates = { next, java.util.Arrays.asList(next) };
        Throwable last = null;
        for (Object candidate : candidates) {
            for (String setter : new String[] {"setValue", "set", "put"}) {
                try {
                    XposedHelpers.callMethod(meta, setter, algorithmKey, candidate);
                    if (seen.add("algo")) {
                        Log.i(TAG, "filter stage added to the capture request via " + setter
                            + ": " + java.util.Arrays.toString(next));
                    }
                    return;
                } catch (Throwable error) {
                    last = error;
                }
            }
        }
        if (seen.add("algoFail")) Log.i(TAG, "algorithm list: no setter worked, " + last);
    }

    /** A system property; empty when unset. */
    private static String prop(String name) {
        try {
            Class<?> type = Class.forName("android.os.SystemProperties");
            Object value = type.getMethod("get", String.class, String.class).invoke(null, name, "");
            return String.valueOf(value);
        } catch (Throwable error) {
            return "";
        }
    }

    /** Where the camera keeps the filter LUTs it loads by name. */
    private static final String LUT_DIR = "/odm/etc/camera/meishe_lut";

    /**
     * The film-grain filters, one row each: the LUT(s) the capture side should load, the enum /
     * intensity 大师模式 sends with them, and the grain block. 和光 and 反差黑白 are the only two of
     * the 21 that carry a grain pass, and the 50 MP jpeg is pure noise unless these values are on
     * the request - the professional page fills them in, the patched-in filter feature does not.
     * Values are the professional mode's own snapshot, verbatim.
     */
    private static final Object[][] GRAIN_FILTERS = {
        // type, capture LUT(s), enumType, intensity, colorGrain, grainType,
        // gain amount, colour ratio, highlight, midtones, roughness, shadows, size,
        // blur, highlight opacity, highlight thresh, shadow thresh, shadow opacity
        {"portra400_normal.bin",
         "portra400_hdr_normal_a_1.bin,portra400_hdr_normal_d_1.bin",
         Integer.valueOf(111), Integer.valueOf(0), Integer.valueOf(1), Integer.valueOf(2),
         Float.valueOf(0.36f), Float.valueOf(0.7f), Float.valueOf(0.85f), Float.valueOf(0.9f),
         Float.valueOf(0.33f), Float.valueOf(0.89f), Float.valueOf(0.7f),
         Float.valueOf(2.0f), Float.valueOf(0.2f), Float.valueOf(1.0f),
         Float.valueOf(0.2f), Float.valueOf(0.6f)},
        {"blackandwhite_HC_normal.bin",
         "blackandwhite_HC_hdr_normal.bin",
         Integer.valueOf(115), Integer.valueOf(50), Integer.valueOf(0), Integer.valueOf(2),
         Float.valueOf(0.4f), Float.valueOf(0.0f), Float.valueOf(1.0f), Float.valueOf(0.9f),
         Float.valueOf(0.33f), Float.valueOf(0.88f), Float.valueOf(0.47f),
         Float.valueOf(0.0f), Float.valueOf(0.0f), Float.valueOf(0.0f),
         Float.valueOf(0.0f), Float.valueOf(0.0f)},
    };

    /**
     * Writes a grain filter's capture-side values onto the request: the HDR LUT(s) the merge expects,
     * the enum / intensity that select the right variant, and the grain block without which the
     * grain stage writes noise across the whole frame. Only applies when the LUT(s) are actually on
     * disk; every other filter (`.cube` LUTs, meicam, the plain looks) already comes out clean and is
     * left to the mode's own values.
     */
    private static void masterizeForCapture(Object tag, String type) {
        for (Object[] row : GRAIN_FILTERS) {
            if (!type.equals(row[0])) continue;
            String luts = (String) row[1];
            boolean present = true;
            for (String name : luts.split(",")) {
                if (!new java.io.File(LUT_DIR, name).isFile()) present = false;
            }
            if (!present) return;
            Object[][] fields = {
                {"mFilterType", luts},
                {"mFilterEnumType", row[2]},
                {"mFilterIntensity", row[3]},
                {"mFilterColorGrain", row[4]},
                {"mnFilterGrainType", row[5]},
                {"mbFilterGainAmount", row[6]},
                {"mbFilterGainColorRatio", row[7]},
                {"mbFilterGainHighlight", row[8]},
                {"mbFilterGainMidtones", row[9]},
                {"mbFilterGainRoughness", row[10]},
                {"mbFilterGainShadows", row[11]},
                {"mbFilterGainSize", row[12]},
                {"mfFilterBlurRadius", row[13]},
                {"mfFilterHighlightOpacity", row[14]},
                {"mfFilterHighlightThresh", row[15]},
                {"mfFilterShadowThresh", row[16]},
                {"mfFilterrShadowOpacity", row[17]},
            };
            int written = 0;
            for (Object[] pair : fields) {
                try {
                    XposedHelpers.setObjectField(tag, (String) pair[0], pair[1]);
                    written++;
                } catch (Throwable ignored) {
                    // a field the tag does not carry keeps its own value
                }
            }
            if (seen.add("grain:" + type)) {
                Log.i(TAG, "grain filter rewritten for the capture: " + luts
                    + " (" + written + "/" + fields.length + " fields)");
            }
            return;
        }
    }

    /** The camera's own id for a filter type, or null when the capture side has no entry for it. */
    private static Integer captureId(String type) {
        try {
            ClassLoader loader = presenter == null ? null : presenter.getClass().getClassLoader();
            Class<?> helper = XposedHelpers.findClass("com.oplus.camera.filter.FilterHelper", loader);
            @SuppressWarnings("unchecked")
            java.util.Map<String, Integer> ids = (java.util.Map<String, Integer>)
                XposedHelpers.getStaticObjectField(helper, "sFilterPathConvertMap");
            return ids == null ? null : ids.get(type);
        } catch (Throwable error) {
            return null;
        }
    }

    /** Whether the camera's capture filter map knows this filter type. */
    private static boolean hasCaptureId(String type) {
        try {
            ClassLoader loader = presenter == null ? null
                : presenter.getClass().getClassLoader();
            Class<?> helper = XposedHelpers.findClass("com.oplus.camera.filter.FilterHelper", loader);
            @SuppressWarnings("unchecked")
            java.util.Map<String, Integer> ids = (java.util.Map<String, Integer>)
                XposedHelpers.getStaticObjectField(helper, "sFilterPathConvertMap");
            boolean known = ids != null && ids.get(type) != null;
            if (!known && seen.add("noId:" + type)) {
                Log.i(TAG, "no capture ID for " + type + ", kept to the preview");
            }
            return known;
        } catch (Throwable error) {
            // Unreadable map: keep the previous behaviour rather than silently dropping the filter.
            if (seen.add("idCheck")) Log.i(TAG, "capture ID lookup failed: " + error);
            return true;
        }
    }

    /** The filter the mode is on, or null. */
    public static String currentType() {
        int index = current;
        return index >= 0 && index < types.size() ? types.get(index) : null;
    }

    /** A one-line state for the palette log. */
    public static String describe() {
        return presenter == null ? "no presenter for this mode"
            : types.size() + " filters, current " + current;
    }

    /** Whether the mode has a filter presenter to drive. */
    public static boolean available() {
        return presenter != null && !types.isEmpty();
    }

    /** Index of the filter the mode is using, or -1. */
    public static int currentIndex() {
        return current;
    }

    /**
     * Picks a filter through the camera's own presenter, the way the stock strip does. Returns false
     * when the mode has no presenter (the caller then leaves the row out).
     */
    public static boolean select(String type) {
        Object presenter = HighPixelFilters.presenter;
        if (presenter == null) return false;
        int index = types.indexOf(type);
        if (index < 0) return false;
        // applyFilter reports the pick back to the camera's own strip handlers, which can call back
        // into this method; without the guard one swipe walked three filters (17,18,19,20 in the log).
        if (applying) return true;
        applying = true;
        try {
            return apply(symbols(), presenter, index, type);
        } finally {
            applying = false;
        }
    }

    private static Symbols symbols() {
        return Symbols.get();
    }

    private static boolean apply(Symbols symbols, Object presenter, int index, String type) {
        // select() records the index; apply(index, softLight, style, group) is what pushes the look
        // to the preview request and the capture, and this mode has no strip to call it. Calling
        // applyFilter as well re-enters the camera's own strip handler and ends up drawing the
        // filter twice (measured on the X8U: the second pass dropped the look), so it is not used.
        try {
            symbols.call("FilterPresenter.select", presenter, Integer.valueOf(index));
            current = index;
            lastPickAt = System.currentTimeMillis();
            Log.i(TAG, "selected " + type + " at " + index);
        } catch (Throwable error) {
            Log.i(TAG, "select " + type + " failed: " + error);
            return false;
        }
        // select()/applyFilter() only record the index; the filter reaches the preview and the
        // capture through apply(index, softLight, style, group), which the camera's own strip calls
        // and this mode never does (it has no strip). Same call the video page's filter needs at
        // 4K/60fps.
        try {
            symbols.call("FilterPresenter.apply", presenter, Integer.valueOf(index),
                symbols.call("FilterPresenter.softLightIndex", presenter),
                symbols.call("FilterPresenter.styleIndex", presenter),
                symbols.call("FilterPresenter.groupIndex", presenter));
            symbols.call("FeaturePresenter.resetState", presenter);
            Log.i(TAG, "filter applied at " + index);
        } catch (Throwable error) {
            Log.i(TAG, "apply " + type + " failed: " + error);
        }
        return true;
    }

    /**
     * The panel photo mode shows under its palette: one fixed card with the filter's name on the
     * black band and its label on the white one, and five dots under it. The card and the dots keep
     * their place - the filter changes by swiping the picture (MasterPalette hands those swipes to
     * {@link #swipe}), and the card's text, the dots sliding and the fade are the only motion, the
     * same way the camera's own strip behaves. Sized to the camera's card: 42x26dp, name 12sp,
     * label 9sp. Null when the mode has no presenter or the 哈苏超清 effects switch is off.
     */
    public static View panel(Activity activity, Runnable onPicked) {
        if (presenter == null) ensurePresenter();
        if (presenter != null) read(Symbols.get());
        if (!available() || !Settings.get(ModuleSettings.HIGH_PIXEL_EFFECTS)) return null;
        final List<String> list = types;
        final List<String> label = labels(activity, list);
        final int cardW = dp(activity, 46);
        final int cardH = dp(activity, 28);
        final int dotSize = dp(activity, 4);
        final int dotGap = dp(activity, 4);
        final int dotCount = 5;
        final int dotSlot = dotSize + 2 * dotGap;
        // One dot cell each side of the five on screen: when the window scrolls, the dot leaving
        // the left edge has somewhere to go and the new one comes in from the right.
        final int cells = dotCount + 2;

        final TextView nameView = new TextView(activity);
        final TextView traitView = new TextView(activity);
        final LinearLayout card = card(activity, nameView, traitView, cardW, cardH);

        final List<View> dots = new ArrayList<>();
        final LinearLayout dotStrip = new LinearLayout(activity);
        dotStrip.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < cells; i++) {
            View dot = new View(activity);
            LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dotSize, dotSize);
            dotParams.leftMargin = dotGap;
            dotParams.rightMargin = dotGap;
            dotStrip.addView(dot, dotParams);
            dots.add(dot);
        }
        // The strip is a cell wider than the window on both sides; the window shows cells 1..5.
        final FrameLayout dotWindow = new FrameLayout(activity);
        dotWindow.setClipChildren(true);
        dotWindow.addView(dotStrip, new FrameLayout.LayoutParams(cells * dotSlot, dotSize));

        LinearLayout block = new LinearLayout(activity);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setGravity(Gravity.CENTER_HORIZONTAL);
        block.addView(card, new LinearLayout.LayoutParams(cardW, cardH));
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dotCount * dotSlot, dotSize);
        dotParams.topMargin = dp(activity, 10);
        block.addView(dotWindow, dotParams);
        dotStrip.setTranslationX(-dotSlot);

        final Runnable texts = () -> {
            int index = Math.max(0, Math.min(list.size() - 1, current));
            nameView.setText(index < label.size() ? label.get(index) : list.get(index));
            traitView.setText(trait(list.get(index)));
        };
        final Runnable paintDots = () -> refreshDots(dots, list.size(), dotCount);
        panelRefresh = () -> {
            texts.run();
            paintDots.run();
        };
        panelRefresh.run();

        // Switching animates the way the camera's strip does: the card stays put and only fades
        // round the new text; the dots scroll one slot when the five-dot window moves (the dot on
        // the left edge leaves, a new one comes in on the right), and just re-tint when it does not.
        panelSwipe = direction -> {
            card.animate().cancel();
            card.animate().alpha(0.3f).setDuration(90)
                .withEndAction(() -> {
                    texts.run();
                    card.animate().alpha(1f).setDuration(150).start();
                }).start();
            dotStrip.animate().cancel();
            if (windowMoves(direction, list.size(), dotCount)) {
                dotStrip.animate().translationX(-dotSlot - direction * (float) dotSlot).setDuration(150)
                    .withEndAction(() -> {
                        paintDots.run();
                        dotStrip.setTranslationX(-dotSlot);
                    }).start();
            } else {
                dotStrip.postDelayed(paintDots, 60);
            }
        };

        if (seen.add("barLog")) {
            StringBuilder pairs = new StringBuilder();
            for (int i = 0; i < list.size() && i < 12; i++) {
                pairs.append(i).append('=').append(list.get(i)).append('/')
                    .append(i < label.size() ? label.get(i) : "?").append(' ');
            }
            Log.i(TAG, "filters: " + pairs);
        }
        Log.i(TAG, "panel built: " + list.size() + " filters, current " + current);
        return block;
    }

    /** Whether moving one filter over scrolls the five-dot window instead of just the highlight. */
    private static boolean windowMoves(int direction, int size, int count) {
        return dotOffset(current, size, count) != dotOffset(current - direction, size, count);
    }

    /** First filter the dot window shows for a selection: the window keeps the selection centred. */
    private static int dotOffset(int index, int size, int count) {
        int safe = Math.max(0, Math.min(size - 1, index));
        return Math.max(0, Math.min(safe - count / 2, Math.max(0, size - count)));
    }

    /** Moves the selection one filter over, the way a horizontal swipe on the picture does. */
    public static boolean swipe(int direction) {
        if (!available()) return false;
        int index = current + direction;
        if (index < 0 || index >= types.size()) return false;
        if (!select(types.get(index))) return false;
        SwipeAnim animate = panelSwipe;
        if (animate != null) {
            animate.run(direction);
        } else {
            Runnable refresh = panelRefresh;
            if (refresh != null) refresh.run();
        }
        return true;
    }

    /** Height of {@link #panel}: the card, the gap and the dot row. */
    public static int panelHeight(Activity activity) {
        return dp(activity, 28) + dp(activity, 10) + dp(activity, 4);
    }

    /** One filter's card: the name on the black band, the label on the white one. */
    private static LinearLayout card(Activity activity, TextView top, TextView bottom,
            int width, int height) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFF0D0D0D);
        background.setCornerRadius(dp(activity, 5));
        card.setBackground(background);
        // Rounds the white band's bottom corners with the card's, as the camera's card does.
        card.setClipToOutline(true);

        top.setTextColor(0xFFFFFFFF);
        top.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        top.setGravity(Gravity.CENTER);
        top.setSingleLine(true);
        top.setEllipsize(android.text.TextUtils.TruncateAt.END);
        top.setBackgroundColor(0xFF0D0D0D);
        top.setPadding(dp(activity, 2), 0, dp(activity, 2), 0);

        bottom.setTextColor(0xFF141414);
        bottom.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 8);
        bottom.setGravity(Gravity.CENTER);
        bottom.setSingleLine(true);
        bottom.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bottom.setBackgroundColor(0xFFF7F7F7);
        bottom.setPadding(dp(activity, 2), 0, dp(activity, 2), 0);

        int topHeight = Math.round(height * 0.60f);
        card.addView(top, new LinearLayout.LayoutParams(width, topHeight));
        card.addView(bottom, new LinearLayout.LayoutParams(width, height - topHeight));
        return card;
    }

    /** The dot strip: five on screen, one spare cell each side; the selection is filled. */
    private static void refreshDots(List<View> dots, int size, int count) {
        int index = Math.max(0, Math.min(size - 1, current));
        int offset = dotOffset(index, size, count);
        for (int i = 0; i < dots.size(); i++) {
            int at = offset + i - 1;
            View dot = dots.get(i);
            dot.setVisibility(at >= 0 && at < size ? View.VISIBLE : View.INVISIBLE);
            GradientDrawable background = new GradientDrawable();
            background.setShape(GradientDrawable.OVAL);
            background.setColor(at == index ? 0xFFFFFFFF : 0x59FFFFFF);
            dot.setBackground(background);
        }
    }

    /**
     * The white band's text: the category label for a filter this module adds (e.g. 胶片模拟), and
     * the camera's own preset label otherwise. The stock filters carry that label in the camera's
     * card, which is not readable from here; 原生质感 is what it prints on this build.
     */
    private static String trait(String type) {
        FilterEntry entry = FilterCatalog.get(FilterScope.NORMAL).find(type);
        if (entry != null && entry.label != null && !entry.label.isEmpty()) return entry.label;
        return "原生质感";
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}

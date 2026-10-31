package local.jiege.hook.photozoom;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.SessionConfiguration;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Settings;
import local.jiege.hook.common.Symbols;
import local.jiege.settings.ModuleSettings;

/**
 * Photo in-sensor zoom: asks the HAL to read a 2x-3x still from the cropped sensor instead of
 * upscaling the whole sensor.
 *
 * <p>The stock chain for stills is already configured. The OEM config keeps the row for the photo
 * opmode ({@code [InsensorZoom] iZoomSettins[0] = 0x8001; 2.0; 2.999999; 0; 2; 5}) and lists the
 * opmode in {@code [EnableIZoomModeList] Mode[0] = 0x8001; 4SatR}, and the extension plugin decides
 * per request in {@code ExtensionPluginBase::IsIZoomRequireForCamera(meta, ?, cameraId,
 * isSnapshotRequest)} (libextensionlayer.so 0xa9eb0), which ANDs three things: the request tag
 * {@code com.oplus.enableIzoom} == 1, an APS sensor mode of {1, 4, 5, 7} (table at 0x40a70), and the
 * caller's snapshot flag.
 *
 * <p>Two things are known from measurement (2026-10-31). The app never sets any of the relevant
 * tags: it carries no {@code enableIzoom} string at all. And the tag itself is reachable from the
 * app - a Key built by name stores and reads back as 1 through the capture request builder, so the
 * vendor tag registry resolves it - but setting it on the still and on the preview requests changes
 * nothing, which is what a session-level property looks like: the sensor readout mode is chosen when
 * the session is configured, not per frame.
 *
 * <p>That is where the HAL's own session parameters come in. Its tag table lists
 * {@code 0x80260018 (EnableInsensorZoom)} and {@code 0x80260019 (EnableSnapshotOnlyInsensorZoom)},
 * both int32, defined in section {@code org.codeaurora.qcamera3.sessionParameters} - a session
 * parameter that says exactly "in-sensor zoom for snapshots". This class puts it into the session
 * parameters at capture-session creation, keeping whatever parameters the app provided, and leaves
 * the per-request tags in place for the ask-the-camera path to have both channels covered.
 *
 * <p>Scope is switchable at runtime through {@code persist.omnicam.photoisz.layer}
 * ({@code both} default, {@code session}, {@code request}) and {@code ...photoisz.mode}
 * ({@code still} default, {@code preview}, {@code all}) so the two channels can be told apart
 * without a rebuild.
 */
public final class PhotoIzoom {
    private static final String TAG = "PhotoIzoom";

    /** Per-request vendor tags of section com.oplus, as registered by the camera service. */
    private static final String ENABLE = "com.oplus.enableIzoom";
    private static final String SNAPSHOT = "com.oplus.IZOOMsnapshot";
    private static final String OPX_ENABLE = "com.oplus.opx.IZOOMenable";
    private static final String[] REQUEST_TAG_NAMES = {ENABLE, SNAPSHOT, OPX_ENABLE};

    /** Session parameters that turn the cropped readout on for snapshots. */
    private static final String SESSION_ENABLE = "EnableInsensorZoom";
    private static final String SESSION_SNAPSHOT_ONLY = "EnableSnapshotOnlyInsensorZoom";
    private static final String[] SESSION_TAG_NAMES = {SESSION_ENABLE, SESSION_SNAPSHOT_ONLY};

    private static final int STILL_CAPTURE = 2;
    private static final int VIDEO_SNAPSHOT = 4;
    private static final int ZERO_SHUTTER_LAG = 5;

    private static final String MODE_PROP = "persist.omnicam.photoisz.mode";
    private static final String LAYER_PROP = "persist.omnicam.photoisz.layer";
    private static final String TAGS_PROP = "persist.omnicam.photoisz.tags";
    /** Shape of the session parameter array, e.g. {@code 0,1,0,0} for the wide camera only. */
    private static final String ARRAY_PROP = "persist.omnicam.photoisz.array";
    /** Installs the hook while the module setting stays off, so an experiment needs no rebuild. */
    private static final String FORCE_PROP = "persist.omnicam.photoisz.force";
    private static final long PROP_TTL_MS = 2000L;

    private static final Map<String, Object> requestKeys = new LinkedHashMap<>();
    private static final Map<String, Object> sessionKeys = new LinkedHashMap<>();
    /** The value shape each tag accepted, so the failed shapes are only tried once per process. */
    private static final Map<String, Object> acceptedValues =
        Collections.synchronizedMap(new LinkedHashMap<String, Object>());
    private static volatile boolean resolved;
    private static volatile boolean verified;

    private static volatile String currentMode = "still";
    private static volatile long modeReadAt;
    private static volatile String currentLayer = "both";
    private static volatile long layerReadAt;
    private static volatile String currentTags = "";
    private static volatile long tagsReadAt;

    private static volatile int tagged;
    private static volatile int builds;
    private static volatile int sessions;
    private static volatile int modeSkipped;
    private static volatile String lastMode = "";
    private static final Set<String> shapes = Collections.synchronizedSet(new HashSet<String>());
    private static final int SHAPE_LOG_LIMIT = 12;

    private PhotoIzoom() {}

    public static void installCamera(ClassLoader classLoader) {
        Log.guard(TAG, "photo in-sensor zoom", () -> install(classLoader));
    }

    private static void install(ClassLoader classLoader) throws Throwable {
        if (!Settings.get(ModuleSettings.PHOTO_IZOOM) && !"1".equals(prop(FORCE_PROP).trim())) {
            Log.i(TAG, "off (set " + FORCE_PROP + "=1 to install it for an experiment)");
            return;
        }
        // The keys carry the tag ids, and those only come from the camera's characteristics.
        XC_MethodHook capture = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.getResult() instanceof CameraCharacteristics) {
                    resolveKeys((CameraCharacteristics) param.getResult());
                }
            }
        };
        XposedBridge.hookAllMethods(CameraManager.class, "getCameraCharacteristics", capture);
        XposedBridge.hookAllMethods(CameraManager.class, "getCameraCharacteristicsMap", capture);

        XposedBridge.hookAllMethods(CaptureRequest.Builder.class, "build", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    onBuild((CaptureRequest.Builder) param.thisObject);
                } catch (Throwable error) {
                    Log.e(TAG, "request tag failed", error);
                }
            }
        });

        hookMode();
        hookSessions(classLoader);
        Log.i(TAG, "installed, waiting for the camera characteristics");
    }

    /**
     * Tracks the mode the camera is in. A session the app builds for a video mode must keep the
     * parameters it had (none) - the preview template a request is built from would carry its
     * defaults into that session, and video is the one place the ISZ combinations are known to be
     * fragile on this device.
     */
    private static void hookMode() {
        try {
            Symbols symbols = Symbols.get();
            XposedBridge.hookMethod(symbols.method("Zoom.updateZoom"), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Object name;
                    try {
                        name = symbols.get("FeaturePresenter.modeName", param.thisObject);
                    } catch (Throwable error) {
                        return;
                    }
                    if (name != null) lastMode = String.valueOf(name);
                }
            });
        } catch (Throwable error) {
            Log.i(TAG, "mode tracking unavailable: " + error);
        }
    }

    /**
     * Whether the camera is in a still mode. The very first session is created before the zoom
     * presenter has reported a mode (measured 2026-10-31: "mode unknown" on the opening session),
     * so an unknown mode is treated as a still - the photo page is what opens first - and the mode
     * is logged as soon as it is known.
     */
    private static boolean photoMode() {
        String mode = lastMode;
        if (mode == null || mode.isEmpty()) {
            if (modeSkipped++ == 0) Log.i(TAG, "mode unknown yet, treating the session as a still");
            return true;
        }
        if (mode.endsWith("Video") || "professional".equals(mode)) return false;
        return true;
    }

    /**
     * Hooks the session creation. The app uses the legacy {@code createCaptureSession(List,
     * StateCallback, Handler)} overload (measured 2026-10-31), which carries no parameters at all,
     * and both public overloads funnel into the private
     * {@code createCaptureSessionInternal(..., CaptureRequest sessionParams)}; that is the one place
     * the parameters can be supplied without replacing the call the app made.
     */
    private static void hookSessions(ClassLoader classLoader) {
        try {
            Class<?> impl = XposedHelpers.findClass(
                "android.hardware.camera2.impl.CameraDeviceImpl", classLoader);
            int hooked = 0;
            for (Method method : impl.getDeclaredMethods()) {
                if ("createCaptureSessionInternal".equals(method.getName())) {
                    Class<?>[] types = method.getParameterTypes();
                    if (types.length == 0) continue;
                    if (!CaptureRequest.class.equals(types[types.length - 1])) {
                        Log.i(TAG, "createCaptureSessionInternal has no parameter slot ("
                            + types.length + " args)");
                        continue;
                    }
                    final int slot = types.length - 1;
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                onSessionParameters(param, slot);
                            } catch (Throwable error) {
                                Log.e(TAG, "session parameters failed", error);
                            }
                        }
                    });
                    hooked++;
                    continue;
                }
                if (!"createCaptureSession".equals(method.getName())) continue;
                Class<?>[] types = method.getParameterTypes();
                if (types.length > 0 && SessionConfiguration.class.isAssignableFrom(types[0])) {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                onSessionConfiguration(param);
                            } catch (Throwable error) {
                                Log.e(TAG, "session parameters failed", error);
                            }
                        }
                    });
                    hooked++;
                }
            }
            Log.i(TAG, "session hooks: " + hooked);
        } catch (Throwable error) {
            Log.i(TAG, "no session hook: " + error);
        }
    }

    /**
     * The parameters path for the legacy overload: the app passes none, so a request is built from
     * the preview template and handed to the same call. That template request would also carry the
     * preview defaults into a session the app never described, so the injection stays inside photo
     * modes - a video session keeps the parameters it had (none).
     */
    private static void onSessionParameters(XC_MethodHook.MethodHookParam param, int slot)
            throws Throwable {
        if (sessionKeys.isEmpty()) return;
        if (!"both".equals(layer()) && !"session".equals(layer())) return;
        if (!photoMode()) return;
        CameraDevice device = (CameraDevice) param.thisObject;
        CaptureRequest.Builder builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        CaptureRequest previous = param.args[slot] instanceof CaptureRequest
            ? (CaptureRequest) param.args[slot] : null;
        if (previous != null) {
            for (CaptureRequest.Key<?> key : previous.getKeys()) {
                copyKey(previous, builder, key);
            }
        }
        int applied = 0;
        for (Map.Entry<String, Object> entry : sessionKeys.entrySet()) {
            if (!wanted(entry.getKey())) continue;
            if (injectSession(builder, entry.getValue(), entry.getKey())) applied++;
        }
        if (applied == 0) return;
        param.args[slot] = builder.build();
        sessions++;
        Log.i(TAG, "session parameters set (" + applied + " keys = "
            + java.util.Arrays.toString(sessionArray()) + ", mode " + lastMode + ", kept "
            + (previous == null ? 0 : previous.getKeys().size()) + ", session " + sessions + ")");
    }

    /**
     * Puts the two session parameters into the configuration the app is about to hand to the
     * camera, copying the parameters it already set so nothing of its own is lost.
     */
    private static void onSessionConfiguration(XC_MethodHook.MethodHookParam param) throws Throwable {
        if (sessionKeys.isEmpty()) return;
        if (!"both".equals(layer()) && !"session".equals(layer())) return;
        if (!photoMode()) return;
        SessionConfiguration original = (SessionConfiguration) param.args[0];
        CameraDevice device = (CameraDevice) param.thisObject;

        CaptureRequest.Builder builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        CaptureRequest previous = existingParameters(original);
        if (previous != null) {
            for (CaptureRequest.Key<?> key : previous.getKeys()) {
                copyKey(previous, builder, key);
            }
        }
        int applied = 0;
        for (Map.Entry<String, Object> entry : sessionKeys.entrySet()) {
            if (!wanted(entry.getKey())) continue;
            if (injectSession(builder, entry.getValue(), entry.getKey())) applied++;
        }
        if (applied == 0) return;

        SessionConfiguration replacement = new SessionConfiguration(
            original.getSessionType(), original.getOutputConfigurations(),
            original.getExecutor(), original.getStateCallback());
        replacement.setSessionParameters(builder.build());
        param.args[0] = replacement;
        sessions++;
        Log.i(TAG, "session parameters set (" + applied + " keys = "
            + java.util.Arrays.toString(sessionArray()) + ", mode " + lastMode + ", kept "
            + (previous == null ? 0 : previous.getKeys().size()) + ", session " + sessions + ")");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void copyKey(CaptureRequest previous, CaptureRequest.Builder builder,
            CaptureRequest.Key key) {
        try {
            Object value = previous.get(key);
            if (value != null) builder.set(key, value);
        } catch (Throwable error) {
            // A key that cannot be re-set is one the session does not need from us.
            Log.i(TAG, "session parameter not carried over: " + key + " (" + error + ")");
        }
    }

    /**
     * The parameters already on the configuration. There is no public getter, so the field holding
     * the request is looked up by type - the class keeps exactly one.
     */
    private static CaptureRequest existingParameters(SessionConfiguration configuration) {
        for (Field field : SessionConfiguration.class.getDeclaredFields()) {
            if (!CaptureRequest.class.equals(field.getType())) continue;
            try {
                field.setAccessible(true);
                return (CaptureRequest) field.get(configuration);
            } catch (Throwable error) {
                Log.i(TAG, "session parameters unreadable: " + error);
                return null;
            }
        }
        return null;
    }

    /**
     * Builds a Key per tag: from the keys the camera advertises when it lists them, otherwise by
     * name. The per-request tags are not advertised (measured: 128 keys, none of them) and are
     * still reachable, so both paths are kept.
     */
    private static void resolveKeys(CameraCharacteristics characteristics) {
        if (resolved) return;
        resolved = true;
        Set<String> request = new HashSet<>();
        Set<String> session = new HashSet<>();
        try {
            for (CaptureRequest.Key<?> key : characteristics.getAvailableCaptureRequestKeys()) {
                if (key.getName() != null) request.add(key.getName());
            }
        } catch (Throwable error) {
            Log.e(TAG, "request keys unreadable", error);
        }
        try {
            for (CaptureRequest.Key<?> key : characteristics.getAvailableSessionKeys()) {
                if (key.getName() != null) session.add(key.getName());
            }
        } catch (Throwable error) {
            Log.e(TAG, "session keys unreadable", error);
        }
        for (String name : REQUEST_TAG_NAMES) {
            Object key = request.contains(name) ? findByName(characteristics, name) : buildKey(name);
            if (key != null) requestKeys.put(name, key);
        }
        StringBuilder report = new StringBuilder("request tags:");
        for (String name : REQUEST_TAG_NAMES) {
            report.append(' ').append(shortName(name)).append('=')
                .append(requestKeys.containsKey(name)
                    ? (request.contains(name) ? "advertised" : "by-name") : "no");
        }
        Log.i(TAG, report.toString());

        // Session keys arrive section-qualified - org.codeaurora.qcamera3.sessionParameters.
        // EnableSnapshotOnlyInsensorZoom - so they are matched on the suffix and stored under the
        // bare name.
        for (String name : SESSION_TAG_NAMES) {
            String offered = null;
            for (String candidate : session) {
                if (candidate.equals(name) || candidate.endsWith("." + name)) {
                    offered = candidate;
                    break;
                }
            }
            if (offered == null) continue;
            Object key = findByName(characteristics, offered);
            if (key != null) sessionKeys.put(name, key);
        }
        Log.i(TAG, "session keys: offered " + session.size() + ", matched " + sessionKeys.keySet()
            + " of " + java.util.Arrays.toString(SESSION_TAG_NAMES));
        // What the camera does offer, for the case none matched: the names are the map.
        if (sessionKeys.isEmpty()) {
            StringBuilder names = new StringBuilder();
            for (String name : session) {
                if (name.toLowerCase(Locale.ROOT).contains("insensor")
                        || name.toLowerCase(Locale.ROOT).contains("session")) {
                    names.append(' ').append(name);
                }
            }
            Log.i(TAG, "session keys mentioning sensor/session:" + names);
        }
    }

    private static Object findByName(CameraCharacteristics characteristics, String name) {
        try {
            for (CaptureRequest.Key<?> key : characteristics.getAvailableCaptureRequestKeys()) {
                if (name.equals(key.getName())) return key;
            }
        } catch (Throwable ignored) {
            // fall through to the session list
        }
        try {
            for (CaptureRequest.Key<?> key : characteristics.getAvailableSessionKeys()) {
                if (name.equals(key.getName())) return key;
            }
        } catch (Throwable ignored) {
            // no session keys either
        }
        return null;
    }

    private static Object buildKey(String name) {
        try {
            Class<?> keyType = Class.forName("android.hardware.camera2.CaptureRequest$Key");
            Constructor<?> ctor = keyType.getDeclaredConstructor(String.class, Class.class);
            ctor.setAccessible(true);
            return ctor.newInstance(name, Integer.class);
        } catch (Throwable error) {
            Log.i(TAG, "no key for " + name + ": " + error);
            return null;
        }
    }

    private static void onBuild(CaptureRequest.Builder builder) {
        if (requestKeys.isEmpty()) return;
        if (!"both".equals(layer()) && !"request".equals(layer())) return;
        Boolean still = stillRequest(builder);
        noteShape(builder, still);
        boolean isStill = Boolean.TRUE.equals(still);
        String mode = mode();
        boolean want = "all".equals(mode) || ("preview".equals(mode) ? !isStill : isStill);
        if (!want) return;
        builds++;
        int applied = 0;
        for (Map.Entry<String, Object> entry : requestKeys.entrySet()) {
            if (!wanted(entry.getKey())) continue;
            if (inject(builder, entry.getValue(), entry.getKey())) applied++;
        }
        if (applied == 0) return;
        tagged++;
        if (!verified) verify(builder);
        if (tagged == 1 || tagged == 5 || tagged % 200 == 0) {
            Log.i(TAG, "tagged " + tagged + " requests (" + mode + ", of " + builds + ")");
        }
    }

    /**
     * Reads a tag back off the builder it was just written to: a vendor Key the camera does not
     * know resolves to nothing and reads back null. That is the one signal that separates "the app
     * cannot reach this tag" from "the tag arrives and the HAL still declines".
     */
    private static void verify(CaptureRequest.Builder builder) {
        for (Map.Entry<String, Object> entry : requestKeys.entrySet()) {
            if (!wanted(entry.getKey())) continue;
            try {
                Object value = XposedHelpers.callMethod(builder, "get", entry.getValue());
                Log.i(TAG, "read back " + shortName(entry.getKey()) + " = " + value
                    + (value == null ? " (not stored - the camera does not know this tag)" : ""));
            } catch (Throwable error) {
                Log.i(TAG, "read back " + shortName(entry.getKey()) + " failed: " + error);
            }
        }
        verified = true;
    }

    /**
     * The value to write for a key, in the shape its tag declares. Camera2 keys carry no readable
     * type on this build ({@code CaptureRequest$Key#getType} does not exist), and the camera answers
     * a scalar with "Not an array" for the session parameters, so the shapes are tried in turn and
     * the one that the camera accepts is remembered per tag.
     */
    private static final Object[] CANDIDATE_VALUES = {
        new int[] {1}, Integer.valueOf(1), new byte[] {1}, new long[] {1L}, new float[] {1f},
    };

    private static boolean inject(CaptureRequest.Builder builder, Object key, String name) {
        Object known = acceptedValues.get(name);
        if (known != null) {
            try {
                XposedHelpers.callMethod(builder, "set", key, known);
                return true;
            } catch (Throwable error) {
                acceptedValues.remove(name);
            }
        }
        Throwable last = null;
        for (Object candidate : CANDIDATE_VALUES) {
            try {
                XposedHelpers.callMethod(builder, "set", key, candidate);
                acceptedValues.put(name, candidate);
                Log.i(TAG, shortName(name) + " takes " + candidate.getClass().getSimpleName());
                return true;
            } catch (Throwable error) {
                last = error;
            }
        }
        Log.i(TAG, "camera refused " + shortName(name) + ": " + last);
        return false;
    }

    /**
     * A session parameter takes an int array and the camera gives no clue what the elements mean.
     * The config describes the cameras in the order ultra-wide, wide, tele, ultra-tele
     * ({@code MarginStartRatio = 0; 2.0; 0; 2.0}), so a per-camera array is the likely shape and
     * {@code {1}} would be setting the ultra-wide, whose ISZ start ratio is 0 - i.e. nothing. The
     * shape is therefore settable ({@code persist.omnicam.photoisz.array}, e.g. {@code 0,1,0,0})
     * instead of being guessed once at build time.
     */
    private static boolean injectSession(CaptureRequest.Builder builder, Object key, String name) {
        int[] value = sessionArray();
        try {
            XposedHelpers.callMethod(builder, "set", key, value);
            return true;
        } catch (Throwable error) {
            // Fall back to the scalar shapes, in case the tag is not an array after all.
            return inject(builder, key, name);
        }
    }

    private static int[] sessionArray() {
        String raw = prop(ARRAY_PROP).trim();
        if (raw.isEmpty()) return new int[] {1};
        String[] parts = raw.split(",");
        int[] values = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                values[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException error) {
                values[i] = 1;
            }
        }
        return values;
    }

    /**
     * A still capture, as opposed to the repeating preview request. The capture intent decides it
     * when the app sets one - preview carries intent 1 and stills carry 5 (zero shutter lag) here -
     * and the JPEG keys are only consulted when no intent is set, because the preview request
     * carries them on this build as well.
     */
    private static Boolean stillRequest(CaptureRequest.Builder builder) {
        Object intent = builder.get(CaptureRequest.CONTROL_CAPTURE_INTENT);
        int value = intent instanceof Integer ? (Integer) intent : -1;
        if (value >= 0) {
            return value == STILL_CAPTURE || value == VIDEO_SNAPSHOT || value == ZERO_SHUTTER_LAG;
        }
        return builder.get(CaptureRequest.JPEG_ORIENTATION) != null
            || builder.get(CaptureRequest.JPEG_QUALITY) != null;
    }

    private static void noteShape(CaptureRequest.Builder builder, Boolean still) {
        if (shapes.size() >= SHAPE_LOG_LIMIT) return;
        String shape = "intent=" + builder.get(CaptureRequest.CONTROL_CAPTURE_INTENT)
            + " jpeg=" + (builder.get(CaptureRequest.JPEG_ORIENTATION) != null)
            + " still=" + still;
        if (shapes.add(shape)) Log.i(TAG, "request " + shape);
    }

    private static String mode() {
        long now = System.currentTimeMillis();
        if (now - modeReadAt >= PROP_TTL_MS) {
            modeReadAt = now;
            String value = prop(MODE_PROP).trim().toLowerCase(Locale.ROOT);
            currentMode = "all".equals(value) || "preview".equals(value) ? value : "still";
        }
        return currentMode;
    }

    private static String layer() {
        long now = System.currentTimeMillis();
        if (now - layerReadAt >= PROP_TTL_MS) {
            layerReadAt = now;
            String value = prop(LAYER_PROP).trim().toLowerCase(Locale.ROOT);
            currentLayer = "request".equals(value) || "session".equals(value) ? value : "both";
        }
        return currentLayer;
    }

    /** Whether the given tag is in the current selection ({@code persist.omnicam.photoisz.tags}). */
    private static boolean wanted(String name) {
        long now = System.currentTimeMillis();
        if (now - tagsReadAt >= PROP_TTL_MS) {
            tagsReadAt = now;
            currentTags = prop(TAGS_PROP).trim();
        }
        if (currentTags.isEmpty()) return true;
        if ("-".equals(currentTags)) return false;
        for (String part : currentTags.split(",")) {
            if (name.equals(part.trim()) || shortName(name).equals(part.trim())) return true;
        }
        return false;
    }

    private static String shortName(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }

    private static String prop(String name) {
        try {
            Class<?> type = Class.forName("android.os.SystemProperties");
            Object value = type.getMethod("get", String.class, String.class).invoke(null, name, "");
            return String.valueOf(value);
        } catch (Throwable error) {
            return "";
        }
    }
}

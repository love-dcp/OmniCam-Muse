package local.jiege.hook.palette;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Symbols;

/**
 * Gallery process: which photos the photo editor runs in palette mode (the "色彩风格" entry).
 *
 * The gallery decides palette mode once for the whole app (isColorPaletteMode, a lazy value). The
 * value is replaced by one that also asks the photo being edited: any photo that carries a
 * filter.info (every camera capture with a filter applied) gets palette mode, everything else
 * (screenshots, downloads) keeps the stock filter editor. That makes the entry consistent across
 * camera photos; the older version-5-only split sent non-palette captures to the stock filter
 * editor, where the module's master styles never show.
 *
 * IMPORTANT (X8U): the palette pipeline cannot re-render from src.image on this device. X8U
 * captures store src.image as a plain JPEG (X10 uses a private format plus basictone.info /
 * master.mode.preset.info meta), and the engine fails the meta read ("resolvePaletteSrcOrientation
 * invalid metaIndex=0" in libAlgoInterface filterProcessMeisheV2), which garbles the BasicTone
 * stage output. GalleryColorPalette therefore bypasses that stage and lets the selected filter
 * apply straight onto the rendered image; capturedFilter keeps the editor's automatic
 * re-selection of the photo's own style from double-applying.
 */
final class GalleryPaletteScope {
    private static final String TAG = "GalleryPaletteScope";
    private static final String EDITOR = "com.oplus.gallery.pictureeditorpage.PhotoEditorActivity";
    private static final String CONTAINER = "com.oplus.gallery.foundation.fileaccess.extension.FileExtendedContainer";
    private static final String OPEN_MODE = "com.oplus.gallery.foundation.fileaccess.extension.OpenFileMode";
    private static final String FILTER_INFO = "filter.info";

    /**
     * The filter the photo was captured with (filter.info +28, e.g. "hu_po.bin"), or null. The
     * palette re-render must not apply it again: the capture already has it baked in, and this
     * device's pipeline cannot start from src.image (see the class comment), so the SDK hooks use
     * this to keep the editor's automatic re-selection of the captured style a no-op.
     */
    public static volatile String capturedFilter;

    /**
     * True when the photo being edited carries a src.image block. The gallery then feeds the
     * palette pipeline the decoded source (un-toned, sensor-oriented) instead of the finished
     * JPEG; GalleryColorPalette swaps that input for the rendered photo (see capturedMainDecode).
     */
    public static volatile boolean srcImagePresent;

    /** The src.image block's byte length, so the decode hook can pick exactly that decode. */
    public static volatile int srcImageLength;

    /** The raw src.image bytes as read at editor open — the decode hooks match against these. */
    public static volatile byte[] srcBytes;

    /** The src decode's stored shape, before any rotation. */
    public static volatile int srcShapeW, srcShapeH;

    /** True once the container hook has re-encoded this session's block upright. */
    public static volatile boolean containerRotated;

    /** Whether the finished photo displays portrait — the src decode must be rotated to match. */
    public static volatile boolean mainPortrait;

    /** The edited photo's media URI, from the same editor-open read as capturedFilter. */
    private static volatile String editedUri;
    /** One decoded main image at a time: (uri, size) -> upright bitmap in the pipeline's shape. */
    private static android.graphics.Bitmap mainCache;
    private static String mainCacheKey;

    /** The cached upright main decode, for render paths that bypass the pipeline input. */
    public static android.graphics.Bitmap cachedMain() {
        synchronized (GalleryPaletteScope.class) {
            return mainCache != null && !mainCache.isRecycled() ? mainCache : null;
        }
    }

    private GalleryPaletteScope() {}

    static void install(ClassLoader classLoader) {
        try {
            Symbols symbols = Symbols.get();
            if (!symbols.has("ColorPaletteMode.lazy")) {
                Log.i(TAG, "palette mode symbol missing");
                return;
            }
            Field field = symbols.field("ColorPaletteMode.lazy");
            Object base = field.get(null);
            Class<?> lazy = field.getType();
            InvocationHandler handler = (proxy, method, args) -> {
                Object value = method.invoke(base, args);
                if ("getValue".equals(method.getName())) return Boolean.TRUE.equals(value);
                return value;
            };
            field.set(null, Proxy.newProxyInstance(lazy.getClassLoader(), new Class<?>[] {lazy}, handler));
            XposedHelpers.findAndHookMethod(EDITOR, classLoader, "onCreate", android.os.Bundle.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    Activity activity = (Activity) param.thisObject;
                    readCapturedFilter(activity, editedImage(activity.getIntent()), classLoader);
                }
            });
            Log.i(TAG, "installed");
        } catch (Throwable error) {
            Log.e(TAG, "palette scope hook failed", error);
        }
    }

    /** The image the editor opens (InputArgumentsVM): editor_image_uri when started from a photo, else the intent data. */
    private static Uri editedImage(Intent intent) {
        if (intent == null) return null;
        if (intent.getBooleanExtra("editor_from_photo", false)) {
            String uri = intent.getStringExtra("editor_image_uri");
            return uri == null ? null : Uri.parse(uri);
        }
        return intent.getData();
    }

    private static void readCapturedFilter(Activity activity, Uri uri, ClassLoader classLoader) {
        editedUri = uri == null ? null : uri.toString();
        srcImagePresent = false;
        srcImageLength = 0;
        srcBytes = null;
        srcShapeW = 0;
        srcShapeH = 0;
        containerRotated = false;
        mainPortrait = false;
        dropMainCache();
        if (uri == null) { capturedFilter = null; return; }
        Object container = null;
        try {
            container = XposedHelpers.newInstance(XposedHelpers.findClass(CONTAINER, classLoader));
            Object read = XposedHelpers.getStaticObjectField(XposedHelpers.findClass(OPEN_MODE, classLoader), "MODE_READ");
            if (!(Boolean) XposedHelpers.callMethod(container, "setDataSource", activity, uri, read)) {
                capturedFilter = null;
                return;
            }
            byte[] info = (byte[]) XposedHelpers.callMethod(container, "getExtensionData", FILTER_INFO);
            // Every photo opens in palette mode, so the 色彩风格 entry is consistent (the stock
            // filter editor only offered the two normal-scope module filters). The palette
            // pipeline itself cannot re-render from src.image on this device (the native meta
            // read fails, metaIndex=0), so GalleryColorPalette bypasses the BasicTone stage and
            // applies the selected filter straight onto the rendered image instead.
            capturedFilter = info != null && info.length >= 44 ? filterId(info) : null;
            Log.i(TAG, "editing " + uri + (capturedFilter == null ? "" : ", captured " + capturedFilter));
            dumpExtensions(container, uri, info);
            readMainShape(uri);
        } catch (Throwable error) {
            capturedFilter = null;
            Log.e(TAG, "filter.info read failed for " + uri, error);
        } finally {
            if (container != null) {
                try {
                    XposedHelpers.callMethod(container, "close");
                } catch (Throwable ignored) {
                    // closing a container that failed to open
                }
            }
        }
    }

    /**
     * The filter LUT name stored at filter.info +28 (NUL-terminated ASCII, preceded by a flag
     * word at +24), or null. Longer than 36 bytes for generated names, so scan to +64.
     */
    private static String filterId(byte[] info) {
        int start = 28, end = Math.min(info.length, 64);
        if (start >= end || info[start] == 0) return null;
        for (int i = start; i < end; i++) {
            if (info[i] == 0) {
                try { return new String(info, start, i - start, "US-ASCII"); } catch (Exception ignored) { return null; }
            }
        }
        return null;
    }

    /**
     * One line per editor open: which extension blocks the photo carries and the decrypted
     * filter.info bytes. The container payloads are encrypted on disk, so this (through
     * FileExtendedContainer) is the only way to see the real values; it exists to diagnose the
     * palette metaIndex gap (X8U captures vs X10 reference photos).
     */
    private static void dumpExtensions(Object container, Uri uri, byte[] info) {
        try {
            StringBuilder sb = new StringBuilder("extdump ").append(uri.getLastPathSegment());
            for (String name : new String[] {"filter.info", "src.image", "basictone.info",
                    "master.mode.preset.info", "basictone.vig.table", "basictone.lmtlut.table", "fb.param"}) {
                try {
                    byte[] data = (byte[]) XposedHelpers.callMethod(container, "getExtensionData", name);
                    if ("src.image".equals(name)) {
                        srcImagePresent = data != null;
                        srcImageLength = data == null ? 0 : data.length;
                        srcBytes = data;
                        if (data != null && data.length > 0) {
                            // what the gallery decoder will see, before any rotation decision
                            android.graphics.BitmapFactory.Options shape =
                                new android.graphics.BitmapFactory.Options();
                            shape.inJustDecodeBounds = true;
                            android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, shape);
                            srcShapeW = shape.outWidth;
                            srcShapeH = shape.outHeight;
                            int orientation = 1;
                            try {
                                orientation = new android.media.ExifInterface(
                                    new java.io.ByteArrayInputStream(data))
                                    .getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1);
                            } catch (Throwable ignored) {
                                // not a container ExifInterface can parse
                            }
                            sb.append(" shape=").append(shape.outWidth).append('x').append(shape.outHeight)
                              .append(" exif=").append(orientation);
                        }
                    }
                    sb.append(' ').append(name).append('=').append(data == null ? "null" : data.length);
                    if ("src.image".equals(name) && data != null && data.length >= 16) {
                        StringBuilder head = new StringBuilder();
                        for (int i = 0; i < 16; i++) head.append(String.format("%02x", data[i]));
                        sb.append(" jpeg=").append(data[0] == (byte) 0xFF && data[1] == (byte) 0xD8)
                          .append(" head=").append(head);
                    }
                } catch (Throwable ignored) {
                    // a block this gallery build cannot read
                }
            }
            Log.i(TAG, sb.toString());
            if (info != null) {
                StringBuilder hex = new StringBuilder("filterinfo-hex len=").append(info.length).append(' ');
                for (int i = 0; i < Math.min(info.length, 216); i++) hex.append(String.format("%02x", info[i]));
                Log.i(TAG, hex.toString());
            }
        } catch (Throwable ignored) {
            // diagnostics only
        }
    }

    /** Drops the cached main-image decode; called on every editor open, before the photo is known. */
    private static void dropMainCache() {
        synchronized (GalleryPaletteScope.class) {
            mainCache = null;  // not recycled: a render in flight may still hold it
            mainCacheKey = null;
        }
    }

    /**
     * The finished photo's displayed orientation, from its bounds plus EXIF: the src.image decode
     * must stand the same way for the editor's palette session to build upright textures (X8U
     * captures store the source in sensor orientation; the meta that would rotate it is the part
     * this device cannot read). Header reads only, a few kilobytes.
     */
    private static void readMainShape(Uri uri) {
        try {
            android.app.Application app = android.app.AndroidAppHelper.currentApplication();
            if (app == null) return;
            android.content.ContentResolver resolver = app.getContentResolver();
            android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (java.io.InputStream in = resolver.openInputStream(uri)) {
                if (in != null) android.graphics.BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return;
            int orientation = 1;
            try (java.io.InputStream in = resolver.openInputStream(uri)) {
                if (in != null) orientation = new android.media.ExifInterface(in)
                    .getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1);
            } catch (Throwable ignored) {
                // no readable EXIF: treat as upright
            }
            boolean storedPortrait = bounds.outHeight > bounds.outWidth;
            boolean swaped = orientation >= 5;  // 5..8 rotate a quarter turn
            mainPortrait = swaped ? !storedPortrait : storedPortrait;
        } catch (Throwable ignored) {
            // the decode hook then falls back to the source's own EXIF only
        }
    }

    /**
     * The edited photo's own rendered JPEG, decoded upright (EXIF orientation applied) at the
     * template's working size and in its config/color space. This is the replacement the palette
     * pipeline renders from instead of the decoded src.image (that one is the un-toned sensor
     * image, stored sideways, and the native stage that would tone and rotate it is exactly the
     * broken one on this device). Also populated for photos without a src.image (editor-saved
     * photos: the gallery's save re-encodes and drops the block) — the pass-through guard in
     * GalleryColorPalette.replacePassThrough renders from it so those sessions do not fall back
     * to the stock flipped copy. Null when the decode fails.
     */
    public static android.graphics.Bitmap capturedMainDecode(android.graphics.Bitmap template) {
        String uri = editedUri;
        if (uri == null) return null;
        // cap at the pipeline's own working scale: an editor-saved photo can be 6144x8192 and a
        // full-size RGBA decode of that (~200 MB) would risk killing the gallery process
        int target = Math.min(Math.max(template.getWidth(), template.getHeight()), 4096);
        String key = uri + "@" + target;
        synchronized (GalleryPaletteScope.class) {
            if (mainCache != null && key.equals(mainCacheKey) && !mainCache.isRecycled()) {
                return mainCache;
            }
            mainCache = null;
            mainCacheKey = null;
            android.graphics.Bitmap decoded = decodeMain(uri, target);
            if (decoded == null) return null;
            android.graphics.Bitmap shaped = intoTemplate(decoded, template);
            android.graphics.Bitmap out = shaped == null ? decoded : shaped;
            mainCache = out;
            mainCacheKey = key;
            return out;
        }
    }

    /**
     * Same decode without a pipeline template (plain ARGB_8888, upright), for render paths that
     * bypass the pipeline input — the pass-through guard when the input swap never ran (photos
     * without a src.image, whose session may not resolve a pipeline input at all).
     */
    public static android.graphics.Bitmap capturedMainDecode(int target) {
        String uri = editedUri;
        if (uri == null || target <= 0) return null;
        String key = uri + "@" + target;
        synchronized (GalleryPaletteScope.class) {
            if (mainCache != null && key.equals(mainCacheKey) && !mainCache.isRecycled()) {
                return mainCache;
            }
            mainCache = null;
            mainCacheKey = null;
            android.graphics.Bitmap decoded = decodeMain(uri, target);
            if (decoded == null) return null;
            mainCache = decoded;
            mainCacheKey = key;
            return decoded;
        }
    }

    /** Media-store decode at a power-of-two sample, rotated per EXIF, scaled so the long side is target. */
    private static android.graphics.Bitmap decodeMain(String uri, int target) {
        try {
            android.net.Uri parsed = Uri.parse(uri);
            android.app.Application app = android.app.AndroidAppHelper.currentApplication();
            if (app == null) return null;
            android.content.ContentResolver resolver = app.getContentResolver();
            android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (java.io.InputStream in = resolver.openInputStream(parsed)) {
                if (in != null) android.graphics.BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int orientation = 1;
            try (java.io.InputStream in = resolver.openInputStream(parsed)) {
                if (in != null) orientation = new android.media.ExifInterface(in)
                    .getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1);
            } catch (Throwable ignored) {
                // no readable EXIF: treat as upright
            }
            int longSide = Math.max(bounds.outWidth, bounds.outHeight);
            int sample = 1;
            while (longSide / (sample * 2) >= target) sample *= 2;
            android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
            options.inSampleSize = sample;
            android.graphics.Bitmap raw = null;
            try (java.io.InputStream in = resolver.openInputStream(parsed)) {
                if (in != null) raw = android.graphics.BitmapFactory.decodeStream(in, null, options);
            }
            if (raw == null) return null;
            android.graphics.Matrix matrix = new android.graphics.Matrix();
            switch (orientation) {
                case 6: matrix.postRotate(90); break;
                case 3: matrix.postRotate(180); break;
                case 8: matrix.postRotate(270); break;
                default: break;
            }
            float scale = target / (float) Math.max(raw.getWidth(), raw.getHeight());
            if (Math.abs(scale - 1f) > 0.01f) matrix.postScale(scale, scale);
            android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(
                raw, 0, 0, raw.getWidth(), raw.getHeight(), matrix, true);
            if (out != raw) raw.recycle();
            return out;
        } catch (Throwable error) {
            Log.i(TAG, "main image decode failed: " + error);
            return null;
        }
    }

    /** The decode drawn into the template's config and color space (the pipeline's own shape). */
    private static android.graphics.Bitmap intoTemplate(android.graphics.Bitmap decoded, android.graphics.Bitmap template) {
        try {
            android.graphics.Bitmap.Config config = template.getConfig();
            if (config != android.graphics.Bitmap.Config.ARGB_8888
                    && config != android.graphics.Bitmap.Config.RGBA_F16
                    && config != android.graphics.Bitmap.Config.RGBA_1010102) {
                return decoded;  // HARDWARE or exotic: a software bitmap is fine for the editor path
            }
            if (config == android.graphics.Bitmap.Config.ARGB_8888
                    && decoded.getConfig() == android.graphics.Bitmap.Config.ARGB_8888
                    && template.getColorSpace().equals(decoded.getColorSpace())) {
                return decoded;
            }
            android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(
                decoded.getWidth(), decoded.getHeight(), config, true, template.getColorSpace());
            new android.graphics.Canvas(out).drawBitmap(decoded, 0f, 0f,
                new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
            return out;
        } catch (Throwable error) {
            Log.i(TAG, "main image re-shape failed: " + error);
            return null;
        }
    }
}

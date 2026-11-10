package local.jiege.hook.palette;

import android.opengl.EGL14;
import android.opengl.EGLContext;
import dalvik.system.BaseDexClassLoader;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.io.File;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Symbols;

/**
 * Gallery process (com.coloros.gallery3d): palette editing in the photo editor ("色彩风格": style
 * list, palette panel, re-edit of the camera's filter + palette from the photo's filter.info).
 *
 * Two gaps on this device:
 *   - feature_is_support_color_palette requires ro.product.first_api_level >= 37 (this device
 *     shipped with 36); every other term holds. The hook answers true for that one switch.
 *   - palette mode renders through CombineLutProcessUnitSDK and PaletteColorTable, which the
 *     system com.oplus.camera.unit.sdk.jar lacks. service.sh stages the X10 build of that jar in
 *     files/jiege_ipu; the gallery's shared-library loader for it is replaced by one over the
 *     staged jar (the JNI libraries it loads, libCombineLutJni etc., are identical on both),
 *     kept off the APS texture entry this device lacks (GL pipeline with P010 input instead).
 *   - photos with a src.image get that source (un-toned, sideways) as the pipeline input;
 *     swapSrcInput replaces it with the photo's rendered JPEG, and swapOutputWrite keeps the
 *     upright result from being stretched back into the sideways 4:3 output texture.
 * Which photos the editor treats as palette photos: GalleryPaletteScope.
 */
public final class GalleryColorPalette {
    private static final java.util.concurrent.atomic.AtomicBoolean SIGNATURE =
        new java.util.concurrent.atomic.AtomicBoolean();
    private static final java.util.concurrent.atomic.AtomicBoolean SWAP_LOGGED =
        new java.util.concurrent.atomic.AtomicBoolean();
    private static final java.util.concurrent.atomic.AtomicBoolean SWAP_FAILED =
        new java.util.concurrent.atomic.AtomicBoolean();
    private static final java.util.concurrent.atomic.AtomicBoolean OUT_LOGGED =
        new java.util.concurrent.atomic.AtomicBoolean();
    private static final java.util.concurrent.atomic.AtomicBoolean OUT_FAILED =
        new java.util.concurrent.atomic.AtomicBoolean();
    /** Last output-geometry state logged, so steady preview rendering stays quiet. */
    private static String lastOutputGeometry;
    private static final java.util.concurrent.atomic.AtomicBoolean SRC_LOGGED =
        new java.util.concurrent.atomic.AtomicBoolean();
    private static final java.util.concurrent.atomic.AtomicBoolean SRC_FAILED =
        new java.util.concurrent.atomic.AtomicBoolean();
    private static final java.util.concurrent.atomic.AtomicBoolean PASS_LOGGED =
        new java.util.concurrent.atomic.AtomicBoolean();
    private static final java.util.concurrent.atomic.AtomicBoolean PASS_FAILED =
        new java.util.concurrent.atomic.AtomicBoolean();
    /** The texture uploaded for the previous write-back, kept so a slider drag does not upload
     *  a fresh copy of the same bitmap every frame (they are ~50 MB each on GPU). */
    private static android.graphics.Bitmap heldSource;
    /** The letterboxed copy of heldSource in the editor texture's shape (palette output guard). */
    private static android.graphics.Bitmap heldShaped;
    /** The upright output texture installed into the editor's output map. If the display renders
     *  from the map, this is the full-size path; the letterbox into the original texture is the
     *  fallback for the case it holds the original texture object instead. */
    private static Object heldOutputTexture;

    private static final String TAG = "GalleryColorPalette";
    private static final String FEATURE = "feature_is_support_color_palette";
    private static final String SDK_JAR = "com.oplus.camera.unit.sdk.jar";
    private static final String STAGED_SDK = "/data/user/0/com.coloros.gallery3d/files/jiege_ipu/" + SDK_JAR;
    private static final String LIBRARY_PATH = "/product/lib64:/system/lib64";
    /** Last tone/sat fallback diagnostic, so a drag does not spam one log line per frame. */
    private static String lastRestyleDiag;

    private GalleryColorPalette() {}

    /** Before anything in the gallery touches the camera unit SDK. */
    public static void installGallery(ClassLoader classLoader) {
        if (!replaceSdkLoader(classLoader)) return;
        try {
            XposedBridge.hookMethod(Symbols.get().method("Config.getBoolean"), new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (FEATURE.equals(param.args[0])) param.setResult(Boolean.TRUE);
                }
            });
            GalleryPaletteScope.install(classLoader);
            rotateSrcImageBlock(classLoader);
            rotateSidewaysSrcDecode();
            alignPaletteOutputGeometry(classLoader);
            swapOutputWrite(classLoader);
            replacePassThrough(classLoader);
            Log.i(TAG, "gallery palette editor enabled");
        } catch (Throwable error) {
            Log.e(TAG, "gallery palette switch hook failed", error);
        }
    }

    /**
     * The loader the framework builds for a system shared library (ApplicationLoaders): a shared
     * native namespace, which the SDK's JNI libraries in /product need for their system deps.
     */
    private static ClassLoader sharedLibraryLoader(ClassLoader parent) {
        int targetSdk = android.os.Build.VERSION.SDK_INT;
        return (ClassLoader) XposedHelpers.callStaticMethod(
            XposedHelpers.findClass("com.android.internal.os.ClassLoaderFactory", null), "createClassLoader",
            new Class<?>[] {String.class, String.class, String.class, ClassLoader.class, int.class, boolean.class, String.class},
            STAGED_SDK, LIBRARY_PATH, LIBRARY_PATH, parent, targetSdk, true, null);
    }

    /**
     * The X10 SDK sends a gallery request with GL input/output textures straight to the APS
     * texture entry, which this device's APS lacks ("no origin image"). Without that branch the
     * SDK takes its GL pipeline: the texture is converted to P010 on the GPU and handed to
     * ipuColorPaletteProcess, whose gallery input on this device is P010. That pipeline shares
     * the caller's GL context, which this gallery does not pass for texture requests; the
     * context current on the calling thread is given to the SDK.
     */
    private static void p010PathOnly(ClassLoader sdkLoader) {
        String sdk = "com.oplus.ocs.camera.ipusdk.processunit.palette.PaletteProcessUnitSDK";
        XposedHelpers.findAndHookMethod(sdk, sdkLoader, "isTextureRequest", XC_MethodReplacement.returnConstant(Boolean.FALSE));
        XposedHelpers.findAndHookMethod(sdk, sdkLoader, "processImage", new XC_MethodHook() {
            private android.opengl.EGLDisplay display;
            private android.opengl.EGLSurface draw, read;
            private android.opengl.EGLContext context;

            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (SIGNATURE.compareAndSet(false, true)) {
                    java.lang.reflect.Method method = (java.lang.reflect.Method) param.method;
                    StringBuilder text = new StringBuilder("processImage ");
                    text.append(method.getName()).append('(');
                    Class<?>[] types = method.getParameterTypes();
                    for (int i = 0; i < types.length; i++) {
                        if (i > 0) text.append(',');
                        text.append(types[i].getSimpleName());
                    }
                    text.append(") -> ").append(method.getReturnType().getSimpleName());
                    for (int i = 0; i < param.args.length; i++) {
                        text.append(" arg").append(i).append('=')
                            .append(param.args[i] == null ? "null" : param.args[i].getClass().getSimpleName());
                    }
                    Log.i(TAG, text.toString());
                }
                display = EGL14.eglGetCurrentDisplay();
                context = EGL14.eglGetCurrentContext();
                draw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW);
                read = EGL14.eglGetCurrentSurface(EGL14.EGL_READ);
                if (context == null || context == EGL14.EGL_NO_CONTEXT) return;
                if (XposedHelpers.getObjectField(param.thisObject, "mShareGlContext") != null) return;
                XposedHelpers.callMethod(param.thisObject, "setShareGlContext", display, context);
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                // The SDK's GL bridge makes its own context current and does not always restore
                // the caller's; the editor then renders into the wrong context (glUseProgram
                // GL_INVALID_VALUE) and the preview goes black. Put back what was current.
                if (context != null && context != EGL14.EGL_NO_CONTEXT) {
                    if (EGL14.eglGetCurrentContext() == context) return;
                    EGL14.eglMakeCurrent(display, draw, read, context);
                } else if (EGL14.eglGetCurrentContext() != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglMakeCurrent(EGL14.EGL_NO_DISPLAY, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                }
            }
        });
    }

    /** Palette mode without the newer SDK fails every filter render, so the switch follows this. */
    private static boolean replaceSdkLoader(ClassLoader classLoader) {
        if (!new File(STAGED_SDK).canRead()) {
            Log.i(TAG, "staged camera unit SDK missing: " + STAGED_SDK);
            return false;
        }
        try {
            ClassLoader[] loaders = (ClassLoader[]) XposedHelpers.getObjectField(classLoader, "sharedLibraryLoaders");
            if (loaders == null) return false;
            for (int i = 0; i < loaders.length; i++) {
                ClassLoader loader = loaders[i];
                if (loader instanceof BaseDexClassLoader && loader.toString().contains(SDK_JAR)) {
                    loaders[i] = sharedLibraryLoader(loader.getParent());
                    p010PathOnly(loaders[i]);
                    restyleDirectly(loaders[i]);
                    swapSrcInput(loaders[i]);
                    GalleryFilters.install(loaders[i]);
                    return true;
                }
            }
            Log.i(TAG, "camera unit SDK loader not found");
        } catch (Throwable error) {
            Log.e(TAG, "camera unit SDK loader replacement failed", error);
        }
        return false;
    }

    /**
     * The palette pipeline cannot re-render from src.image on this device: the native meta read
     * fails ("resolvePaletteSrcOrientation invalid metaIndex=0" — X8U captures store src.image as
     * a plain JPEG where X10 uses a private format plus basictone meta) and the BasicTone stage
     * outputs garbage, while the gallery hardcodes runFilterStage=false so the selected style is
     * never applied at all. Both are replaced: BasicTone passes its input through (every photo is
     * already toned at capture), and the filter stage runs for any style except the one the photo
     * was captured with (already baked in, see GalleryPaletteScope.capturedFilter). The result is
     * the stock filter editor's rendering path, which does work here.
     *
     * The panel's tone/saturation sliders live in the bypassed native stage, so they would do
     * nothing. The gallery sends them as process values (palette_tone / palette_saturation,
     * both -100..100, saturation 100 meaning a 2.0 multiplier — ColorPaletteParameter reads them
     * from the "tone"/"filter_saturation" editor keys and x8v.k forwards them per request); the
     * replacement reads them off the request entity and applies a Java equivalent onto the copy:
     * tone as a gamma curve (positive brightens the midtones), saturation as a luminance mix.
     */
    private static void restyleDirectly(ClassLoader sdkLoader) {
        String unit = "com.oplus.ocs.camera.ipusdk.processunit.palette.PaletteProcessUnitSDK";
        String stage = "com.oplus.ocs.camera.ipusdk.processunit.palette.PaletteFilterPreviewStage";
        try {
            XposedHelpers.findAndHookMethod(unit, sdkLoader, "runBasicToneStage",
                String.class, android.graphics.Bitmap.class, android.graphics.Bitmap.class,
                String.class, boolean.class, new XC_MethodReplacement() {
                    @Override protected Object replaceHookedMethod(MethodHookParam param) {
                        android.graphics.Bitmap input = (android.graphics.Bitmap) param.args[1];
                        if (input == null) return null;  // the caller turns this into failProcess
                        int[] panel = panelValues(sdkLoader, param.thisObject);
                        android.graphics.Bitmap restyled = panel == null ? null : toneSaturation(input, panel[0], panel[1]);
                        if (restyled == null && panel != null && (panel[0] != 0 || panel[1] != 0)) {
                            String diag = "tone/sat not applied: tone=" + panel[0] + " sat=" + panel[1]
                                + " config=" + input.getConfig() + " colorspace=" + input.getColorSpace();
                            if (!diag.equals(lastRestyleDiag)) {
                                lastRestyleDiag = diag;  // once per distinct situation, not per drag frame
                                Log.i(TAG, diag);
                            }
                        }
                        if (restyled != null) return restyled;
                        try {
                            return input.copy(input.getConfig(), false);
                        } catch (Throwable error) {
                            return input;  // e.g. a HARDWARE bitmap: better aliasing than failing
                        }
                    }
                });
            final Class<?> entity = XposedHelpers.findClass(
                "com.oplus.ocs.camera.ipusdk.processunit.BaseProcessUnitSDK$ParameterEntity", sdkLoader);
            XposedHelpers.findAndHookMethod(stage, sdkLoader, "shouldRunFilterStage", entity,
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        Object value = needsFilterStage(sdkLoader, stage, param.args[0]);
                        if (value == null) return;  // hook setup incomplete: keep the stock answer
                        param.setResult(value);
                    }
                });
            Log.i(TAG, "gallery palette restyle enabled");
        } catch (Throwable error) {
            Log.e(TAG, "gallery palette restyle hook failed", error);
        }
    }

    /**
     * For photos that carry a src.image the gallery hands the pipeline the decoded source as its
     * input bitmap (palette_input_bitmap): un-toned and stored in sensor orientation. On X8U the
     * stages that would re-tone and rotate it are the broken ones (BasicTone's native meta read),
     * so the editor would show a sideways neutral image. When the gallery provides that source
     * bitmap, the resolved input is replaced by the photo's own rendered JPEG (upright, captured
     * look baked in — GalleryPaletteScope.capturedMainDecode); the identity BasicTone passes it
     * through and the filter stage layers the picked style on top. Photos without a src.image
     * already render from the finished image and are left alone.
     */
    private static void swapSrcInput(ClassLoader sdkLoader) {
        String unit = "com.oplus.ocs.camera.ipusdk.processunit.palette.PaletteProcessUnitSDK";
        try {
            final Class<?> keys = XposedHelpers.findClass(
                "com.oplus.ocs.camera.ipusdk.processunit.palette.key.PaletteProcessKeySDK", sdkLoader);
            XposedHelpers.findAndHookMethod(unit, sdkLoader, "resolveProcessInputBitmap",
                int.class, boolean.class, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        android.graphics.Bitmap input = (android.graphics.Bitmap) param.getResult();
                        if (input == null || input.isRecycled()) return;
                        try {
                            Object entity = XposedHelpers.getObjectField(param.thisObject, "mRequestEntities");
                            Object provided = XposedHelpers.callMethod(entity, "getProcessValue",
                                XposedHelpers.getStaticObjectField(keys, "PALETTE_INPUT_BITMAP"));
                            if (provided == null) {
                                // no source bitmap: the finished image is the pipeline input
                                // already. Warm the main cache anyway: editor-saved photos lose
                                // src.image on re-encode, and the pass-through guard below needs
                                // the finished photo to keep their palette session upright.
                                try {
                                    GalleryPaletteScope.capturedMainDecode(input);
                                } catch (Throwable ignored) {
                                    // cache warm-up is best-effort
                                }
                                return;
                            }
                            android.graphics.Bitmap main = GalleryPaletteScope.capturedMainDecode(input);
                            if (main == null || main == input) return;
                            if (SWAP_LOGGED.compareAndSet(false, true)) {
                                Log.i(TAG, "palette input swapped: src " + input.getWidth() + "x" + input.getHeight()
                                    + " -> rendered " + main.getWidth() + "x" + main.getHeight()
                                    + " " + main.getConfig());
                            }
                            param.setResult(main);
                        } catch (Throwable error) {
                            if (SWAP_FAILED.compareAndSet(false, true)) {
                                Log.e(TAG, "palette input swap failed", error);
                            }
                        }
                    }
                });
            Log.i(TAG, "palette src-image input swap enabled");
        } catch (Throwable error) {
            Log.e(TAG, "palette input swap hook failed", error);
        }
    }

    /** The src.image block as last re-encoded (in/out pair), so a repeated read is free. */
    private static byte[] lastSrcIn;
    private static byte[] lastSrcOut;

    /**
     * X8U captures store src.image sideways — the block's own EXIF says orientation 6, but the
     * gallery's media cache decodes it without applying that, so every palette session texture
     * comes out landscape and the editor panel shows the upright photo squeezed into a band. The
     * block is rotated at its source instead: FileExtendedContainer.getExtensionData("src.image")
     * — the gallery's own extension-block API — returns a re-encoded upright JPEG, so whichever
     * decoder the cache uses sees an upright image and the whole session (textures, input bitmap,
     * write-back, display, save) stands portrait. The module's own editor-open diagnostic keeps
     * the stored bytes (skipped by the stack walk, so it still reports the raw shape and EXIF);
     * the decode hooks below catch paths that bypass the container.
     */
    private static void rotateSrcImageBlock(ClassLoader galleryLoader) {
        try {
            Class<?> container = XposedHelpers.findClass(
                "com.oplus.gallery.foundation.fileaccess.extension.FileExtendedContainer", galleryLoader);
            XposedHelpers.findAndHookMethod(container, "getExtensionData", String.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!"src.image".equals(param.args[0])) return;
                    byte[] data = (byte[]) param.getResult();
                    if (data == null || data.length == 0) return;
                    for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
                        if (frame.getClassName().endsWith("GalleryPaletteScope")) return;  // diagnostics read the block as stored
                    }
                    param.setResult(uprightSrcBytes(data));
                }
            });
            Log.i(TAG, "src block rotation enabled");
        } catch (Throwable error) {
            Log.e(TAG, "src block rotation hook failed", error);
        }
    }

    /** The block decoded, rotated upright per its own EXIF (or the photo's shape) and re-encoded. */
    private static byte[] uprightSrcBytes(byte[] data) {
        if (sameBytes(data, lastSrcIn) && lastSrcOut != null) return lastSrcOut;
        try {
            android.graphics.BitmapFactory.Options shape = new android.graphics.BitmapFactory.Options();
            shape.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, shape);
            if (shape.outWidth <= 0) return data;
            int degrees = srcUprightDegrees(data, shape.outWidth, shape.outHeight);
            if (degrees == 0) {
                lastSrcIn = data;
                lastSrcOut = data;
                return data;
            }
            android.graphics.Bitmap decoded = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length);
            android.graphics.Bitmap upright = decoded == null ? null : rotateBitmap(decoded, degrees);
            if (decoded != null && upright != decoded) decoded.recycle();
            if (upright == null) return data;
            java.io.ByteArrayOutputStream encoded = new java.io.ByteArrayOutputStream(1 << 20);
            boolean ok = upright.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, encoded);
            upright.recycle();
            if (!ok) return data;
            byte[] out = encoded.toByteArray();
            lastSrcIn = data;
            lastSrcOut = out;
            GalleryPaletteScope.containerRotated = true;
            if (SRC_LOGGED.compareAndSet(false, true)) {
                Log.i(TAG, "src block re-encoded upright: " + shape.outWidth + "x" + shape.outHeight
                    + " rotated " + degrees + " degrees, " + out.length + " bytes");
            }
            return out;
        } catch (Throwable error) {
            if (SRC_FAILED.compareAndSet(false, true)) {
                Log.e(TAG, "src block rotation failed", error);
            }
            return data;
        }
    }

    /**
     * X8U captures store src.image in sensor orientation (sideways). The stock palette session
     * builds every texture off that decode, so the whole editor panel renders a landscape band of
     * the upright photo; on X10 the rotation comes from meta this device cannot read. The gallery
     * decodes the block through BitmapFactory (the portrait-blur plugin's code shows the
     * decodeByteArray pattern) — both decodeByteArray overloads are hooked with a byte-length
     * match, and decodeStream with either the very bytes read at editor open (a ByteArrayInputStream
     * over them) or, when the container path was not used at all, the source's exact shape on the
     * media cache thread. The matched decode is rotated upright before the cache ever sees it, so
     * the session's input and output textures, the input bitmap and the write-back all stand
     * portrait and the display shows the photo full size.
     */
    private static void rotateSidewaysSrcDecode() {
        XC_MethodHook bytesHook = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                android.graphics.Bitmap decoded = (android.graphics.Bitmap) param.getResult();
                byte[] bytes = (byte[]) param.args[0];
                if (decoded == null || decoded.isRecycled() || bytes == null) return;
                int length = GalleryPaletteScope.srcImageLength;
                if (length <= 0 || bytes.length != length) return;
                rotateMatchedDecode(param, decoded, bytes);
            }
        };
        XC_MethodHook streamHook = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                android.graphics.Bitmap decoded = (android.graphics.Bitmap) param.getResult();
                if (decoded == null || decoded.isRecycled()) return;
                if (GalleryPaletteScope.srcShapeW <= 0 || GalleryPaletteScope.srcShapeH <= 0) return;
                if (decoded.getWidth() != GalleryPaletteScope.srcShapeW
                        || decoded.getHeight() != GalleryPaletteScope.srcShapeH) return;
                if (GalleryPaletteScope.containerRotated) return;  // the block was already fixed upstream
                Object stream = param.args[0];
                byte[] bytes = null;
                if (stream instanceof java.io.ByteArrayInputStream) {
                    try {
                        bytes = (byte[]) XposedHelpers.getObjectField(stream, "buf");
                    } catch (Throwable ignored) {
                        // not the buffered shape expected: fall through to the thread rule
                    }
                    if (bytes != null && !sameBytes(bytes, GalleryPaletteScope.srcBytes)) return;
                } else if (!Thread.currentThread().getName().contains("MediaCache")) {
                    return;  // no byte identity and not the media cache: too risky to touch
                }
                rotateMatchedDecode(param, decoded, bytes);
            }
        };
        try {
            XposedHelpers.findAndHookMethod(android.graphics.BitmapFactory.class, "decodeByteArray",
                byte[].class, int.class, int.class, bytesHook);
            XposedHelpers.findAndHookMethod(android.graphics.BitmapFactory.class, "decodeByteArray",
                byte[].class, int.class, int.class, android.graphics.BitmapFactory.Options.class, bytesHook);
            XposedHelpers.findAndHookMethod(android.graphics.BitmapFactory.class, "decodeStream",
                java.io.InputStream.class, android.graphics.Rect.class,
                android.graphics.BitmapFactory.Options.class, streamHook);
            Log.i(TAG, "src decode rotation enabled");
        } catch (Throwable error) {
            Log.e(TAG, "src decode rotation hook failed", error);
        }
    }

    /** Rotates one matched source decode upright and replaces the hook result with it. */
    private static void rotateMatchedDecode(XC_MethodHook.MethodHookParam param,
            android.graphics.Bitmap decoded, byte[] bytes) {
        try {
            int degrees = bytes != null
                ? srcUprightDegrees(bytes, decoded.getWidth(), decoded.getHeight())
                : srcShapeDegrees(decoded.getWidth(), decoded.getHeight());
            if (degrees == 0) return;
            android.graphics.Bitmap upright = rotateBitmap(decoded, degrees);
            if (upright == decoded) return;
            param.setResult(upright);
            decoded.recycle();  // replaced: the caller only ever sees the rotated one
            if (SRC_LOGGED.compareAndSet(false, true)) {
                Log.i(TAG, "src decode rotated " + degrees + " degrees: palette session stands upright");
            }
        } catch (Throwable error) {
            if (SRC_FAILED.compareAndSet(false, true)) {
                Log.e(TAG, "src decode rotation failed", error);
            }
        }
    }

    /** The source's own EXIF orientation when its bytes are at hand, else the photo's shape. */
    private static int srcUprightDegrees(byte[] bytes, int width, int height) {
        if (bytes != null) {
            try {
                android.media.ExifInterface exif =
                    new android.media.ExifInterface(new java.io.ByteArrayInputStream(bytes));
                switch (exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                    case 6: return 90;
                    case 8: return 270;
                    case 3: return 180;
                    default: break;  // upright per its EXIF — but X8U stores raw sensor bytes
                }
            } catch (Throwable ignored) {
                // not a container ExifInterface can parse: fall through to the shape rule
            }
        }
        return srcShapeDegrees(width, height);
    }

    /** A source lying the other way than the finished photo gets the rear sensor's quarter turn. */
    private static int srcShapeDegrees(int width, int height) {
        boolean srcLandscape = width > height;
        if (srcLandscape == GalleryPaletteScope.mainPortrait) return 90;
        return 0;
    }

    private static android.graphics.Bitmap rotateBitmap(android.graphics.Bitmap source, int degrees) {
        android.graphics.Matrix matrix = new android.graphics.Matrix();
        matrix.postRotate(degrees);
        return android.graphics.Bitmap.createBitmap(
            source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
    }

    /** Length plus first and last 64 bytes — enough to recognize the same block across reads. */
    private static boolean sameBytes(byte[] a, byte[] b) {
        if (a == b) return true;
        if (a == null || b == null || a.length != b.length) return false;
        for (int i = 0; i < 64; i++) {
            if (a[i] != b[i]) return false;
        }
        for (int i = 1; i <= 64; i++) {
            if (a[a.length - i] != b[b.length - i]) return false;
        }
        return true;
    }

    /**
     * The panel's 标准/original entry renders through passThroughToOutput: a plain GL copy of the
     * input texture into the output. That chain flips the image relative to the normal render
     * path (with the sideways X8U source the flip hid inside the old "rotated" look; with the
     * source now upright it shows as upside down). The photo as captured is exactly the upright
     * main decode the input swap already renders from, so the pass-through is replaced by the
     * same write-back the normal chain uses — the standard entry then shows the photo exactly
     * like the identity render does.
     *
     * Editor-saved photos (a palette edit re-encodes the JPEG and drops src.image) never get a
     * pipeline source bitmap, so the cache warm-up in swapSrcInput may not have run; the main
     * decode is then done here from the input texture's scale. Without it those sessions fall
     * back to the stock flipped copy — the "re-open an edited photo, tap 色彩风格, it is upside
     * down" case.
     */
    private static void replacePassThrough(ClassLoader galleryLoader) {
        try {
            Class<?> accessorType = XposedHelpers.findClass(
                "com.oplus.tbluniformeditor.plugins.pool.export.inst.IPluginArgumentsAccessor", galleryLoader);
            Class<?> textureType = XposedHelpers.findClass(
                "com.oplus.gallery.sdk.texture.texture.f", galleryLoader);
            XposedHelpers.findAndHookMethod(
                "com.oplus.tbluniformeditor.plugins.colorpalette.BitmapColorPaletteManager", galleryLoader,
                "passThroughToOutput", accessorType, textureType, textureType, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        android.graphics.Bitmap main = GalleryPaletteScope.cachedMain();
                        if (main == null || main.isRecycled()) {
                            // Editor-saved photos carry no src.image, so the input swap never
                            // warmed the cache — decode the finished photo here (at the input
                            // texture's scale) so the flipped stock copy is replaced for them too.
                            try {
                                int tw = XposedHelpers.getIntField(param.args[1], "l");
                                int th = XposedHelpers.getIntField(param.args[1], "k");
                                int target = Math.max(1024, Math.min(Math.max(tw, th), 4096));
                                main = GalleryPaletteScope.capturedMainDecode(target);
                            } catch (Throwable ignored) {
                                // no texture dims: fall through to the stock copy
                            }
                        }
                        if (main == null || main.isRecycled()) return;  // nothing better to show: keep the stock copy
                        try {
                            java.lang.reflect.Method writer = param.thisObject.getClass()
                                .getDeclaredMethod("writeResultToOutput",
                                    accessorType, android.graphics.Bitmap.class, textureType);
                            writer.setAccessible(true);
                            writer.invoke(param.thisObject, param.args[0], main, param.args[2]);
                            param.setResult(null);
                            if (PASS_LOGGED.compareAndSet(false, true)) {
                                Log.i(TAG, "original entry rendered from the finished photo");
                            }
                        } catch (Throwable error) {
                            if (PASS_FAILED.compareAndSet(false, true)) {
                                Log.e(TAG, "original entry replacement failed", error);
                            }
                            // fall through: the stock pass-through copy still runs
                        }
                    }
                });
            Log.i(TAG, "palette original entry guard enabled");
        } catch (Throwable error) {
            Log.e(TAG, "palette original entry hook failed", error);
        }
    }

    /**
     * Gallery builds the palette output from the source image's sideways geometry.  The input
     * bitmap is replaced by the upright rendered photo later in execute(), so changing only the
     * output texture at write-back leaves the host with a portrait texture and a landscape
     * width/height contract.  Gallery 17.10.41 then paints that mismatch as first a stretched
     * square and, after the next filter rebuild, a correctly shaped but half-size image.
     *
     * Align the primary output dimensions with the finished photo before Gallery allocates the
     * output texture.  The stock palette manager can then copy the portrait result as a normal
     * same-size write instead of relying on the late texture reroute.
     */
    private static void alignPaletteOutputGeometry(ClassLoader galleryLoader) {
        try {
            Class<?> accessorType = XposedHelpers.findClass(
                "com.oplus.tbluniformeditor.plugins.pool.export.inst.IPluginArgumentsAccessor", galleryLoader);
            XposedHelpers.findAndHookMethod(
                "com.oplus.tbluniformeditor.plugins.colorpalette.ColorPaletteAlgoImpl", galleryLoader,
                "getOutputInfo", accessorType, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (!Boolean.TRUE.equals(param.getResult())) return;
                        Object accessor = param.args[0];
                        String before = describeOutputGeometry(accessor);
                        boolean changed = alignPrimaryOutputToPhoto(accessor);
                        logOutputGeometry((changed ? "aligned " : "stock ") + before
                            + " -> " + describeOutputGeometry(accessor));
                    }
                });
            Log.i(TAG, "palette output geometry alignment enabled");
        } catch (Throwable error) {
            Log.e(TAG, "palette output geometry hook failed", error);
        }
    }

    /** Swaps only the primary map dimensions; HDR gainmap companions keep their own size. */
    private static boolean alignPrimaryOutputToPhoto(Object accessor) {
        if (!GalleryPaletteScope.mainPortrait) return false;
        try {
            int[] widths = (int[]) XposedHelpers.callMethod(
                accessor, "getArg", "global_key_output_source_map_width", null);
            int[] heights = (int[]) XposedHelpers.callMethod(
                accessor, "getArg", "global_key_output_source_map_height", null);
            if (widths == null || heights == null || widths.length == 0 || heights.length == 0) {
                return false;
            }
            int width = widths[0], height = heights[0];
            if (width <= 0 || height <= 0 || height > width) return false;
            int[] uprightWidths = widths.clone();
            int[] uprightHeights = heights.clone();
            uprightWidths[0] = height;
            uprightHeights[0] = width;
            XposedHelpers.callMethod(accessor, "setTemporaryArg",
                "global_key_output_source_map_width", uprightWidths);
            XposedHelpers.callMethod(accessor, "setTemporaryArg",
                "global_key_output_source_map_height", uprightHeights);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String describeOutputGeometry(Object accessor) {
        try {
            Object widths = XposedHelpers.callMethod(
                accessor, "getArg", "global_key_output_source_map_width", null);
            Object heights = XposedHelpers.callMethod(
                accessor, "getArg", "global_key_output_source_map_height", null);
            Object roads = XposedHelpers.callMethod(
                accessor, "getArg", "global_key_output_source_road_map_count", null);
            Object index = XposedHelpers.callMethod(
                accessor, "getArg", "global_key_output_source_road_map_index", null);
            Object map = XposedHelpers.callMethod(
                accessor, "getArg", "global_key_output_source_map", null);
            int maps = map instanceof Object[] ? ((Object[]) map).length : -1;
            return "w=" + java.util.Arrays.toString((int[]) widths)
                + " h=" + java.util.Arrays.toString((int[]) heights)
                + " roads=" + roads + " index=" + java.util.Arrays.deepToString((Object[]) index)
                + " maps=" + maps + " portraitPhoto=" + GalleryPaletteScope.mainPortrait;
        } catch (Throwable error) {
            return "unreadable(" + error.getClass().getSimpleName() + ')';
        }
    }

    private static synchronized void logOutputGeometry(String state) {
        if (state.equals(lastOutputGeometry)) return;
        lastOutputGeometry = state;
        Log.i(TAG, "palette output geometry " + state);
    }

    /**
     * The editor's palette write-back (BitmapColorPaletteManager.writeResultToOutput) copies the
     * result into the output texture object it holds: same size is a plain GL copy, any size
     * difference goes through scaleCopyTexture/scaleBitmapExact, which stretch to the texture's
     * exact dimensions with no aspect preservation — and the display fits the output texture into
     * the photo canvas uniformly, so a stretched bitmap shows up deformed and the whole sideways
     * texture (X8U captures: src.image is stored in sensor orientation) only ever covers a
     * landscape band of the upright canvas. With swapSrcInput the result is the upright rendered
     * photo, so two writes cover both possible display paths:
     *
     *  - a new upright RGBA_8888 texture of the result's own size is swapped into the accessor's
     *    global_key_output_source_map (setStoreAbleArg — the earlier setTemporaryArg attempt was
     *    invisible to later frames) and the result written into it through the stock same-size
     *    copy; if the display renders from the map, the photo shows full size;
     *  - the result letterboxed into the original texture's exact dimensions (centered, black
     *    bands) through the stock same-size copy; if the display holds the original texture
     *    object, the photo shows at the right aspect, smaller.
     */
    private static void swapOutputWrite(ClassLoader galleryLoader) {
        try {
            Class<?> accessorType = XposedHelpers.findClass(
                "com.oplus.tbluniformeditor.plugins.pool.export.inst.IPluginArgumentsAccessor", galleryLoader);
            Class<?> textureType = XposedHelpers.findClass(
                "com.oplus.gallery.sdk.texture.texture.f", galleryLoader);
            XposedHelpers.findAndHookMethod(
                "com.oplus.tbluniformeditor.plugins.colorpalette.BitmapColorPaletteManager", galleryLoader,
                "writeResultToOutput", accessorType, android.graphics.Bitmap.class, textureType,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        android.graphics.Bitmap result = (android.graphics.Bitmap) param.args[1];
                        Object outputTexture = param.args[2];
                        if (result == null || result.isRecycled() || outputTexture == null) return;
                        try {
                            int bw = result.getWidth(), bh = result.getHeight();
                            int tw = XposedHelpers.getIntField(outputTexture, "l");
                            int th = XposedHelpers.getIntField(outputTexture, "k");
                            logOutputGeometry("write result=" + bw + "x" + bh
                                + " target=" + tw + "x" + th + ' ' + describeOutputGeometry(param.args[0]));
                            if (bw == tw && bh == th) return;    // straight copy: the stock path is correct
                            if (bw > 0 && bh > 0 && tw > 0 && th > 0
                                    && (long) bw * th == (long) bh * tw) {
                                // A same-aspect downscale is normally faithful, but Gallery 17.10.41
                                // can hand the standard palette entry a small display texture
                                // (276x368 for a portrait photo). Returning here leaves that small
                                // texture in the output map, so the editor paints a thumbnail-sized
                                // image even though the result bitmap is full working size. Only
                                // promote truly undersized targets; near-size proxy textures keep
                                // the stock copy path and avoid unnecessary full-size allocations.
                                if (Math.max(tw, th) * 2L >= Math.max(bw, bh)) return;
                                Log.i(TAG, "palette output triggered reroute from small same-aspect texture "
                                    + tw + "x" + th + " for result " + bw + "x" + bh);
                            }
                            Object accessor = param.args[0];
                            boolean rerouted = rerouteOutputTexture(param, accessor, result);
                            android.graphics.Bitmap shaped = letterbox(result, tw, th);
                            if (shaped == null && !rerouted) return;  // fall through to the stock stretched write
                            if (shaped != null) {
                                XposedBridge.invokeOriginalMethod((java.lang.reflect.Method) param.method,
                                    param.thisObject, new Object[] { accessor, shaped, outputTexture });
                            }
                            param.setResult(null);  // both writes done (or attempted) above
                            if (OUT_LOGGED.compareAndSet(false, true)) {
                                Log.i(TAG, "palette output " + (rerouted ? "re-routed to an upright texture and letterboxed "
                                    : "letterboxed ") + bw + "x" + bh
                                    + " into the editor texture " + tw + "x" + th);
                            }
                        } catch (Throwable error) {
                            if (OUT_FAILED.compareAndSet(false, true)) {
                                Log.e(TAG, "palette output reshape failed", error);
                            }
                            // fall through: the stock stretched write-back still runs
                        }
                    }
                });
            Log.i(TAG, "palette output write guard enabled");
        } catch (Throwable error) {
            Log.e(TAG, "palette output write hook failed", error);
        }
    }

    /**
     * Puts an upright texture of the result's own size into the accessor's output map and writes
     * the result into it through the stock same-size copy. The texture is kept across frames (the
     * stock copy refreshes its content every frame); a new one is created only when the result
     * size changes. False when the accessor does not cooperate — the caller then keeps to the
     * letterbox-only write.
     */
    private static boolean rerouteOutputTexture(XC_MethodHook.MethodHookParam param, Object accessor,
            android.graphics.Bitmap result) {
        try {
            int w = result.getWidth(), h = result.getHeight();
            if (heldOutputTexture != null) {
                int hw = XposedHelpers.getIntField(heldOutputTexture, "l");
                int hh = XposedHelpers.getIntField(heldOutputTexture, "k");
                if (hw != w || hh != h) {
                    try {
                        XposedHelpers.callMethod(accessor, "reuse", heldOutputTexture);
                    } catch (Throwable ignored) {
                        // pool recycling is best-effort
                    }
                    heldOutputTexture = null;
                }
            }
            if (heldOutputTexture == null) {
                // plain 8888: the earlier 1010102 upload never made it to the screen
                android.graphics.Bitmap seed = android.graphics.Bitmap.createBitmap(
                    w, h, android.graphics.Bitmap.Config.ARGB_8888);
                heldOutputTexture = XposedHelpers.callMethod(accessor, "toTexture", seed, false);
                if (heldOutputTexture == null) return false;
            }
            Object current = XposedHelpers.callMethod(accessor, "getArg",
                "global_key_output_source_map", "");
            Object replacement;
            if (current instanceof Object[]) {
                Object[] arr = ((Object[]) current).clone();
                if (arr.length == 0) return false;
                arr[0] = heldOutputTexture;  // keep any gainmap companions the host put there
                replacement = arr;
            } else {
                replacement = new Object[] { heldOutputTexture };
            }
            XposedHelpers.callMethod(accessor, "setStoreAbleArg",
                "global_key_output_source_map", replacement);
            XposedBridge.invokeOriginalMethod((java.lang.reflect.Method) param.method,
                param.thisObject, new Object[] { accessor, result, heldOutputTexture });
            return true;
        } catch (Throwable error) {
            return false;
        }
    }

    /**
     * The result drawn centered into a tw x th bitmap at a uniform scale, black bands filling the
     * rest. The editor's canvas fits the output texture uniformly, so content drawn at its own
     * aspect inside the texture displays at the correct shape. Cached per source bitmap: the
     * panel re-renders every slider frame off one cached input, and each copy is ~50 MB.
     */
    private static android.graphics.Bitmap letterbox(android.graphics.Bitmap source, int tw, int th) {
        if (source == heldSource && heldShaped != null && !heldShaped.isRecycled()
                && heldShaped.getWidth() == tw && heldShaped.getHeight() == th) {
            return heldShaped;
        }
        try {
            android.graphics.Bitmap.Config config = source.getConfig();
            if (config != android.graphics.Bitmap.Config.ARGB_8888
                    && config != android.graphics.Bitmap.Config.RGBA_F16
                    && config != android.graphics.Bitmap.Config.RGBA_1010102) {
                config = android.graphics.Bitmap.Config.ARGB_8888;
            }
            android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(
                tw, th, config, true, source.getColorSpace());
            android.graphics.Canvas canvas = new android.graphics.Canvas(out);
            canvas.drawColor(android.graphics.Color.BLACK);
            float scale = Math.min(tw / (float) source.getWidth(), th / (float) source.getHeight());
            float dw = source.getWidth() * scale, dh = source.getHeight() * scale;
            android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
            canvas.drawBitmap(source, (tw - dw) / 2f, (th - dh) / 2f, paint);
            if (heldShaped != null && heldShaped != out) heldShaped.recycle();
            heldSource = source;
            heldShaped = out;
            return out;
        } catch (Throwable error) {
            return null;
        }
    }

    /** needsFilterStage(entity), plus a runFilterStage override: false for the captured style. */
    private static Boolean needsFilterStage(ClassLoader sdkLoader, String stage, Object entity) {        if (entity == null) return Boolean.FALSE;
        try {
            boolean needs = (Boolean) XposedHelpers.callStaticMethod(XposedHelpers.findClass(stage, sdkLoader),
                "needsFilterStage", entity);
            if (!needs) return Boolean.FALSE;
            String captured = GalleryPaletteScope.capturedFilter;
            if (captured != null) {
                Object key = XposedHelpers.getStaticObjectField(XposedHelpers.findClass(
                    "com.oplus.ocs.camera.ipusdk.processunit.palette.key.PaletteProcessKeySDK", sdkLoader),
                    "PALETTE_FILTER_ID");
                if (captured.equals(XposedHelpers.callMethod(entity, "getProcessValue", key))) return Boolean.FALSE;
            }
            return Boolean.TRUE;
        } catch (Throwable error) {
            Log.e(TAG, "needsFilterStage reflection failed", error);
            return Boolean.FALSE;
        }
    }

    /** The panel sliders of the request being processed: {tone, saturation}, both -100..100. */
    private static int[] panelValues(ClassLoader sdkLoader, Object unit) {
        try {
            Object entity = XposedHelpers.getObjectField(unit, "mRequestEntities");
            Class<?> keys = XposedHelpers.findClass(
                "com.oplus.ocs.camera.ipusdk.processunit.palette.key.PaletteProcessKeySDK", sdkLoader);
            return new int[] {
                intValue(XposedHelpers.callMethod(entity, "getProcessValue",
                    XposedHelpers.getStaticObjectField(keys, "PALETTE_TONE"))),
                intValue(XposedHelpers.callMethod(entity, "getProcessValue",
                    XposedHelpers.getStaticObjectField(keys, "PALETTE_SATURATION"))),
            };
        } catch (Throwable error) {
            return null;  // keys or entity unavailable: keep the identity bypass
        }
    }

    private static int intValue(Object value) {
        return value instanceof Integer ? (Integer) value : 0;
    }

    /**
     * The Java stand-in for the bypassed BasicTone: tone as a gamma curve over 0..255 (positive
     * brightens the midtones, 2^(-tone/100)), saturation as a mix towards/away from luminance
     * (100 = 2.0x, -100 = grayscale). The editor's input bitmap is typically RGBA_F16 in an
     * extended scRGB color space, so the pixel pass runs on an sRGB/8888 flatten and the result
     * is drawn back into the input's config and color space (values beyond 0..1 clamp — the
     * panel is SDR-only here). Returns null when the values are neutral or the bitmap cannot be
     * processed this way; the caller then falls back to the plain identity copy.
     */
    private static android.graphics.Bitmap toneSaturation(android.graphics.Bitmap input, int tone, int saturation) {
        if (tone == 0 && saturation == 0) return null;
        android.graphics.Bitmap.Config config = input.getConfig();
        if (config != android.graphics.Bitmap.Config.ARGB_8888
                && config != android.graphics.Bitmap.Config.RGBA_F16
                && config != android.graphics.Bitmap.Config.RGBA_1010102) return null;
        int w = input.getWidth(), h = input.getHeight();
        if (w <= 0 || h <= 0) return null;
        android.graphics.Bitmap flat;
        try {
            if (config == android.graphics.Bitmap.Config.ARGB_8888) {
                flat = input.copy(android.graphics.Bitmap.Config.ARGB_8888, true);
            } else {
                flat = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
                new android.graphics.Canvas(flat).drawBitmap(input, 0f, 0f,
                    new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
            }
        } catch (Throwable error) {
            Log.i(TAG, "tone/sat flatten failed: " + error);
            return null;
        }
        try {
            int[] toneLut = new int[256];
            double gamma = Math.pow(2.0, -tone / 100.0);
            for (int i = 0; i < 256; i++) {
                toneLut[i] = (int) Math.round(255.0 * Math.pow(i / 255.0, gamma));
            }
            float satMul = Math.max(0f, 1f + saturation / 100f);
            int rowsPerStrip = Math.max(1, 4 * 1024 * 1024 / w);  // ~16MB pixel strips, at least one row
            int[] pixels = new int[w * rowsPerStrip];
            for (int top = 0; top < h; ) {
                int rows = Math.min(h - top, rowsPerStrip);
                flat.getPixels(pixels, 0, w, 0, top, w, rows);
                for (int i = 0, count = w * rows; i < count; i++) {
                    int p = pixels[i];
                    int r = toneLut[(p >> 16) & 0xFF];
                    int g = toneLut[(p >> 8) & 0xFF];
                    int b = toneLut[p & 0xFF];
                    float luma = 0.299f * r + 0.587f * g + 0.114f * b;
                    pixels[i] = (p & 0xFF000000)
                        | (channel(luma, r, satMul) << 16)
                        | (channel(luma, g, satMul) << 8)
                        | channel(luma, b, satMul);
                }
                flat.setPixels(pixels, 0, w, 0, top, w, rows);
                top += rows;
            }
        } catch (Throwable error) {
            flat.recycle();
            Log.i(TAG, "tone/sat pixel pass failed: " + error);
            return null;
        }
        if (config == android.graphics.Bitmap.Config.ARGB_8888) {
            if (android.os.Build.VERSION.SDK_INT >= 34) {  // keep an Ultra HDR gainmap if any
                try {
                    android.graphics.Gainmap gainmap = input.getGainmap();
                    if (gainmap != null && flat.getGainmap() == null) flat.setGainmap(gainmap);
                } catch (Throwable ignored) {
                    // gainmaps are optional extras
                }
            }
            return flat;
        }
        try {  // back into the pipeline's own config + color space
            android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(w, h, config, true, input.getColorSpace());
            new android.graphics.Canvas(out).drawBitmap(flat, 0f, 0f,
                new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
            flat.recycle();
            return out;
        } catch (Throwable error) {
            flat.recycle();
            Log.i(TAG, "tone/sat re-encode failed: " + error);
            return null;
        }
    }

    private static int channel(float luma, int value, float satMul) {
        float c = luma + (value - luma) * satMul;
        return c <= 0f ? 0 : c >= 255f ? 255 : (int) c;
    }
}

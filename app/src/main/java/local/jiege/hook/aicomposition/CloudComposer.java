package local.jiege.hook.aicomposition;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.util.Log;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Asks OPPO's own vision-aesthetics model for a composition, the same one the gallery's AI
 * inspiration mode calls. The request leaves the device (frame to S3, then
 * {@code /imageComposition/infer}), so it is one-shot per user action and never part of the
 * per-frame tracking loop -- it answers "what would a model do with this scene", not "where did
 * the subject move".
 *
 * <p>The camera ships a client for exactly this unit but returns false before ever sending, so the
 * call is made here through the SDK copy the gallery carries.
 */
final class CloudComposer {
    private static final String TAG = "CompCloud";
    private static final String UNIT = "image_ai_composition";
    private static final String CLIENT = "com.oplus.aiunit.eraser.client.AICompositionClient";
    private static final String CALLBACK = "com.oplus.aiunit.eraser.client.AICompositionCallback";
    /** The service is a round trip to OPPO; anything past this is dead in the water. */
    private static final long TIMEOUT = 8000L;

    /** Reference frame the camera's crop coordinates are expressed in. */
    private static final int REF_W = 768;
    private static final int REF_H = 1024;

    /** Widest zoom worth suggesting; the modules that work on this camera stop at 10x. */
    private static final double MAX_ZOOM = 10.0;
    /**
     * How much tighter the second suggestion is than the first. The stock demo ships 3.0x and
     * 4.76x side by side (a ratio of 1.59), and the card row in the panel is laid out for exactly
     * that shape, so the cloud answer follows it.
     */
    private static final double TIGHT_RATIO = 1.15;
    private static final String TIGHT_DESC =
        "视觉美学模型建议拉近取景，突出主体细节，弱化背景干扰。";

    interface Listener {
        /** @param json camera-ready composition JSON, or null when the model had nothing to say. */
        void onComposed(String json, String summary);
    }

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "comp-cloud");
        thread.setDaemon(true);
        return thread;
    });

    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static volatile Object client;
    private static volatile boolean clientDead;
    private static volatile int requests;
    private static volatile String lastStatus = "idle";

    private CloudComposer() {}

    static boolean enabled() {
        // Opt-in, and read live: the camera process cannot read the module's preferences (different
        // uid and SELinux categories), so the settings switch mirrors into a property the way the
        // natural-mode controls do. Empty means off -- inspiration composition uploads a frame, so
        // it has to be asked for. persist.omnicam.cloudcomp stays as a per-run override for tests:
        // anything but empty wins.
        String forced = prop("persist.omnicam.cloudcomp", "");
        if ("1".equals(forced)) return true;
        if ("0".equals(forced)) return false;
        return "1".equals(prop("persist.omnicam.comp.inspiration", "0"));
    }

    static String status() {
        return lastStatus + " (requests=" + requests + ")";
    }

    /** True while a request is in flight, so the caller does not stack them up. */
    static boolean busy() {
        return BUSY.get();
    }

    static void submit(Bitmap frame, Listener listener) {
        if (frame == null || listener == null) return;
        if (!enabled()) {
            listener.onComposed(null, "cloud off");
            return;
        }
        if (!BUSY.compareAndSet(false, true)) {
            listener.onComposed(null, "busy");
            return;
        }
        Bitmap copy = null;
        try {
            // The caller recycles its sample; this copy outlives it.
            copy = frame.copy(Bitmap.Config.ARGB_8888, false);
        } catch (Throwable error) {
            Log.i(TAG, "copy failed: " + error);
        }
        if (copy == null) {
            BUSY.set(false);
            listener.onComposed(null, "copy failed");
            return;
        }
        final Bitmap upload = copy;
        WORKER.execute(() -> {
            String json = null;
            String summary = "failed";
            long started = android.os.SystemClock.uptimeMillis();
            try {
                Object callback = newCallback();
                if (callback == null) {
                    summary = "no callback";
                } else {
                    Object target = client();
                    if (target == null) {
                        summary = "no client";
                    } else {
                        // Built on the main thread (it binds a service), driven from this one: the
                        // SDK asserts "The call must be in a worker thread" on every process().
                        Method process = target.getClass().getMethod(
                            "process", Bitmap.class, callback.getClass().getInterfaces()[0]);
                        Object code = process.invoke(target, upload, callback);
                        summary = "sent (" + code + ")";
                        Result result = await();
                        if (result != null) {
                            json = result.json;
                            summary = result.summary;
                        }
                    }
                }
            } catch (Throwable error) {
                summary = "error " + error;
                Log.e(TAG, "composition failed", error);
            } finally {
                if (!upload.isRecycled()) upload.recycle();
                BUSY.set(false);
            }
            requests++;
            long cost = android.os.SystemClock.uptimeMillis() - started;
            lastStatus = summary;
            Log.i(TAG, String.format(Locale.US, "cloud #%d %dms %s -> %s",
                requests, cost, summary, json == null ? "null" : json.substring(0, Math.min(160, json.length()))));
            listener.onComposed(json, summary);
        });
    }

    /* ------------------------------------------------------------------ plumbing */

    private static Object client() {
        if (clientDead) return null;
        Object ready = client;
        if (ready != null) return ready;
        synchronized (CloudComposer.class) {
            if (client != null) return client;
            if (clientDead) return null;
            Object made = AiUnitBridge.newClient(CLIENT, UNIT);
            if (made == null) {
                clientDead = true;
                Log.i(TAG, "client unavailable");
                return null;
            }
            client = made;
            Log.i(TAG, "client ready for unit " + UNIT);
            return made;
        }
    }

    /**
     * The SDK callback is an interface, so a dynamic proxy stands in for it. Results are parked
     * here and picked up by the thread that is waiting on {@link #await()}.
     */
    private static volatile Result pending;

    private static Object newCallback() throws Exception {
        ClassLoader loader = AiUnitBridge.sdk();
        if (loader == null) return null;
        Class<?> type = Class.forName(CALLBACK, false, loader);
        return Proxy.newProxyInstance(loader, new Class<?>[]{type}, (proxy, method, args) -> {
            String name = method.getName();
            if ("onComposeResult".equals(name) && args != null && args.length == 1) {
                pending = read(args[0]);
                synchronized (CloudComposer.class) {
                    CloudComposer.class.notifyAll();
                }
            } else if ("toString".equals(name)) {
                return "omnicam-cloud-callback";
            } else if ("hashCode".equals(name)) {
                return System.identityHashCode(proxy);
            } else if ("equals".equals(name)) {
                return proxy == args[0];
            }
            return null;
        });
    }

    private static Result await() {
        long deadline = android.os.SystemClock.uptimeMillis() + TIMEOUT;
        synchronized (CloudComposer.class) {
            while (pending == null) {
                long left = deadline - android.os.SystemClock.uptimeMillis();
                if (left <= 0) break;
                try {
                    CloudComposer.class.wait(left);
                } catch (InterruptedException stop) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        Result result = pending;
        pending = null;
        return result;
    }

    /**
     * Turns the SDK's answer into the JSON the camera's own composition UI renders. The model
     * returns one or more crops with a ratio label; the first box is used and the rest are logged,
     * because the camera accepts a single recommendation per event.
     */
    private static Result read(Object value) {
        if (value == null) return new Result(null, "null result");
        try {
            Object boxes = call(value, "getCropBoxes");
            String raw = String.valueOf(call(value, "getJsonStr"));
            int scene = asInt(call(value, "getSceneCategory"));
            Log.i(TAG, "raw answer scene=" + scene + " boxes="
                + (boxes instanceof List ? ((List<?>) boxes).size() : -1)
                + " json=" + raw.substring(0, Math.min(600, raw.length())));
            if (!(boxes instanceof List)) return new Result(null, "no boxes");
            List<?> list = (List<?>) boxes;
            if (list.isEmpty()) return new Result(null, "empty boxes");
            double[] best = null;
            String label = "";
            for (Object item : list) {
                double[] coordinates = asDoubles(call(item, "getCoordinates"));
                String ratio = String.valueOf(call(item, "getRatioType"));
                Log.i(TAG, "  box " + (coordinates == null ? "null" : coordinates.length + " nums")
                    + " ratio=" + ratio);
                if (coordinates == null || coordinates.length < 4) continue;
                if (best == null) {
                    best = coordinates;
                    label = ratio;
                }
            }
            if (best == null) return new Result(null, "unusable box");
            return new Result(jsonOf(best, label), "ok " + label);
        } catch (Throwable error) {
            Log.e(TAG, "result parse failed", error);
            return new Result(null, "parse error");
        }
    }

    /**
     * The model reports a box in normalised coordinates. Which normalisation is not documented, but
     * a real answer settles it: {@code [0, 0.0366, 0.6997, 0.5600]} labelled {@code 4:3} only comes
     * out 4:3 when both axes are scaled by the image <em>width</em> (537 x 402); scaling y by the
     * height would give a square. The camera also wants {@code srcCrop} as
     * {@code [left, top, right, bottom]} with {@code zoom = 768 / (right - left)}, not x/y/w/h --
     * feeding it w/h makes it read the width as the right edge and zoom in twice as far.
     */
    private static String jsonOf(double[] box, String label) {
        double a = box[0], b = box[1], c = box[2], d = box[3];
        // [x1,y1,x2,y2]
        double w1 = Math.abs(c - a), h1 = Math.abs(d - b);
        // [x,y,w,h]
        double w2 = c, h2 = d;
        double[] pick = null;
        if (w1 > 0.05 && w1 <= 1.0 && h1 > 0.05 && h1 <= 1.0) {
            pick = new double[]{Math.min(a, c), Math.min(b, d), w1, h1};
        } else if (w2 > 0.05 && w2 <= 1.0 && h2 > 0.05 && h2 <= 1.0) {
            pick = new double[]{a, b, w2, h2};
        }
        if (pick == null) return null;
        int x = (int) Math.round(pick[0] * REF_W);
        int y = (int) Math.round(pick[1] * REF_W);
        int w = (int) Math.round(pick[2] * REF_W);
        int h = (int) Math.round(pick[3] * REF_W);
        w = Math.max(2, Math.min(REF_W, w));
        // The answer comes back as a 4:3 landscape box, but this camera shoots 3:4 portrait. Keep
        // the model's centre and its chosen width, then extend to portrait -- a landscape crop
        // pushed straight in would letterbox.
        h = (int) Math.round(w * (REF_H / (double) REF_W));
        x = Math.max(0, Math.min(REF_W - w, x));
        y = Math.max(0, Math.min(REF_H - h, y));
        // A box that only shaves the frame comes back as "invalid zoom result" and the photo is
        // dropped, so tighten the model's box around its own centre until the crop reaches the
        // wanted zoom. The centre is what the model actually judged; only the margin shrinks.
        int wanted = (int) Math.round(REF_W / zoomFloor());
        if (wanted > 2 && wanted < w) {
            int cx = x + w / 2;
            int cy = y + h / 2;
            w = wanted;
            x = cx - w / 2;
            y = cy - h / 2;
            h = (int) Math.round(w * (REF_H / (double) REF_W));
            x = Math.max(0, Math.min(REF_W - w, x));
            y = Math.max(0, Math.min(REF_H - h, y));
        }
        if (w < 2 || h < 2) return null;
        double zoom = REF_W / (double) w;
        // 10x is the cap the working modules use; past that the crop is upscaled mush.
        if (zoom < 1.0 || zoom > MAX_ZOOM) return null;
        String ratio = label == null || label.isEmpty() || "null".equals(label) ? "" : label;
        // The model labels each box with an output ratio, not with a judgement; "origin" means it
        // kept the frame's own ratio, which is what this camera already shoots. Rewriting that as
        // "suggests origin composition" reads like a bug.
        String desc;
        switch (ratio) {
            case "origin":
                desc = "视觉美学模型建议保持当前比例，主体落在三分线上，画面留白更均衡。";
                break;
            case "1:1":
                desc = "视觉美学模型建议方形取景，主体居中偏三分线，画面更聚焦。";
                break;
            case "4:3":
            case "16:9":
                desc = "视觉美学模型建议更宽的取景，容纳更多环境，主体落在三分线上。";
                break;
            default:
                desc = "视觉美学模型给出的取景建议：主体落在三分线附近，画面留白更均衡。";
                break;
        }
        // The panel that shows the suggestion cards only appears when the answer carries more than
        // one crop: a single crop is applied silently and the user is left with nothing to look at,
        // which reads as "no suggestion at all". The stock demo always ships two (3.0x and 4.76x),
        // so mirror that shape -- the model's framing plus a tighter variant of the same centre.
        int tw = (int) Math.round(w / TIGHT_RATIO);
        tw = Math.max(2, Math.min(w - 1, tw));
        double tzoom = REF_W / (double) tw;
        if (tzoom > MAX_ZOOM) {
            tw = (int) Math.round(REF_W / MAX_ZOOM);
            tzoom = REF_W / (double) tw;
        }
        int th = (int) Math.round(tw * (REF_H / (double) REF_W));
        int ccx = x + w / 2, ccy = y + h / 2;
        int tx = Math.max(0, Math.min(REF_W - tw, ccx - tw / 2));
        int ty = Math.max(0, Math.min(REF_H - th, ccy - th / 2));
        boolean two = tzoom > zoom + 0.05 && tw >= 2;
        if (!two) {
            return String.format(Locale.US,
                "{\"outputNum\":1,\"crops\":[{\"zoom\":%.4f,\"srcCrop\":[%d,%d,%d,%d],"
                    + "\"version\":\"omnicam-cloud\",\"confidenceLevel\":100,"
                    + "\"compositionDesc\":{\"title\":\"AI 灵感构图\",\"desc1\":\"%s\","
                    + "\"desc2\":\"\",\"desc3\":\"\"}}]}",
                zoom, x, y, x + w, y + h, desc);
        }
        return String.format(Locale.US,
            "{\"outputNum\":2,\"crops\":["
                + "{\"zoom\":%.4f,\"srcCrop\":[%d,%d,%d,%d],"
                + "\"version\":\"omnicam-cloud\",\"confidenceLevel\":100,"
                + "\"compositionDesc\":{\"title\":\"AI 灵感构图\",\"desc1\":\"%s\","
                + "\"desc2\":\"\",\"desc3\":\"\"}},"
                + "{\"zoom\":%.4f,\"srcCrop\":[%d,%d,%d,%d],"
                + "\"version\":\"omnicam-cloud-tight\",\"confidenceLevel\":100,"
                + "\"compositionDesc\":{\"title\":\"AI 灵感构图\",\"desc1\":\"%s\","
                + "\"desc2\":\"\",\"desc3\":\"\"}}]}",
            zoom, x, y, x + w, y + h, desc,
            tzoom, tx, ty, tx + tw, ty + th, TIGHT_DESC);
    }

    private static Object call(Object target, String name) {
        try {
            Method method = target.getClass().getMethod(name);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Throwable error) {
            return null;
        }
    }

    private static int asInt(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : -1;
    }

    private static double[] asDoubles(Object value) {
        if (value instanceof double[]) return (double[]) value;
        if (value instanceof int[]) {
            int[] source = (int[]) value;
            double[] out = new double[source.length];
            for (int i = 0; i < source.length; i++) out[i] = source[i];
            return out;
        }
        if (value instanceof float[]) {
            float[] source = (float[]) value;
            double[] out = new double[source.length];
            for (int i = 0; i < source.length; i++) out[i] = source[i];
            return out;
        }
        return null;
    }

    private static final class Result {
        final String json;
        final String summary;

        Result(String json, String summary) {
            this.json = json;
            this.summary = summary;
        }
    }

    /**
     * Zoom the crop is tightened to when the model's own box is looser than that. The camera reads a
     * wide crop as "no suggestion at all", so this is deliberately not the model's choice -- it is
     * the smallest tightening that still counts as a composition.
     */
    private static double zoomFloor() {
        try {
            double value = Double.parseDouble(prop("persist.omnicam.comp.zoom", "3.0"));
            if (value >= 1.0 && value <= MAX_ZOOM) return value;
        } catch (Throwable ignored) {
            // fall through to the default
        }
        return 3.0;
    }

    private static String prop(String key, String fallback) {
        try {
            Class<?> system = Class.forName("android.os.SystemProperties");
            Method get = system.getMethod("get", String.class, String.class);
            Object value = get.invoke(null, key, fallback);
            return value == null ? fallback : String.valueOf(value).trim();
        } catch (Throwable error) {
            return fallback;
        }
    }

    /** Unused placeholder to keep Rect import meaningful for future cropping. */
    static Rect fullFrame() {
        return new Rect(0, 0, REF_W, REF_H);
    }
}

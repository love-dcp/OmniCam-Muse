package local.jiege.hook.video;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.widget.Toast;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Settings;
import local.jiege.settings.ModuleSettings;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Replaces the gallery-only LUT picker with the system document picker.
 *
 * <p>The pro-video panel starts {@code com.android.camera.action.SELECT_LUT} in the stock gallery
 * and receives a JSON list in {@code select_lut_list_json}. The gallery page only exposes its own
 * LUT library. This hook lets the user choose one or more .cube files from any document provider,
 * including an SD card, or recursively import a whole folder. The selected streams are cached for
 * the camera process and converted to the same JSON shape, so the stock copy, deduplication,
 * persistence and panel refresh code still runs.
 */
final class LogLutImport {
    private static final String TAG = "LogLutImport";
    private static final String CAMERA_CLASS = "com.oplus.camera.Camera";
    private static final String SELECT_LUT = "com.android.camera.action.SELECT_LUT";
    private static final String GALLERY = "com.coloros.gallery3d";
    private static final String DOCUMENTS_UI = "com.android.documentsui";
    private static final String SELECTED_LUT_LIST = "has_selected_lut_list";
    private static final String RESULT_JSON = "select_lut_list_json";
    private static final String FILE_PATH = "filePath";
    private static final String TITLE = "title";
    private static final int REQUEST_LUT = 11115;
    private static final int MAX_LUTS = 20;
    private static final int MAX_TREE_DIRECTORIES = 2000;
    private static final long MAX_LUT_BYTES = 64L * 1024L * 1024L;
    private static final String CACHE_DIR = "omnicam_lut";
    private static final String EXTRA_SKIP_IMPORT_HOOK = "local.jiege.extra.SKIP_LUT_IMPORT_HOOK";

    private static final Map<Activity, Integer> LIMITS =
        Collections.synchronizedMap(new WeakHashMap<>());

    private LogLutImport() {}

    static void install(ClassLoader classLoader) {
        try {
            Class<?> camera = XposedHelpers.findClass(CAMERA_CLASS, classLoader);
            XposedBridge.hookAllMethods(Activity.class, "startActivityForResult",
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        interceptStart(param);
                    }
                });
            XposedBridge.hookAllMethods(camera, "onActivityResult", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    interceptResult(param);
                }
            });
            Log.i(TAG, "LUT path picker installed");
        } catch (Throwable error) {
            Log.e(TAG, "LUT path picker unavailable", error);
        }
    }

    private static boolean enabled() {
        return Settings.get(ModuleSettings.LOG_LUT);
    }

    private static void interceptStart(XC_MethodHook.MethodHookParam param) {
        if (!enabled() || param.args.length < 2 || !(param.args[0] instanceof Intent)) return;
        if (!(param.thisObject instanceof Activity)) return;

        Intent stock = (Intent) param.args[0];
        Object code = param.args[1];
        if (!(code instanceof Integer) || (Integer) code != REQUEST_LUT) return;
        if (stock.getBooleanExtra(EXTRA_SKIP_IMPORT_HOOK, false)) return;
        if (!SELECT_LUT.equals(stock.getAction()) || !GALLERY.equals(stock.getPackage())) return;

        Activity activity = (Activity) param.thisObject;
        if (activity.isFinishing() || activity.isDestroyed()) return;
        Log.i(TAG, "intercepting stock LUT picker for " + activity.getClass().getName());

        // The stock caller only opens the gallery. Return immediately and drive our picker with the
        // same request code so Camera.onActivityResult remains the single result receiver.
        param.setResult(null);
        int limit = remainingSlots(stock);
        LIMITS.put(activity, limit);
        showPickerChoice(activity);
    }

    private static int remainingSlots(Intent stock) {
        int used = 0;
        ArrayList<String> selected = stock.getStringArrayListExtra(SELECTED_LUT_LIST);
        if (selected != null) used = selected.size();
        return Math.max(1, MAX_LUTS - used);
    }

    private static void showPickerChoice(Activity activity) {
        activity.runOnUiThread(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) {
                cancelInitialSelection(activity);
                return;
            }
            AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("导入 LUT")
                .setItems(new CharSequence[] {
                    "选择 LUT 文件（可多选）",
                    "导入整个文件夹"
                }, (ignored, which) -> launchPicker(activity, which == 1))
                .setNegativeButton("取消", (ignored, which) -> cancelInitialSelection(activity))
                .setOnCancelListener(ignored -> cancelInitialSelection(activity))
                .create();
            dialog.show();
        });
    }

    private static void launchPicker(Activity activity, boolean folder) {
        Intent picker = folder
            ? new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            : new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        picker.putExtra(Intent.EXTRA_LOCAL_ONLY, true)
            .putExtra(Intent.EXTRA_TITLE, folder ? "选择包含 LUT 的文件夹" : "选择 LUT 文件")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);

        // ColorOS File Manager advertises OPEN_DOCUMENT_TREE but closes that picker without a
        // usable tree result. Prefer AOSP DocumentsUI on ROMs that ship both, then fall back to
        // the normal resolver so this still works on devices without DocumentsUI.
        boolean documentsUi = folder;
        if (documentsUi) picker.setPackage(DOCUMENTS_UI);
        try {
            activity.startActivityForResult(picker, REQUEST_LUT);
        } catch (ActivityNotFoundException error) {
            if (documentsUi) {
                picker.setPackage(null);
                try {
                    activity.startActivityForResult(picker, REQUEST_LUT);
                    return;
                } catch (ActivityNotFoundException ignored) {
                    // Report one consistent error below.
                }
            }
            LIMITS.remove(activity);
            toast(activity, "系统文件选择器不可用");
            cancelInitialSelection(activity);
        }
    }

    private static void cancelInitialSelection(Activity activity) {
        LIMITS.remove(activity);
        try {
            XposedHelpers.callMethod(activity, "onActivityResult", REQUEST_LUT,
                Activity.RESULT_CANCELED, new Intent());
        } catch (Throwable error) {
            Log.e(TAG, "could not close LUT selection", error);
        }
    }

    private static void interceptResult(XC_MethodHook.MethodHookParam param) {
        if (!enabled() || param.args.length < 3 || !(param.thisObject instanceof Activity)) return;
        if (!(param.args[0] instanceof Integer) || (Integer) param.args[0] != REQUEST_LUT) return;
        if (!(param.args[1] instanceof Integer)) return;

        Activity activity = (Activity) param.thisObject;
        Integer pending = LIMITS.remove(activity);
        int resultCode = (Integer) param.args[1];
        Intent result = param.args[2] instanceof Intent ? (Intent) param.args[2] : null;
        Log.i(TAG, "picker result code=" + resultCode
            + " uri=" + (result == null ? "null" : result.getData())
            + " action=" + (result == null ? "null" : result.getAction())
            + " flags=" + (result == null ? "null"
                : "0x" + Integer.toHexString(result.getFlags())));
        if (resultCode != Activity.RESULT_OK) return;
        if (result == null) {
            param.args[1] = Activity.RESULT_CANCELED;
            return;
        }

        Intent data = result;
        if (data.getStringExtra(RESULT_JSON) != null) return;

        try {
            ImportResult imported = importSelection(activity, data,
                pending == null ? MAX_LUTS : pending);
            if (imported.count == 0) {
                param.args[1] = Activity.RESULT_CANCELED;
                toast(activity, imported.message);
                return;
            }
            data.putExtra(RESULT_JSON, imported.json.toString());
            if (imported.message != null) toast(activity, imported.message);
            Log.i(TAG, "imported " + imported.count + " LUT(s) through system picker");
        } catch (Throwable error) {
            Log.e(TAG, "could not prepare selected LUT files", error);
            param.args[1] = Activity.RESULT_CANCELED;
            toast(activity, "无法读取所选 LUT");
        }
    }

    private static ImportResult importSelection(Activity activity, Intent data, int limit)
        throws Exception {
        ContentResolver resolver = activity.getContentResolver();
        Uri tree = data.getData();
        List<Candidate> candidates;
        boolean folder = tree != null && DocumentsContract.isTreeUri(tree);
        Log.i(TAG, "import selection uri=" + tree + " folder=" + folder
            + " limit=" + limit);
        if (folder) {
            // Read one extra entry so the UI can explain when the 20-item camera limit truncated
            // a folder import.
            candidates = treeCandidates(resolver, tree, limit + 1);
        } else {
            candidates = directCandidates(data);
        }
        Log.i(TAG, "import selection found " + candidates.size() + " candidate(s)");
        if (candidates.isEmpty()) {
            return ImportResult.empty(folder
                ? "所选文件夹中没有找到 .cube 文件"
                : "请选择 .cube 格式的 LUT 文件");
        }

        File batch = new File(new File(activity.getCacheDir(), CACHE_DIR),
            String.valueOf(System.currentTimeMillis()));
        if (!batch.isDirectory() && !batch.mkdirs()) {
            throw new IllegalStateException("无法创建 LUT 缓存目录");
        }

        JSONArray json = new JSONArray();
        Set<String> usedNames = new HashSet<>();
        int skipped = 0;
        int accepted = 0;
        for (Candidate candidate : candidates) {
            if (accepted >= limit) break;
            String name = candidate.name;
            if (name == null || name.isEmpty()) name = displayName(resolver, candidate.uri);
            if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".cube")) {
                skipped++;
                continue;
            }

            String unique = uniqueFileName(safeFileName(name), usedNames);
            String title = uniqueTitle(unique, usedNames);
            File target = new File(batch, unique);
            if (!copy(resolver, candidate.uri, target)) {
                skipped++;
                target.delete();
                continue;
            }

            JSONObject item = new JSONObject();
            item.put(TITLE, title);
            item.put(FILE_PATH, target.getAbsolutePath());
            json.put(item);
            accepted++;
        }

        if (accepted == 0) {
            return ImportResult.empty("所选内容中没有可读取的 .cube 文件");
        }
        String message = null;
        if (candidates.size() > limit) {
            message = "最多还能导入 " + limit + " 个 LUT，已导入前 " + accepted + " 个";
        } else if (skipped > 0) {
            message = "已导入 " + accepted + " 个 LUT，跳过 " + skipped + " 个无法读取的文件";
        }
        return new ImportResult(json, accepted, message);
    }

    private static List<Candidate> directCandidates(Intent data) {
        LinkedHashSet<Uri> uris = new LinkedHashSet<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null) uris.add(uri);
            }
        }
        Uri uri = data.getData();
        if (uri != null) uris.add(uri);
        List<Candidate> result = new ArrayList<>();
        for (Uri item : uris) result.add(new Candidate(item, null));
        return result;
    }

    private static List<Candidate> treeCandidates(ContentResolver resolver, Uri tree, int limit) {
        ArrayDeque<Uri> directories = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        List<Candidate> found = new ArrayList<>();
        try {
            // getDocumentId() expects a /document/ URI, while the picker returns a /tree/ URI.
            // Convert the root first so the BFS can use the same path for root and subdirectories.
            String rootId = DocumentsContract.getTreeDocumentId(tree);
            Uri root = DocumentsContract.buildDocumentUriUsingTree(tree, rootId);
            Log.i(TAG, "tree authority=" + tree.getAuthority() + " rootId=" + rootId
                + " rootUri=" + root);
            directories.add(root);
        } catch (Throwable error) {
            Log.e(TAG, "could not read selected tree", error);
            return found;
        }

        while (!directories.isEmpty() && found.size() < limit
            && visited.size() < MAX_TREE_DIRECTORIES) {
            Uri directory = directories.removeFirst();
            String directoryId;
            try {
                directoryId = DocumentsContract.getDocumentId(directory);
            } catch (Throwable error) {
                continue;
            }
            if (directoryId == null || !visited.add(directoryId)) continue;

            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, directoryId);
            try (Cursor cursor = resolver.query(children, new String[] {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            }, null, null, null)) {
                if (cursor == null) {
                    Log.i(TAG, "tree folder query returned null: " + directoryId);
                    continue;
                }
                int entries = 0;
                while (cursor.moveToNext() && found.size() < limit) {
                    entries++;
                    String id = cursor.getString(0);
                    String name = cursor.getString(1);
                    String mime = cursor.getString(2);
                    if (id == null) continue;
                    Uri document = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                        directories.addLast(document);
                    } else if (name != null
                        && name.toLowerCase(Locale.ROOT).endsWith(".cube")) {
                        found.add(new Candidate(document, name));
                    }
                }
                Log.i(TAG, "tree folder " + directoryId + " entries=" + entries);
            } catch (Throwable error) {
                Log.e(TAG, "could not read folder " + directoryId, error);
            }
        }
        found.sort(Comparator.comparing(candidate -> candidate.name == null
            ? "" : candidate.name.toLowerCase(Locale.ROOT)));
        Log.i(TAG, "tree scan complete directories=" + visited.size()
            + " candidates=" + found.size());
        return found;
    }

    private static String displayName(ContentResolver resolver, Uri uri) {
        try (Cursor cursor = resolver.query(uri,
            new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.isEmpty()) return name;
            }
        } catch (Throwable ignored) {
            // Fall through to the document id, which is still useful for the .cube suffix check.
        }
        String id = uri.getLastPathSegment();
        if (id == null) return null;
        int colon = id.lastIndexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
    }

    private static String safeFileName(String name) {
        String base = name.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) base = base.substring(slash + 1);
        base = base.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (base.isEmpty()) base = "lut.cube";
        if (!base.toLowerCase(Locale.ROOT).endsWith(".cube")) base += ".cube";
        return base;
    }

    private static String uniqueFileName(String name, Set<String> used) {
        String probe = name;
        int suffix = 2;
        while (used.contains(probe.toLowerCase(Locale.ROOT))) {
            String stem = name.substring(0, name.length() - 5);
            probe = stem + "_" + suffix++ + ".cube";
        }
        used.add(probe.toLowerCase(Locale.ROOT));
        return probe;
    }

    private static String uniqueTitle(String fileName, Set<String> used) {
        String title = fileName.substring(0, fileName.length() - 5);
        if (title.isEmpty()) title = "lut";
        String probe = title;
        int suffix = 2;
        while (used.contains("title:" + probe.toLowerCase(Locale.ROOT))) {
            probe = title + " (" + suffix++ + ")";
        }
        used.add("title:" + probe.toLowerCase(Locale.ROOT));
        return probe;
    }

    private static boolean copy(ContentResolver resolver, Uri source, File target) {
        try (InputStream in = resolver.openInputStream(source);
             OutputStream out = new FileOutputStream(target)) {
            if (in == null) return false;
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int count;
            while ((count = in.read(buffer)) != -1) {
                total += count;
                if (total > MAX_LUT_BYTES) return false;
                out.write(buffer, 0, count);
            }
            out.flush();
            return true;
        } catch (Throwable error) {
            Log.e(TAG, "could not copy " + source, error);
            return false;
        }
    }

    private static void toast(Activity activity, String message) {
        if (message == null) return;
        new Handler(Looper.getMainLooper()).post(
            () -> Toast.makeText(activity, message, Toast.LENGTH_LONG).show());
    }

    private static final class Candidate {
        final Uri uri;
        final String name;

        Candidate(Uri uri, String name) {
            this.uri = uri;
            this.name = name;
        }
    }

    private static final class ImportResult {
        final JSONArray json;
        final int count;
        final String message;

        ImportResult(JSONArray json, int count, String message) {
            this.json = json;
            this.count = count;
            this.message = message;
        }

        static ImportResult empty(String message) {
            return new ImportResult(new JSONArray(), 0, message);
        }
    }
}

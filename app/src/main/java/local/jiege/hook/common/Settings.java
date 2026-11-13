package local.jiege.hook.common;

import de.robv.android.xposed.XSharedPreferences;
import java.io.File;
import java.util.Map;
import local.jiege.settings.ModuleSettings;

/**
 * Reads the module settings inside hooked apps. Changes apply on the next read.
 *
 * <p>Two sources, in this order:
 *
 * <ol>
 *   <li>the preferences file itself ({@link PreferencesFile}), re-parsed whenever its stamp moves.
 *       This is the one the settings app actually wrote: LSPosed keeps the directory world-readable
 *       for the hooked app, and the master filter store in the same directory is read the same way.
 *   <li>{@link XSharedPreferences}, for the case where the file is missing or unreadable. Its copy
 *       only refreshes when the module writes through the SharedPreferences API, so a read can be
 *       empty or stale even though the file already holds the new value — which is exactly the
 *       "changed the setting, camera did nothing" report that the file-first order fixes.
 * </ol>
 */
public final class Settings {
    private static final String MODULE_PACKAGE = "local.jiege.camera";
    private static XSharedPreferences preferences;
    /** Parsed preferences file and the stamp (mtime + length) it was parsed at. */
    private static Map<String, Object> values;
    private static long valuesStamp = Long.MIN_VALUE;

    private Settings() {}

    public static synchronized boolean get(String key) {
        Object value = value(key);
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof String) return Boolean.parseBoolean((String) value);
        if (value instanceof Number) return ((Number) value).intValue() != 0;
        return ModuleSettings.defaultValue(key);
    }

    public static synchronized int getInt(String key) {
        Object value = value(key);
        if (value instanceof Number) return ((Number) value).intValue();
        if (value instanceof String) {
            try {
                return Integer.parseInt(((String) value).trim());
            } catch (NumberFormatException ignored) {
                // fall through to the default
            }
        }
        return ModuleSettings.defaultInt(key);
    }

    public static synchronized String getString(String key) {
        Object value = value(key);
        if (value instanceof String) return (String) value;
        return ModuleSettings.defaultString(key);
    }

    /**
     * One line describing where the values came from and how many there are. Printed once at camera
     * start so a "the setting did nothing" report can be settled from a log instead of a guess.
     */
    public static synchronized String describe() {
        File file = file();
        Map<String, Object> parsed = values();
        int count = parsed == null ? -1 : parsed.size();
        int proxied = -1;
        try {
            Map<String, ?> all = preferences().getAll();
            proxied = all == null ? -1 : all.size();
        } catch (Throwable ignored) {
            // reported as -1
        }
        return "prefs file=" + (file == null ? "(none)" : file.getAbsolutePath())
            + " readable=" + (count >= 0) + " keys=" + count
            + " xposed=" + proxied + " source=" + (count > 0 ? "file" : proxied > 0 ? "xposed" : "defaults");
    }

    private static Object value(String key) {
        Map<String, Object> parsed = values();
        if (parsed != null && parsed.containsKey(key)) return parsed.get(key);
        try {
            Map<String, ?> all = preferences().getAll();
            if (all != null) return all.get(key);
        } catch (Throwable ignored) {
            // Unreadable proxy: the default applies.
        }
        return null;
    }

    private static Map<String, Object> values() {
        File file = file();
        if (file == null) return values;
        long stamp = file.lastModified() * 1_000_003L + file.length();
        Map<String, Object> cached = values;
        if (cached != null && stamp == valuesStamp) return cached;
        Map<String, Object> parsed = PreferencesFile.read(file);
        if (parsed == null) return cached;
        values = parsed;
        valuesStamp = stamp;
        return parsed;
    }

    private static File file() {
        try {
            return preferences().getFile();
        } catch (Throwable error) {
            return null;
        }
    }

    private static XSharedPreferences preferences() {
        if (preferences == null) {
            preferences = new XSharedPreferences(MODULE_PACKAGE, ModuleSettings.FILE);
            // XSharedPreferences loads its file asynchronously on construction; a read right after
            // construction sees an empty map and falls back to defaults. reload() blocks until the
            // backing map is loaded, so force it once here.
            preferences.reload();
        } else if (preferences.hasFileChanged()) {
            preferences.reload();
        }
        return preferences;
    }
}

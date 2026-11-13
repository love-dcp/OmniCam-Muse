package local.jiege.hook.common;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a SharedPreferences XML file directly, without going through
 * {@link de.robv.android.xposed.XSharedPreferences}.
 *
 * <p>Why: the camera process sees the module's preferences through LSPosed's xposedsharedprefs
 * proxy, which keeps its own copy and only refreshes it when the module writes through the
 * SharedPreferences API. An XSharedPreferences read can therefore come back empty or stale while
 * the file on disk already holds the new value — the documented "settings changed but nothing
 * happened" failure, and the reason the soft-light switches live in system properties instead.
 * The file itself is readable (LSPosed makes the preferences directory world-readable; the master
 * filter store in the same directory is read by the camera the same way), so it is parsed here and
 * used as the primary source.
 *
 * <p>Only what the module writes is handled: booleans, ints, longs, floats and strings. Sets are
 * skipped. Parsing is deliberately literal — no Android, no XML library — so it can be exercised
 * from a plain JVM.
 */
public final class PreferencesFile {
    private static final Pattern SCALAR =
        Pattern.compile("<(boolean|int|long|float)\\s+name=\"([^\"]*)\"\\s+value=\"([^\"]*)\"");
    private static final Pattern STRING =
        Pattern.compile("<string\\s+name=\"([^\"]*)\">(.*?)</string>", Pattern.DOTALL);

    private PreferencesFile() {}

    /** Parses the file, or returns null when it cannot be read. */
    public static Map<String, Object> read(File file) {
        if (file == null || !file.isFile()) return null;
        try (InputStream in = new FileInputStream(file)) {
            byte[] data = new byte[(int) Math.min(file.length(), 1 << 20)];
            int read = 0;
            while (read < data.length) {
                int n = in.read(data, read, data.length - read);
                if (n <= 0) break;
                read += n;
            }
            return parse(new String(data, 0, read, StandardCharsets.UTF_8));
        } catch (Throwable error) {
            return null;
        }
    }

    /** Parses preferences XML text. */
    public static Map<String, Object> parse(String xml) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (xml == null || xml.isEmpty()) return values;
        Matcher scalar = SCALAR.matcher(xml);
        while (scalar.find()) {
            String type = scalar.group(1);
            String name = scalar.group(2);
            String text = scalar.group(3);
            try {
                if ("boolean".equals(type)) values.put(name, Boolean.parseBoolean(text));
                else if ("long".equals(type)) values.put(name, Long.parseLong(text));
                else if ("float".equals(type)) values.put(name, Float.parseFloat(text));
                else values.put(name, Integer.parseInt(text));
            } catch (NumberFormatException ignored) {
                // A value the writer meant to be a number but wrote as text: leave it out, the
                // caller's default then applies instead of a wrong 0.
            }
        }
        Matcher string = STRING.matcher(xml);
        while (string.find()) values.put(string.group(1), unescape(string.group(2)));
        return values;
    }

    private static String unescape(String text) {
        if (text.indexOf('&') < 0) return text;
        return text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&apos;", "'").replace("&amp;", "&");
    }
}

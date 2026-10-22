package local.jiege.hook.common;

import android.app.Application;
import android.content.pm.PackageInfo;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Per-process port health report, written next to the other module state so an OTA can be diagnosed
 * without guessing: every symbol the port needs is listed with what it resolved to, and every
 * missing one with the resolver's reason. The header records the app build the symbols were matched
 * against, so a report before and after a system update can simply be diffed.
 *
 * <p>File: {@code <app data>/files/jiege/port_status_<section>.txt}; one line per symbol, grouped as
 * {@code resolved} then {@code missing}. It is rewritten on every process start (a few kilobytes).
 */
public final class PortReport {
    private static final String TAG = "PortReport";
    private static final String DIR = "jiege";
    /** Enough for the whole table plus the missing reasons. */
    private static final long MAX_BYTES = 256 * 1024;
    /** APK the version is read from when the Application does not exist yet. */
    private static volatile String currentApk;
    /** Package the report describes, so the version can be looked up without an Application. */
    private static volatile String currentPackage;

    private PortReport() {}

    static void write(String section, String apkPath, String dataDir, Symbols symbols) {
        try {
            File dir = new File(dataDir, "files/" + DIR);
            if (!dir.isDirectory() && !dir.mkdirs()) {
                Log.i(TAG, "no report dir " + dir);
                return;
            }
            File out = new File(dir, "port_status_" + section + ".txt");
            currentApk = apkPath;
            currentPackage = "gallery".equals(section) ? "com.coloros.gallery3d" : "com.oplus.camera";
            StringBuilder text = new StringBuilder(8192);
            text.append("# port status, ").append(section).append('\n');
            text.append("time=").append(stamp()).append('\n');
            text.append("pid=").append(android.os.Process.myPid())
                .append(" process=").append(processName()).append('\n');
            text.append("app_apk=").append(apkPath).append('\n');
            File apk = apkPath == null ? null : new File(apkPath);
            if (apk != null && apk.isFile()) {
                text.append("app_size=").append(apk.length())
                    .append(" app_time=").append(stamp(apk.lastModified())).append('\n');
            }
            text.append("app_version=").append(version()).append('\n');
            environment(text);
            text.append("module_apk=").append(Symbols.modulePath()).append('\n');
            text.append('\n');

            Map<String, String> resolved = new TreeMap<>();
            for (Map.Entry<String, java.util.List<Object>> entry : symbols.resolvedEntries().entrySet()) {
                resolved.put(entry.getKey(), describe(entry.getValue()));
            }
            Map<String, String> missing = new TreeMap<>(symbols.failureEntries());

            text.append("resolved ").append(resolved.size()).append('\n');
            for (Map.Entry<String, String> entry : resolved.entrySet()) {
                text.append("  OK   ").append(entry.getKey()).append(" -> ").append(entry.getValue())
                    .append('\n');
            }
            text.append("missing ").append(missing.size()).append('\n');
            for (Map.Entry<String, String> entry : missing.entrySet()) {
                text.append("  MISS ").append(entry.getKey()).append(" : ").append(entry.getValue())
                    .append('\n');
            }
            byte[] bytes = text.toString().getBytes("UTF-8");
            if (bytes.length > MAX_BYTES) {
                Log.i(TAG, "report too large, truncated: " + bytes.length);
                return;
            }
            try (FileOutputStream stream = new FileOutputStream(out, false)) {
                stream.write(bytes);
            }
            Log.i(TAG, section + " report written: " + resolved.size() + " ok, " + missing.size()
                + " missing, " + bytes.length + " bytes");
        } catch (Throwable error) {
            Log.e(TAG, "report failed", error);
        }
    }

    private static String describe(java.util.List<Object> values) {
        if (values == null || values.isEmpty()) return "?";
        Object value = values.get(0);
        if (value instanceof Class) {
            return "class " + ((Class<?>) value).getName();
        }
        if (value instanceof Method) {
            Method method = (Method) value;
            StringBuilder sb = new StringBuilder("method ").append(method.getDeclaringClass().getName())
                .append('#').append(method.getName()).append('(');
            Class<?>[] params = method.getParameterTypes();
            for (int i = 0; i < params.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(params[i].getSimpleName());
            }
            return sb.append(')').toString();
        }
        if (value instanceof Field) {
            Field field = (Field) value;
            return "field " + field.getDeclaringClass().getName() + '#' + field.getName();
        }
        return String.valueOf(value);
    }

    private static String version() {
        try {
            Application app = currentApplication();
            if (app != null) {
                PackageInfo info = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
                return describe(info);
            }
        } catch (Throwable ignored) {
            // fall through
        }
        // Symbol install runs before the Application exists; ActivityThread's package manager is
        // available from the very start and answers the same question.
        try {
            Object manager = Class.forName("android.app.ActivityThread")
                .getMethod("getPackageManager").invoke(null);
            if (manager != null && currentPackage != null) {
                for (Method method : manager.getClass().getMethods()) {
                    if (!"getPackageInfo".equals(method.getName())) continue;
                    Class<?>[] params = method.getParameterTypes();
                    Object[] args;
                    if (params.length == 3 && params[0] == String.class && params[1] == long.class) {
                        args = new Object[] {currentPackage, 0L, android.os.Process.myUid() / 100000};
                    } else if (params.length == 3 && params[0] == String.class && params[1] == int.class) {
                        args = new Object[] {currentPackage, 0, android.os.Process.myUid() / 100000};
                    } else if (params.length == 2 && params[0] == String.class) {
                        args = new Object[] {currentPackage, 0};
                    } else {
                        continue;
                    }
                    Object info = method.invoke(manager, args);
                    if (info instanceof PackageInfo) return describe((PackageInfo) info);
                }
            }
        } catch (Throwable ignored) {
            // fall through
        }
        // Last resort: the archive itself carries the same versionName / versionCode.
        try {
            Application app = currentApplication();
            if (app != null && currentApk != null) {
                PackageInfo info = app.getPackageManager().getPackageArchiveInfo(currentApk, 0);
                if (info != null) return describe(info);
            }
        } catch (Throwable ignored) {
            // fall through
        }
        return "?";
    }

    private static String describe(PackageInfo info) {        String code = android.os.Build.VERSION.SDK_INT >= 28
            ? String.valueOf(info.getLongVersionCode()) : String.valueOf(info.versionCode);
        return info.versionName + " (" + code + ")";
    }

    /** Module version from its own module.prop, and the native halves' switches. */
    private static void environment(StringBuilder text) {
        try {
            java.io.File prop = new java.io.File("/data/adb/modules/ricoh_pop_x9u/module.prop");
            if (prop.isFile()) {
                for (String line : new String(java.nio.file.Files.readAllBytes(prop.toPath()), "UTF-8")
                    .split("\n")) {
                    if (line.startsWith("version=")) {
                        text.append("module_version=").append(line.substring(8).trim()).append('\n');
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {
            // best effort
        }
        String[] props = {"persist.omnicam.aura.fisheye", "persist.omnicam.aura.nfish",
            "persist.omnicam.aura.npop", "persist.omnicam.aura.nnat", "persist.omnicam.aura.ngr",
            "persist.omnicam.aura.nlut", "persist.omnicam.aura.nsl"};
        StringBuilder switches = new StringBuilder();
        for (String name : props) {
            String value = prop(name);
            if (value == null) continue;
            if (switches.length() > 0) switches.append(' ');
            switches.append(name.substring("persist.omnicam.aura.".length())).append('=').append(value);
        }
        if (switches.length() > 0) text.append("switches=").append(switches).append('\n');
    }

    private static String prop(String name) {
        try {
            Class<?> type = Class.forName("android.os.SystemProperties");
            Object value = type.getMethod("get", String.class, String.class).invoke(null, name, "");
            return value == null || String.valueOf(value).isEmpty() ? null : String.valueOf(value);
        } catch (Throwable error) {
            return null;
        }
    }

    private static String processName() {
        try {
            return Application.getProcessName();
        } catch (Throwable error) {
            return "?";
        }
    }

    private static Application currentApplication() {
        try {
            return (Application) Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication").invoke(null);
        } catch (Throwable error) {
            return null;
        }
    }

    private static String stamp() {
        return stamp(System.currentTimeMillis());
    }

    private static String stamp(long time) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date(time));
    }

}

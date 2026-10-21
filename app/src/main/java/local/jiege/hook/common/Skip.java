package local.jiege.hook.common;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Debug-only hook group switch, read once per process from
 * {@code persist.omnicam.aura.skip}.
 *
 * <p>Two forms, both comma separated:
 * <ul>
 *   <li>{@code pop,filter} or {@code all} - blacklist, the named groups are not installed;</li>
 *   <li>{@code only:highpixel,only:native} - whitelist, everything not named is not installed.</li>
 * </ul>
 *
 * <p>It exists so a hang or a crash can be bisected by group without a rebuild: set the property,
 * force-stop the process, and the groups follow it. The group ids are given at their install site in
 * {@code local.jiege.hook.ModuleEntry}. An empty property (the default) installs everything.
 */
public final class Skip {
    public static final String PROP = "persist.omnicam.aura.skip";

    private static final Set<String> GROUPS = new HashSet<>();
    /** Non-null when the property is a whitelist: only the groups named here are installed. */
    private static final Set<String> ONLY;

    static {
        Set<String> only = null;
        for (String part : prop().split(",")) {
            String id = part.trim().toLowerCase(Locale.ROOT);
            if (id.isEmpty()) continue;
            if (id.startsWith("only:")) {
                if (only == null) only = new HashSet<>();
                String name = id.substring(5).trim();
                if (!name.isEmpty()) only.add(name);
            } else {
                GROUPS.add(id);
            }
        }
        ONLY = only == null ? null : Collections.unmodifiableSet(only);
    }

    private Skip() {}

    /** True when no hook group at all should be installed. */
    public static boolean all() {
        return ONLY == null && GROUPS.contains("all");
    }

    /** True when the given group id must not be installed. */
    public static boolean off(String group) {
        // "highpixel" still means the whole hi-res family (hpeffects / hpentry / hpfilter / hp200m).
        boolean alias = group.startsWith("hp") && !"highpixel".equals(group);
        if (ONLY != null) {
            if (ONLY.contains(group)) return false;
            if (alias && ONLY.contains("highpixel")) return false;
            return true;
        }
        if (GROUPS.contains("all") || GROUPS.contains(group)) return true;
        // "highpixel" still means the whole hi-res family (hpeffects / hpentry / hpfilter / hp200m).
        return alias && GROUPS.contains("highpixel");
    }

    /** The raw selection, for the log line at startup. */
    public static String raw() {
        if (ONLY != null) return "only " + ONLY;
        return GROUPS.isEmpty() ? "-" : GROUPS.toString();
    }

    private static String prop() {
        try {
            Class<?> type = Class.forName("android.os.SystemProperties");
            Object value = type.getMethod("get", String.class, String.class).invoke(null, PROP, "");
            return String.valueOf(value);
        } catch (Throwable error) {
            return "";
        }
    }
}

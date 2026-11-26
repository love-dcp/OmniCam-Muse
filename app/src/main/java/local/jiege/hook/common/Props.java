package local.jiege.hook.common;

import android.os.SystemClock;

/**
 * System property reads for the module's switches.
 *
 * <p>Every hook used to carry its own copy of {@code Class.forName("android.os.SystemProperties")}
 * followed by {@code getMethod(...).invoke(...)}. That is fine once at start-up and expensive in a
 * hot path: the touch hook asks for a property on every {@code dispatchTouchEvent}, the state
 * machine asks for half a dozen on every composition pass, and the class lookup alone is a full
 * lookup through the boot class loader each time. This keeps the {@link java.lang.reflect.Method}
 * and caches each value briefly, so a switch flipped with {@code setprop} still takes effect
 * immediately from the user's point of view while the steady state costs one map lookup.
 */
public final class Props {

    /** Long enough to absorb a burst of reads, short enough that a setprop lands while you watch. */
    private static final long TTL_MS = 300L;

    private static volatile java.lang.reflect.Method getter;

    private static final java.util.HashMap<String, Entry> CACHE = new java.util.HashMap<>();

    private static final class Entry {
        final String value;
        final long at;

        Entry(String value, long at) {
            this.value = value;
            this.at = at;
        }
    }

    private Props() {}

    /**
     * @param key      property name
     * @param fallback returned when the property is unset or empty, and when it cannot be read
     */
    public static String get(String key, String fallback) {
        long now = SystemClock.elapsedRealtime();
        synchronized (CACHE) {
            Entry entry = CACHE.get(key);
            if (entry != null && now - entry.at < TTL_MS) {
                return entry.value == null || entry.value.isEmpty() ? fallback : entry.value;
            }
        }
        String value = read(key);
        if (value == null) return fallback;
        synchronized (CACHE) {
            CACHE.put(key, new Entry(value, now));
        }
        return value.isEmpty() ? fallback : value;
    }

    /** Integer form, for the switches that carry a number (zoom floor, EV offsets). */
    public static int getInt(String key, int fallback) {
        try {
            return Integer.parseInt(get(key, String.valueOf(fallback)).trim());
        } catch (Throwable error) {
            return fallback;
        }
    }

    private static String read(String key) {
        try {
            java.lang.reflect.Method method = getter;
            if (method == null) {
                Class<?> type = Class.forName("android.os.SystemProperties");
                method = type.getMethod("get", String.class, String.class);
                method.setAccessible(true);
                getter = method;
            }
            Object value = method.invoke(null, key, "");
            return value == null ? null : String.valueOf(value);
        } catch (Throwable error) {
            return null;
        }
    }
}

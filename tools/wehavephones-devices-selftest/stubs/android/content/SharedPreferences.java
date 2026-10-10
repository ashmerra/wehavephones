package android.content;

import java.util.HashMap;
import java.util.Map;

/** In-memory preferences with the Editor calls the code under test uses. */
public class SharedPreferences {
    private final HashMap<String, Object> values = new HashMap<>();

    public synchronized String getString(String key, String fallback) {
        Object value = values.get(key);
        return value instanceof String ? (String)value : fallback;
    }

    public synchronized long getLong(String key, long fallback) {
        Object value = values.get(key);
        return value instanceof Long ? (Long)value : fallback;
    }

    public synchronized Map<String, ?> getAll() {
        return new HashMap<>(values);
    }

    public Editor edit() {
        return new Editor();
    }

    public class Editor {
        private final HashMap<String, Object> pending = new HashMap<>();
        private final java.util.HashSet<String> removed = new java.util.HashSet<>();

        public Editor putString(String key, String value) { pending.put(key, value); removed.remove(key); return this; }
        public Editor putLong(String key, long value) { pending.put(key, value); removed.remove(key); return this; }
        public Editor remove(String key) { pending.remove(key); removed.add(key); return this; }

        public boolean commit() {
            synchronized (SharedPreferences.this) {
                values.keySet().removeAll(removed);
                values.putAll(pending);
            }
            return true;
        }

        public void apply() { commit(); }
    }
}

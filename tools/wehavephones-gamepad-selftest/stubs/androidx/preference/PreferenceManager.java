package androidx.preference;
import android.content.Context;
import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.*;
public class PreferenceManager {
    private static final Map<String, Object> values = new HashMap<>();
    public static final Set<SharedPreferences.OnSharedPreferenceChangeListener> listeners = new HashSet<>();
    private static final SharedPreferences prefs = (SharedPreferences) Proxy.newProxyInstance(
        SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "getBoolean": case "getInt": case "getString": return values.getOrDefault(args[0], args[1]);
                case "registerOnSharedPreferenceChangeListener": listeners.add((SharedPreferences.OnSharedPreferenceChangeListener) args[0]); return null;
                case "unregisterOnSharedPreferenceChangeListener": listeners.remove(args[0]); return null;
                case "edit": return editor();
                default: throw new AssertionError(method);
            }
        });
    private static SharedPreferences.Editor editor() {
        Map<String, Object> pending = new HashMap<>();
        return (SharedPreferences.Editor) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
            new Class<?>[]{SharedPreferences.Editor.class}, (proxy, method, args) -> {
                if (method.getName().startsWith("put")) { pending.put((String) args[0], args[1]); return proxy; }
                if (method.getName().equals("apply") || method.getName().equals("commit")) {
                    values.putAll(pending);
                    for (String key : pending.keySet())
                        for (SharedPreferences.OnSharedPreferenceChangeListener listener : new ArrayList<>(listeners))
                            listener.onSharedPreferenceChanged(prefs, key);
                    return method.getName().equals("commit") ? true : null;
                }
                throw new AssertionError(method);
            });
    }
    public static SharedPreferences getDefaultSharedPreferences(Context context) { return prefs; }
}

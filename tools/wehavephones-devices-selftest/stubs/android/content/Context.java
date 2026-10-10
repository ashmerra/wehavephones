package android.content;

import android.content.pm.ApplicationInfo;
import android.content.res.AssetManager;

import java.io.File;
import java.util.HashMap;

/** Host stand-in: a files dir, an assets dir and in-memory preferences. */
public class Context {
    public static final int MODE_PRIVATE = 0;

    private final File filesDir;
    private final AssetManager assets;
    private final HashMap<String, SharedPreferences> preferences = new HashMap<>();

    public Context(File filesDir, File assetsDir) {
        this.filesDir = filesDir;
        this.assets = new AssetManager(assetsDir);
    }

    public File getFilesDir() { return filesDir; }
    public AssetManager getAssets() { return assets; }
    public Context getApplicationContext() { return this; }

    public ApplicationInfo getApplicationInfo() {
        ApplicationInfo info = new ApplicationInfo();
        info.nativeLibraryDir = new File(filesDir, "native").getPath();
        return info;
    }

    public synchronized SharedPreferences getSharedPreferences(String name, int mode) {
        SharedPreferences prefs = preferences.get(name);
        if (prefs == null) {
            prefs = new SharedPreferences();
            preferences.put(name, prefs);
        }
        return prefs;
    }
}

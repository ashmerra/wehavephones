package android.content;

import android.content.pm.ApplicationInfo;

import java.io.File;

/** Compile-time stub for the host GpuProbe self-test: only the calls GpuProbe makes. */
public class Context {
    private final File filesDir;

    public Context() {
        this(new File("/nonexistent/files"));
    }

    public Context(File filesDir) {
        this.filesDir = filesDir;
    }

    public File getFilesDir() {
        return filesDir;
    }

    public ApplicationInfo getApplicationInfo() {
        return new ApplicationInfo();
    }
}

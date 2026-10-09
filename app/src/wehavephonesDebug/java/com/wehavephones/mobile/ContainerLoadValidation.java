package com.wehavephones.mobile;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Bundle;

import com.wehavephones.container.Container;
import com.wehavephones.container.ContainerManager;
import com.wehavephones.core.FileUtils;

import java.io.File;
import java.nio.file.Files;

/** Runs the production loader against isolated cache fixtures, never user containers. */
public final class ContainerLoadValidation extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        File sandbox = null;
        int status = Activity.RESULT_CANCELED;
        try {
            sandbox = Files.createTempDirectory(getTargetContext().getCacheDir().toPath(), "container-load-").toFile();
            final File isolatedFiles = sandbox;
            Context isolated = new ContextWrapper(getTargetContext()) {
                @Override public File getFilesDir() { return isolatedFiles; }
            };
            File home = new File(sandbox, "imagefs/home");
            File partial = new File(home, "xuser-1");
            require(new File(partial, ".wine").mkdirs(), "create unfinished prefix");
            File sentinel = new File(partial, ".wine/preserve.txt");
            require(FileUtils.writeString(sentinel, "unfinished prefix data"), "write prefix data");
            saveContainer(new File(home, "xuser-2"), 2);
            File malformed = new File(home, "xuser-3/.container");
            require(malformed.getParentFile().mkdirs(), "create damaged prefix");
            require(FileUtils.writeString(malformed, "{\"name\":"), "write truncated config");
            require(new File(home, "xuser-4/.container").mkdirs(), "create non-file config");
            File invalidFields = new File(home, "xuser-5/.container");
            require(invalidFields.getParentFile().mkdirs(), "create invalid config prefix");
            require(FileUtils.writeString(invalidFields, "{\"showFPS\":[]}"), "write invalid config fields");
            require(new File(home, "xuser-invalid").mkdirs(), "create unrelated directory");
            require(new File(home, "xuser-99999999999999999999").mkdirs(), "create oversized ID");

            // Repeated setup refreshes while the prefix has no saved config.
            for (int i = 0; i < 20; i++) {
                ContainerManager manager = new ContainerManager(isolated);
                require(manager.getContainers().size() == 1 && manager.getContainerById(2) != null,
                        "healthy container survives incomplete and damaged neighbors");
                require(manager.getContainerById(1) == null, "unfinished container stays unavailable");
                require(manager.getNextContainerId() == 6, "retry reserves all existing prefix IDs");
            }
            require("unfinished prefix data".equals(FileUtils.readString(sentinel)), "preserve unfinished prefix");
            require("{\"name\":".equals(FileUtils.readString(malformed)), "preserve damaged config");
            saveContainer(partial, 1);
            ContainerManager complete = new ContainerManager(isolated);
            require(complete.getContainers().size() == 2 && complete.getContainerById(1) != null,
                    "completed container becomes available on next refresh");
            result.putString("stream", "PASS: incomplete-prefix refresh, damaged neighbors, reserved IDs, data preservation and completion\n");
            status = Activity.RESULT_OK;
        } catch (Throwable failure) {
            result.putString("stream", "FAIL: " + android.util.Log.getStackTraceString(failure));
        } finally {
            if (sandbox != null) FileUtils.delete(sandbox);
        }
        finish(status, result);
    }

    private static void saveContainer(File directory, int id) {
        require(directory.isDirectory() || directory.mkdirs(), "create healthy prefix");
        Container container = new Container(id);
        container.setRootDir(directory);
        container.setName("Validation " + id);
        container.saveData();
        require(container.getConfigFile().isFile(), "save healthy config");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

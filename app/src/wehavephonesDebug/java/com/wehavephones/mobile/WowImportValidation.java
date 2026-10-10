package com.wehavephones.mobile;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;

import com.wehavephones.R;
import com.wehavephones.container.Container;
import com.wehavephones.container.ContainerManager;
import com.wehavephones.core.FileUtils;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/** Android filesystem validation in cache; the service check only refuses an existing installation. */
public final class WowImportValidation extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        File sandbox = null;
        Activity launcher = null;
        AtomicBoolean startedService = new AtomicBoolean();
        int status = Activity.RESULT_CANCELED;
        try {
            require(!WowImportService.hasLiveSession() && !WowImportService.isRunning(), "leave live sessions/imports intact");
            sandbox = Files.createTempDirectory(getTargetContext().getCacheDir().toPath(), "wow-import-").toFile();
            File source = new File(sandbox, "Downloads/World of Warcraft");
            require(new File(source, "Data").mkdirs(), "create data fixture");
            require(new File(source, "_retail_").mkdirs(), "create version fixture");
            byte[] data = new byte[2 * 1024 * 1024 + 37];
            for (int i = 0; i < data.length; i++) data[i] = (byte)(i * 13);
            Files.write(new File(source, "Data/data.000").toPath(), data);
            Files.write(new File(source, ".build.info").toPath(), new byte[]{1, 2});
            Files.write(new File(source, "_retail_/Wow.exe").toPath(), new byte[]{3, 4});
            File destination = new File(sandbox, "prefix/World of Warcraft");
            WowFileImporter.copy(source, destination, null, (copied, total) -> {
                require(!destination.exists(), "partial installation invisible");
            }, () -> false);
            require(Arrays.equals(data, Files.readAllBytes(new File(destination, "Data/data.000").toPath())), "all bytes retained");
            require(new File(destination, ".build.info").isFile(), "hidden metadata retained");
            FileUtils.delete(destination);
            AtomicBoolean cancelled = new AtomicBoolean();
            try {
                WowFileImporter.copy(source, destination, null,
                        (copied, total) -> { if (copied > 0) cancelled.set(true); }, cancelled::get);
                throw new AssertionError("Expected cancellation");
            }
            catch (WowFileImporter.ImportException failure) {
                require(failure.problem == WowFileImporter.Problem.CANCELLED, "cancellation reported");
            }
            require(!destination.exists() && !new File(destination.getParentFile(), WowFileImporter.STAGING_NAME).exists(),
                    "partial copy removed");
            require(Arrays.equals(data, Files.readAllBytes(new File(source, "Data/data.000").toPath())), "source retained");

            String foreground = "not exercised (no existing game folder)";
            for (Container container : new ContainerManager(getTargetContext()).getContainers()) {
                if (!WeHavePhonesPaths.wowRoot(container).exists() && !WeHavePhonesPaths.wowRootFallback(container).exists()) continue;
                launcher = startActivitySync(new Intent(getTargetContext(), WeHavePhonesActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                runOnMainSync(() -> {
                    require(!WowImportService.isRunning(), "leave other imports intact");
                    WowImportService.start(getTargetContext(), container);
                    startedService.set(true);
                    require(WowImportService.isRunning(), "foreground import requested");
                    require(!WeHavePhonesSessionLauncher.launch(getTargetContext(), container, null), "launch blocked during import");
                });
                long deadline = SystemClock.elapsedRealtime() + 15000;
                while (WowImportService.isRunning() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50);
                WowImportService.State finished = WowImportService.state();
                require(!finished.running && finished.error, "service finished without crash");
                require(getTargetContext().getString(R.string.wehavephones_import_existing).equals(finished.message),
                        "service refuses existing installation");
                runOnMainSync(() -> {
                    WowImportService.start(getTargetContext(), container);
                    getTargetContext().startService(new Intent(getTargetContext(), WowImportService.class).setAction("cancel"));
                });
                deadline = SystemClock.elapsedRealtime() + 15000;
                while ((WowImportService.isRunning() || servicePresent()) && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50);
                require(!WowImportService.isRunning() && !servicePresent(), "notification cancellation stops the service");
                foreground = "existing-folder refusal, launch guard and notification cancellation passed";
                break;
            }
            result.putString("stream", "PASS: Android copy integrity, metadata, cancellation, cleanup and source preservation; foreground service: "
                    + foreground + "\n");
            status = Activity.RESULT_OK;
        }
        catch (Throwable failure) { result.putString("stream", "FAIL: " + android.util.Log.getStackTraceString(failure)); }
        finally {
            if (startedService.get() && WowImportService.isRunning()) WowImportService.cancel();
            if (launcher != null) { Activity current = launcher; runOnMainSync(current::finish); }
            if (sandbox != null) FileUtils.delete(sandbox);
        }
        finish(status, result);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private boolean servicePresent() {
        for (ActivityManager.RunningServiceInfo service : getTargetContext().getSystemService(ActivityManager.class).getRunningServices(100)) {
            if (WowImportService.class.getName().equals(service.service.getClassName())) return true;
        }
        return false;
    }
}

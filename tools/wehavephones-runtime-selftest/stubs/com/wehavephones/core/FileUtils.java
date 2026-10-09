package com.wehavephones.core;

import android.content.Context;

import java.io.File;
import java.nio.file.Files;

/**
 * Host self-test stub: serves the runtime profile JSON the test points at.
 */
public final class FileUtils {
    public static volatile File source = new File(
            System.getProperty("wehavephones.profile.json", "wehavephones-runtime-profiles.json"));

    private FileUtils() {}

    public static byte[] read(Context context, String assetFile) {
        try {
            return Files.readAllBytes(source.toPath());
        }
        catch (Exception e) {
            return null;
        }
    }
}

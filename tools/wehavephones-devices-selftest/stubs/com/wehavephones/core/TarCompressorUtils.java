package com.wehavephones.core;

import android.content.Context;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

/**
 * Host stand-in for the real extractor: the test "tzst" archives are plain tar files, so only the
 * compression layer differs. Path handling and the listener contract follow the real class.
 */
public abstract class TarCompressorUtils {
    public enum Type {XZ, ZSTD}

    public static boolean extract(Type type, Context context, String assetFile, File destination, OnExtractFileListener listener) {
        try (InputStream in = context.getAssets().open(assetFile)) {
            return extract(in, destination, listener);
        }
        catch (IOException e) {
            return false;
        }
    }

    public static boolean extract(Type type, File source, File destination, OnExtractFileListener listener) {
        try (InputStream in = new FileInputStream(source)) {
            return extract(in, destination, listener);
        }
        catch (IOException e) {
            return false;
        }
    }

    private static boolean extract(InputStream source, File destination, OnExtractFileListener listener) {
        try (TarArchiveInputStream tar = new TarArchiveInputStream(source)) {
            TarArchiveEntry entry;
            while ((entry = (TarArchiveEntry)tar.getNextEntry()) != null) {
                File file = new File(destination, entry.getName());
                if (listener != null) {
                    file = listener.onExtractFile(file, entry.getSize());
                    if (file == null) continue;
                }
                if (entry.isDirectory()) {
                    file.mkdirs();
                }
                else if (entry.isSymbolicLink()) {
                    file.getParentFile().mkdirs();
                    Files.createSymbolicLink(file.toPath(), new File(entry.getLinkName()).toPath());
                }
                else {
                    file.getParentFile().mkdirs();
                    Files.copy(tar, file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
            return true;
        }
        catch (IOException e) {
            return false;
        }
    }
}

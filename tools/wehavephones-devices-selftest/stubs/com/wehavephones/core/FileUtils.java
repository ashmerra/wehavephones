package com.wehavephones.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public abstract class FileUtils {
    public static String readString(File file) {
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            return null;
        }
    }

    public static boolean delete(File target) {
        if (target == null || !Files.exists(target.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return false;
        if (target.isDirectory() && !Files.isSymbolicLink(target.toPath())) {
            File[] children = target.listFiles();
            if (children != null) for (File child : children) delete(child);
        }
        return target.delete();
    }
}

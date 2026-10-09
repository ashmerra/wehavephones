package com.wehavephones.mobile;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Downloads a runtime profile's Wine layer (.wcp) and installs it under
 * imagefs/opt/<name>, the in-app counterpart of scripts/install-wine-layer-adb.py.
 * The archive must match its sha256, and the layer only appears under its final
 * name after extraction produced bin/wine and profile.json, so an interrupted
 * install never leaves a half-extracted layer that looks installed.
 */
public final class WineLayerInstaller {
    public interface Extractor {
        boolean extract(File archive, File destination);
    }

    public interface Progress {
        void onDownload(long done, long total);
    }

    public interface BundledSource {
        InputStream open() throws IOException;
    }

    private static final int BUFFER_SIZE = 64 * 1024;

    private WineLayerInstaller() {}

    public static boolean isInstalled(File optDir, String name) {
        return new File(optDir, name + "/bin/wine").isFile();
    }

    /** Returns null when the layer is installed, otherwise an error message. */
    public static String install(String name, String url, String sha256, File optDir, File cacheDir,
                                 Extractor extractor, Progress progress) {
        if (isInstalled(optDir, name)) return null;
        if (name == null || !name.matches("proton-[0-9A-Za-z.\\-]+")) return "Invalid Wine layer name: " + name;
        if (sha256 == null || !sha256.matches("[0-9A-Fa-f]{64}")) return "No valid sha256 for Wine layer " + name;
        String expected = sha256.toLowerCase(Locale.US);

        File target = new File(optDir, name);
        if (target.exists()) return "Incomplete Wine layer already at " + target + "; remove it and retry";
        if (!cacheDir.isDirectory() && !cacheDir.mkdirs()) return "Could not create " + cacheDir;
        File archive = new File(cacheDir, name + ".wcp");
        try {
            if (!archive.isFile() || !expected.equals(sha256(archive))) {
                if (url == null || url.isEmpty()) return "No verified archive or download source for Wine layer " + name;
                String error = download(url, archive, expected, progress);
                if (error != null) return error;
            }

            File staging = new File(optDir, name + ".installing");
            deleteRecursively(staging);
            if (!staging.mkdirs()) return "Could not create " + staging;

            if (!extractor.extract(archive, staging)) {
                deleteRecursively(staging);
                return "Could not extract Wine layer " + name;
            }
            if (!new File(staging, "bin/wine").isFile() || !new File(staging, "profile.json").isFile()) {
                deleteRecursively(staging);
                return "Wine layer " + name + " is missing bin/wine or profile.json";
            }
            if (!staging.renameTo(target)) {
                deleteRecursively(staging);
                return "Could not move Wine layer into " + target;
            }
            archive.delete();
            return null;
        }
        catch (IOException e) {
            return "Wine layer " + name + " install failed: " + e.getMessage();
        }
    }

    /** Install the APK's pinned layer offline, with the same hash and staged extraction checks. */
    public static String installBundled(String name, String sha256, File optDir, File cacheDir,
                                        BundledSource source, Extractor extractor) {
        if (name == null || !name.matches("proton-[0-9A-Za-z.\\-]+")) return "Invalid Wine layer name";
        if (sha256 == null || !sha256.matches("[0-9A-Fa-f]{64}")) return "No valid sha256 for Wine layer " + name;
        if (isInstalled(optDir, name)) return null;
        if (!cacheDir.isDirectory() && !cacheDir.mkdirs()) return "Could not create " + cacheDir;
        File archive = new File(cacheDir, name + ".wcp");
        File part = new File(archive.getPath() + ".part");
        try {
            if (!archive.isFile() || !sha256.equalsIgnoreCase(sha256(archive))) {
                try (InputStream input = source.open(); OutputStream output = new FileOutputStream(part)) {
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                }
                if (!sha256.equalsIgnoreCase(sha256(part))) return "Bundled Wine layer failed SHA-256 verification";
                if (!part.renameTo(archive)) return "Could not stage the bundled Wine layer";
            }
            return install(name, "", sha256, optDir, cacheDir, extractor, null);
        }
        catch (IOException e) {
            return "Bundled Wine layer install failed: " + e.getMessage();
        }
        finally {
            part.delete();
        }
    }

    private static String download(String url, File archive, String expected, Progress progress) throws IOException {
        File part = new File(archive.getPath() + ".part");
        URLConnection connection = new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("User-Agent", "WeHavePhonesMobile");
        if (connection instanceof HttpURLConnection) {
            HttpURLConnection http = (HttpURLConnection)connection;
            http.setInstanceFollowRedirects(true);
            int code = http.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                http.disconnect();
                return "Wine layer download failed: HTTP " + code;
            }
        }

        long total = connection.getContentLengthLong();
        MessageDigest digest = newSha256();
        try (InputStream in = new BufferedInputStream(connection.getInputStream(), BUFFER_SIZE);
             OutputStream out = new FileOutputStream(part)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            long done = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                digest.update(buffer, 0, read);
                done += read;
                if (progress != null) progress.onDownload(done, total);
            }
        }
        catch (IOException e) {
            part.delete();
            throw e;
        }
        finally {
            if (connection instanceof HttpURLConnection) ((HttpURLConnection)connection).disconnect();
        }

        String actual = hex(digest.digest());
        if (!expected.equals(actual)) {
            part.delete();
            return "Wine layer download is corrupt (sha256 " + actual + ")";
        }
        archive.delete();
        if (!part.renameTo(archive)) {
            part.delete();
            return "Could not save the Wine layer download";
        }
        return null;
    }

    private static String sha256(File file) throws IOException {
        MessageDigest digest = newSha256();
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        return hex(digest.digest());
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        }
        catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) builder.append(String.format(Locale.US, "%02x", value));
        return builder.toString();
    }

    // Walks without following symlinks, so links inside the layer never lead the
    // delete outside of it.
    private static void deleteRecursively(File root) throws IOException {
        Path path = root.toPath();
        if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                if (e != null) throw e;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}

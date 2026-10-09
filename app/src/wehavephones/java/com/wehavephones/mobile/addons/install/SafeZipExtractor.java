package com.wehavephones.mobile.addons.install;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * Streaming ZIP extractor with strict security checks: limits on entry count,
 * size and compression ratio, no symlinks or duplicate names, and no unsafe
 * entry names (path traversal, absolute, backslash or drive-letter paths).
 * Never trusts entry metadata.
 */
public final class SafeZipExtractor {
    private SafeZipExtractor() {}

    public static final class Limits {
        public int maxEntries = 20000;
        public long maxTotalBytes = 1536L * 1024L * 1024L;
        public long maxEntryBytes = 1024L * 1024L * 1024L;
        public int maxEntryRatio = 200;
        public int maxTotalRatio = 100;
        public int maxNameLength = 200;
        public long ratioCheckThreshold = 1024L * 1024L;

        public static Limits defaults() {
            return new Limits();
        }
    }

    public static final class Result {
        public final ArrayList<String> entries = new ArrayList<>();
        public long totalBytes;
    }

    public static Result extract(File zipFile, File destination, Limits limits)
            throws IOException, ZipSecurityException {
        Limits effective = limits != null ? limits : Limits.defaults();
        if (!destination.exists() && !destination.mkdirs()) {
            throw new IOException("Cannot create extraction directory: " + destination);
        }
        Path root = destination.toPath().toAbsolutePath().normalize();
        Result result = new Result();

        HashSet<String> seenFiles = new HashSet<>();
        HashSet<String> seenDirectories = new HashSet<>();
        long totalCompressed = 0;
        int entryCount = 0;

        try (ZipFile zip = new ZipFile(zipFile)) {
            Enumeration<ZipArchiveEntry> enumeration = zip.getEntries();
            while (enumeration.hasMoreElements()) {
                ZipArchiveEntry entry = enumeration.nextElement();
                entryCount++;
                if (entryCount > effective.maxEntries) {
                    throw new ZipSecurityException("Archive has too many entries");
                }
                if (entry.isUnixSymlink()) {
                    throw new ZipSecurityException("Archive contains a symlink: " + entry.getName());
                }
                if (!zip.canReadEntryData(entry)) {
                    throw new ZipSecurityException("Unsupported compression for entry: " + entry.getName());
                }
                String normalized = validateEntryName(entry.getName(), effective);
                if (normalized.isEmpty()) continue;
                boolean directory = entry.isDirectory() || normalized.endsWith("/");
                String cleanName = trimTrailingSlash(normalized);
                String key = cleanName.toLowerCase(Locale.US);

                if (directory) {
                    if (seenFiles.contains(key) || seenDirectories.contains(key)) {
                        throw new ZipSecurityException("Duplicate entry: " + cleanName);
                    }
                    seenDirectories.add(key);
                    File target = resolveChecked(root, cleanName, effective);
                    if (!target.exists() && !target.mkdirs()) {
                        throw new IOException("Cannot create directory: " + target);
                    }
                    continue;
                }

                if (seenFiles.contains(key) || seenDirectories.contains(key)) {
                    throw new ZipSecurityException("Duplicate entry: " + cleanName);
                }
                seenFiles.add(key);

                File target = resolveChecked(root, cleanName, effective);
                File parent = target.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new IOException("Cannot create directory: " + parent);
                }

                long written;
                try (InputStream entryStream = zip.getInputStream(entry)) {
                    written = copyEntry(entryStream, target, effective, result.totalBytes);
                }
                result.totalBytes += written;
                if (result.totalBytes > effective.maxTotalBytes) {
                    throw new ZipSecurityException("Archive expands beyond the size limit");
                }
                if (entry.getCompressedSize() > 0) totalCompressed += entry.getCompressedSize();

                if (written > effective.ratioCheckThreshold && entry.getCompressedSize() > 0
                        && written / Math.max(1, entry.getCompressedSize()) > effective.maxEntryRatio) {
                    throw new ZipSecurityException("Suspicious compression ratio for entry: " + cleanName);
                }
                if (result.totalBytes > effective.ratioCheckThreshold * effective.maxTotalRatio
                        && totalCompressed > 0
                        && result.totalBytes / Math.max(1, totalCompressed) > effective.maxTotalRatio) {
                    throw new ZipSecurityException("Suspicious total compression ratio");
                }
                result.entries.add(cleanName);
            }
        }
        return result;
    }

    private static long copyEntry(InputStream input, File target, Limits limits, long alreadyWritten)
            throws IOException, ZipSecurityException {
        long written = 0;
        byte[] buffer = new byte[64 * 1024];
        try (OutputStream output = new FileOutputStream(target)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                written += read;
                if (written > limits.maxEntryBytes || alreadyWritten + written > limits.maxTotalBytes) {
                    output.close();
                    //noinspection ResultOfMethodCallIgnored
                    target.delete();
                    throw new ZipSecurityException("Entry exceeds the size limit: " + target.getName());
                }
                output.write(buffer, 0, read);
            }
        }
        catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            throw e;
        }
        return written;
    }

    private static File resolveChecked(Path root, String name, Limits limits) throws ZipSecurityException {
        Path resolved = root.resolve(name).normalize();
        if (!resolved.startsWith(root)) {
            throw new ZipSecurityException("Entry escapes the extraction directory: " + name);
        }
        return resolved.toFile();
    }

    /**
     * Validates and normalizes one ZIP entry name. Returns "" for an empty
     * (root directory) entry. Throws for absolute paths, traversal, device
     * names, illegal characters and over-long segments.
     */
    public static String validateEntryName(String rawName, Limits limits) throws ZipSecurityException {
        Limits effective = limits != null ? limits : Limits.defaults();
        if (rawName == null) throw new ZipSecurityException("Archive entry with no name");
        if (rawName.indexOf('\0') >= 0) throw new ZipSecurityException("Archive entry with NUL byte");
        String name = rawName.replace('\\', '/').trim();
        if (name.isEmpty()) return "";
        if (name.startsWith("/") || name.startsWith("//")) {
            throw new ZipSecurityException("Absolute path in archive: " + rawName);
        }
        if (name.length() >= 2 && Character.isLetter(name.charAt(0)) && name.charAt(1) == ':') {
            throw new ZipSecurityException("Absolute path in archive: " + rawName);
        }
        String[] segments = name.split("/");
        StringBuilder normalized = new StringBuilder();
        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".")) continue;
            if (segment.equals("..")) {
                throw new ZipSecurityException("Path traversal in archive: " + rawName);
            }
            if (segment.length() > effective.maxNameLength) {
                throw new ZipSecurityException("Over-long path segment in archive: " + segment);
            }
            if (segment.endsWith(".") || segment.endsWith(" ")) {
                throw new ZipSecurityException("Illegal segment ending in archive: " + segment);
            }
            for (int i = 0; i < segment.length(); i++) {
                char character = segment.charAt(i);
                if (character == '<' || character == '>' || character == ':' || character == '"'
                        || character == '|' || character == '?' || character == '*'
                        || character < 0x20) {
                    throw new ZipSecurityException("Illegal character in archive path: " + rawName);
                }
            }
            if (normalized.length() > 0) normalized.append('/');
            normalized.append(segment);
        }
        String result = normalized.toString();
        if (name.endsWith("/") && !result.isEmpty()) result += "/";
        return result;
    }

    private static String trimTrailingSlash(String name) {
        return name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
    }

    public static List<String> entryNames(Result result) {
        return new ArrayList<>(result.entries);
    }
}

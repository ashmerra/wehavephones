package com.wehavephones.mobile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.FileVisitResult;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Copies a complete installation without exposing partial files to the game scanner. */
public final class WowFileImporter {
    public static final String FOLDER_NAME = "World of Warcraft";
    static final String STAGING_NAME = ".wehavephones-wow-import";
    static final long SPACE_RESERVE = 256L * 1024 * 1024;

    public enum Problem { MISSING_SOURCE, INVALID_SOURCE, ALREADY_INSTALLED, NOT_ENOUGH_SPACE,
        SOURCE_CHANGED, COPY_FAILED, CANCELLED }

    public static final class ImportException extends IOException {
        public final Problem problem;
        public long requiredBytes, availableBytes;

        ImportException(Problem problem) { super(problem.name()); this.problem = problem; }
    }

    public interface Progress { void onProgress(long copiedBytes, long totalBytes); }

    private static final class Entry {
        final File source;
        final String relative;
        final boolean directory;
        final long size, modified;

        Entry(File source, String relative) {
            this.source = source;
            this.relative = relative;
            directory = source.isDirectory();
            size = directory ? 0 : source.length();
            modified = source.lastModified();
        }
    }

    private WowFileImporter() {}

    public static void copy(File source, File destination, File alternateDestination,
            Progress progress, BooleanSupplier cancelled) throws ImportException {
        copy(source, destination, alternateDestination, progress, cancelled,
                () -> destination.getParentFile().getUsableSpace());
    }

    // Injectable available space keeps low-storage failures testable without filling a disk.
    static void copy(File source, File destination, File alternateDestination,
            Progress progress, BooleanSupplier cancelled, LongSupplier availableSpace) throws ImportException {
        File staging = new File(destination.getParentFile(), STAGING_NAME);
        boolean ownsStaging = false;
        try {
            checkCancelled(cancelled);
            checkDestination(destination, alternateDestination);
            if (!source.isDirectory()) throw new ImportException(Problem.MISSING_SOURCE);
            validateInstallation(source);
            ArrayList<Entry> entries = new ArrayList<>();
            long total = scan(source, "", entries, cancelled);
            if (total == 0) throw new ImportException(Problem.INVALID_SOURCE);
            File parent = destination.getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create destination parent");
            // A process killed during an earlier import leaves only this dedicated temporary folder.
            deleteTree(staging);
            long available = availableSpace.getAsLong();
            long required = Math.addExact(total, SPACE_RESERVE);
            if (available < required) {
                ImportException failure = new ImportException(Problem.NOT_ENOUGH_SPACE);
                failure.requiredBytes = required;
                failure.availableBytes = available;
                throw failure;
            }
            checkCancelled(cancelled);
            Files.createDirectory(staging.toPath());
            ownsStaging = true;
            progress.onProgress(0, total);
            byte[] buffer = new byte[1024 * 1024];
            long copied = 0;
            for (Entry entry : entries) {
                checkCancelled(cancelled);
                if (Files.isSymbolicLink(entry.source.toPath())
                        || entry.source.isDirectory() != entry.directory
                        || (!entry.directory && (!entry.source.isFile() || entry.source.length() != entry.size
                            || entry.source.lastModified() != entry.modified))) {
                    throw new ImportException(Problem.SOURCE_CHANGED);
                }
                Path target = new File(staging, entry.relative).toPath();
                if (entry.directory) {
                    Files.createDirectory(target);
                    continue;
                }
                long fileBytes = 0;
                try (InputStream input = Files.newInputStream(entry.source.toPath());
                     OutputStream output = Files.newOutputStream(target, java.nio.file.StandardOpenOption.CREATE_NEW,
                             java.nio.file.StandardOpenOption.WRITE)) {
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        checkCancelled(cancelled);
                        fileBytes += read;
                        if (fileBytes > entry.size) throw new ImportException(Problem.SOURCE_CHANGED);
                        output.write(buffer, 0, read);
                        copied += read;
                        progress.onProgress(copied, total);
                    }
                }
                if (fileBytes != entry.size || entry.source.lastModified() != entry.modified) {
                    throw new ImportException(Problem.SOURCE_CHANGED);
                }
            }
            checkCancelled(cancelled);
            checkDestination(destination, alternateDestination);
            // Same filesystem; no REPLACE_EXISTING. The scanner sees the folder only when complete.
            Files.move(staging.toPath(), destination.toPath());
            ownsStaging = false;
        }
        catch (ImportException failure) { throw failure; }
        catch (IOException | RuntimeException failure) {
            ImportException wrapped = new ImportException(Problem.COPY_FAILED);
            wrapped.initCause(failure);
            throw wrapped;
        }
        finally {
            if (ownsStaging) {
                try { deleteTree(staging); }
                catch (IOException ignored) { /* The next attempt cleans up this staging folder. */ }
            }
        }
    }

    private static void checkDestination(File destination, File alternate) throws ImportException {
        if (Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS)
                || (alternate != null && Files.exists(alternate.toPath(), LinkOption.NOFOLLOW_LINKS))) {
            throw new ImportException(Problem.ALREADY_INSTALLED);
        }
    }

    private static void validateInstallation(File source) throws ImportException {
        if (Files.isSymbolicLink(source.toPath()) || !new File(source, "Data").isDirectory()
                || !new File(source, ".build.info").isFile()) {
            throw new ImportException(Problem.INVALID_SOURCE);
        }
        File[] children = source.listFiles();
        if (children != null) {
            for (File child : children) {
                if (!child.isDirectory() || !child.getName().matches("_[A-Za-z0-9_]+_")) continue;
                String[] names = child.list();
                if (names != null && !WowFlavor.pickExecutable(Arrays.asList(names)).isEmpty()) return;
            }
        }
        throw new ImportException(Problem.INVALID_SOURCE);
    }

    private static long scan(File file, String relative, ArrayList<Entry> entries,
            BooleanSupplier cancelled) throws IOException {
        checkCancelled(cancelled);
        if (Files.isSymbolicLink(file.toPath())) throw new ImportException(Problem.INVALID_SOURCE);
        if (!file.isDirectory() && !file.isFile()) throw new ImportException(Problem.INVALID_SOURCE);
        Entry entry = new Entry(file, relative);
        if (!relative.isEmpty()) entries.add(entry);
        if (!entry.directory) return entry.size;
        File[] children = file.listFiles();
        if (children == null) throw new IOException("Cannot read source directory");
        long total = 0;
        for (File child : children) {
            String childRelative = relative.isEmpty() ? child.getName() : relative + "/" + child.getName();
            total = Math.addExact(total, scan(child, childRelative, entries, cancelled));
        }
        return total;
    }

    private static void checkCancelled(BooleanSupplier cancelled) throws ImportException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new ImportException(Problem.CANCELLED);
        }
    }

    private static void deleteTree(File file) throws IOException {
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(file.toPath(), new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException {
                Files.delete(path);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path path, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.delete(path);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}

package com.wehavephones.mobile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicBoolean;

/** Real filesystem fixtures exercise content integrity and preservation on failed imports. */
public final class WowFileImporterSelfTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path temporary = Files.createTempDirectory("wow-import-test-");
        try {
            File source = fixture(temporary.resolve("Downloads/World of Warcraft"));
            File destination = temporary.resolve("prefix/Program Files (x86)/World of Warcraft").toFile();
            File fallback = temporary.resolve("prefix/Program Files/World of Warcraft").toFile();
            long[] progress = {0, 0};
            WowFileImporter.copy(source, destination, fallback, (copied, total) -> {
                check(copied >= progress[0] && copied <= total, "bounded monotonic byte progress");
                check(!destination.exists(), "destination stays hidden during copy");
                progress[0] = copied; progress[1] = total;
            }, () -> false);
            check(progress[0] == progress[1] && progress[0] > 2 * 1024 * 1024, "all bytes counted");
            compareTree(source.toPath(), destination.toPath());
            check(source.isDirectory(), "source retained");
            check(new File(destination, "_retail_/empty").isDirectory(), "empty directories retained");
            check(!staging(destination).exists(), "staging consumed on success");

            expect(WowFileImporter.Problem.ALREADY_INSTALLED, () -> copy(source, destination, fallback));
            compareTree(source.toPath(), destination.toPath());
            delete(destination.toPath());
            fallback.mkdirs();
            Files.write(new File(fallback, "keep.txt").toPath(), new byte[]{7});
            expect(WowFileImporter.Problem.ALREADY_INSTALLED, () -> copy(source, destination, fallback));
            check(new File(fallback, "keep.txt").isFile(), "fallback installation retained");
            delete(fallback.toPath());

            expect(WowFileImporter.Problem.MISSING_SOURCE,
                    () -> copy(temporary.resolve("missing").toFile(), destination, fallback));
            File invalid = temporary.resolve("version-only").toFile(); invalid.mkdirs();
            Files.write(new File(invalid, "Wow.exe").toPath(), new byte[]{1});
            expect(WowFileImporter.Problem.INVALID_SOURCE, () -> copy(invalid, destination, fallback));

            try {
                WowFileImporter.copy(source, destination, fallback, (copied, total) -> {}, () -> false, () -> 1);
                throw new AssertionError("Expected free space failure");
            }
            catch (WowFileImporter.ImportException failure) {
                check(failure.problem == WowFileImporter.Problem.NOT_ENOUGH_SPACE, "low space refused");
                check(failure.requiredBytes > WowFileImporter.SPACE_RESERVE && failure.availableBytes == 1,
                        "space requirements reported");
            }
            check(!destination.exists() && !staging(destination).exists(), "low space exposes no files");

            AtomicBoolean cancelled = new AtomicBoolean();
            expect(WowFileImporter.Problem.CANCELLED, () -> WowFileImporter.copy(source, destination, fallback,
                    (copied, total) -> { if (copied > 0) cancelled.set(true); }, cancelled::get));
            check(!destination.exists() && !staging(destination).exists(), "cancelled copy cleaned up");
            check(new File(source, "Data/data.000").isFile(), "cancellation retains source data");

            staging(destination).mkdirs();
            Files.write(new File(staging(destination), "old-partial-file").toPath(), new byte[]{9});
            copy(source, destination, fallback);
            check(!new File(destination, "old-partial-file").exists(), "orphaned staging discarded on retry");
            compareTree(source.toPath(), destination.toPath());
            delete(destination.toPath());

            AtomicBoolean race = new AtomicBoolean();
            expect(WowFileImporter.Problem.ALREADY_INSTALLED, () -> WowFileImporter.copy(source, destination, fallback,
                    (copied, total) -> {
                        if (race.compareAndSet(false, true)) {
                            destination.mkdirs();
                            try { Files.write(new File(destination, "keep.txt").toPath(), new byte[]{5}); }
                            catch (IOException failure) { throw new RuntimeException(failure); }
                        }
                    }, () -> false));
            check(Arrays.equals(new byte[]{5}, Files.readAllBytes(new File(destination, "keep.txt").toPath())),
                    "installation created during copy preserved");
            check(!staging(destination).exists(), "race cleans only staging");
            delete(destination.toPath());

            Path outside = temporary.resolve("outside.txt"); Files.write(outside, new byte[]{3});
            Path link = new File(source, "Data/linked-file").toPath(); Files.createSymbolicLink(link, outside);
            expect(WowFileImporter.Problem.INVALID_SOURCE, () -> copy(source, destination, fallback));
            check(Arrays.equals(new byte[]{3}, Files.readAllBytes(outside)), "symlink target untouched");
            Files.delete(link);

            AtomicBoolean changed = new AtomicBoolean();
            expect(WowFileImporter.Problem.SOURCE_CHANGED, () -> WowFileImporter.copy(source, destination, fallback,
                    (copied, total) -> {
                        if (copied == 0 && changed.compareAndSet(false, true)) {
                            try { Files.write(new File(source, "Data/data.000").toPath(), new byte[]{1}); }
                            catch (IOException failure) { throw new RuntimeException(failure); }
                        }
                    }, () -> false));
            check(!destination.exists() && !staging(destination).exists(), "changing source leaves no partial install");
            System.out.println("[wow-file-importer-selftest] PASS: " + checks + " checks");
        }
        finally { delete(temporary); }
    }

    private static File fixture(Path root) throws IOException {
        Files.createDirectories(root.resolve("Data"));
        Files.createDirectories(root.resolve("_retail_/empty"));
        Files.createDirectories(root.resolve("_classic_era_/Interface/AddOns"));
        Files.write(root.resolve(".build.info"), "Product!STRING:0|Version!STRING:0\nwow|12.0.0\n".getBytes());
        Files.write(root.resolve(".product.db"), new byte[]{0, 1, 2, 3});
        byte[] data = new byte[2 * 1024 * 1024 + 33];
        for (int i = 0; i < data.length; i++) data[i] = (byte)(i * 31);
        Files.write(root.resolve("Data/data.000"), data);
        Files.write(root.resolve("_retail_/Wow.exe"), new byte[]{4, 5});
        Files.write(root.resolve("_classic_era_/WowClassic.exe"), new byte[]{6, 7});
        Files.write(root.resolve("_classic_era_/Interface/AddOns/test.lua"), new byte[]{8, 9});
        return root.toFile();
    }

    private static File staging(File destination) { return new File(destination.getParentFile(), WowFileImporter.STAGING_NAME); }
    private static void copy(File source, File destination, File fallback) throws IOException {
        WowFileImporter.copy(source, destination, fallback, (copied, total) -> {}, () -> false);
    }

    private interface Operation { void run() throws Exception; }
    private static void expect(WowFileImporter.Problem problem, Operation operation) throws Exception {
        try { operation.run(); throw new AssertionError("Expected " + problem); }
        catch (WowFileImporter.ImportException failure) { check(failure.problem == problem, "expected " + problem); }
    }

    private static void compareTree(Path source, Path target) throws IOException {
        try (java.util.stream.Stream<Path> paths = Files.walk(source)) {
            for (Path file : (Iterable<Path>)paths::iterator) {
                Path copy = target.resolve(source.relativize(file));
                check(Files.exists(copy), "copied " + source.relativize(file));
                if (Files.isRegularFile(file)) check(Arrays.equals(Files.readAllBytes(file), Files.readAllBytes(copy)),
                        "content retained " + source.relativize(file));
            }
        }
    }

    private static void delete(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            for (Path path : (Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator) Files.delete(path);
        }
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}

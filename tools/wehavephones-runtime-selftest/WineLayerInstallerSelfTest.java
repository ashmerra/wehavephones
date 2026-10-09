package com.wehavephones.mobile;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Host test for WineLayerInstaller over file:// URLs with a fake extractor:
 * a verified download lands under opt/<name> and leaves no staging or cached
 * archive behind; a sha256 mismatch, an extraction without bin/wine, an
 * existing incomplete layer, or a bad name all fail without leaving a layer
 * that looks installed; an installed layer or a verified cached archive is
 * never downloaded again.
 */
public final class WineLayerInstallerSelfTest {
    private static final String NAME = "proton-10.0-34-arm64ec";
    private static int failures;

    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("wine-layer-selftest").toFile();
        try {
            byte[] payload = "fake wcp".getBytes(StandardCharsets.UTF_8);
            File source = new File(root, "src.wcp");
            Files.write(source.toPath(), payload);
            String url = source.toURI().toString();
            String sha = sha256(payload);
            String missingUrl = new File(root, "missing.wcp").toURI().toString();

            // Fresh install.
            File opt = dir(root, "opt1"), cache = dir(root, "cache1");
            long[] progress = {0};
            String error = WineLayerInstaller.install(NAME, url, sha, opt, cache, WineLayerInstallerSelfTest::fakeLayer,
                    (done, total) -> progress[0] = done);
            check("install succeeds", error == null);
            check("layer installed", WineLayerInstaller.isInstalled(opt, NAME));
            check("download progress reported", progress[0] == payload.length);
            check("no staging left", !new File(opt, NAME + ".installing").exists());
            check("cached archive removed", !new File(cache, NAME + ".wcp").exists());

            // Already installed: no download, no extraction.
            error = WineLayerInstaller.install(NAME, missingUrl, sha, opt, cache, (a, d) -> false, null);
            check("installed layer is left alone", error == null);

            // Corrupt download.
            opt = dir(root, "opt2");
            cache = dir(root, "cache2");
            error = WineLayerInstaller.install(NAME, url, "0".repeat(64), opt, cache, WineLayerInstallerSelfTest::fakeLayer, null);
            check("sha256 mismatch fails", error != null && error.contains("corrupt"));
            check("sha256 mismatch installs nothing", !new File(opt, NAME).exists());
            check("sha256 mismatch keeps no download", isEmpty(cache));

            // Extraction without the Wine binary.
            opt = dir(root, "opt3");
            cache = dir(root, "cache3");
            error = WineLayerInstaller.install(NAME, url, sha, opt, cache, (archive, destination) -> true, null);
            check("layer without bin/wine fails", error != null && error.contains("bin/wine"));
            check("layer without bin/wine is not installed", !new File(opt, NAME).exists());
            check("failed extraction leaves no staging", !new File(opt, NAME + ".installing").exists());

            // A verified cached archive is reused (source unreachable).
            error = WineLayerInstaller.install(NAME, missingUrl, sha, opt, cache, WineLayerInstallerSelfTest::fakeLayer, null);
            check("cached archive reused", error == null && WineLayerInstaller.isInstalled(opt, NAME));

            // Unreachable source.
            opt = dir(root, "opt4");
            cache = dir(root, "cache4");
            error = WineLayerInstaller.install(NAME, missingUrl, sha, opt, cache, WineLayerInstallerSelfTest::fakeLayer, null);
            check("unreachable source fails", error != null && !new File(opt, NAME).exists());

            // Incomplete layer from elsewhere is never replaced.
            opt = dir(root, "opt5");
            File stray = new File(opt, NAME + "/keep.txt");
            stray.getParentFile().mkdirs();
            Files.write(stray.toPath(), payload);
            error = WineLayerInstaller.install(NAME, url, sha, opt, dir(root, "cache5"), WineLayerInstallerSelfTest::fakeLayer, null);
            check("incomplete layer refused", error != null && stray.isFile());

            // Names that could escape opt/.
            error = WineLayerInstaller.install("../proton-x", url, sha, dir(root, "opt6"), dir(root, "cache6"),
                    WineLayerInstallerSelfTest::fakeLayer, null);
            check("path-like name refused", error != null && !new File(root, "proton-x").exists());

            opt = dir(root, "offline-opt");
            cache = dir(root, "offline-cache");
            error = WineLayerInstaller.installBundled(NAME, sha, opt, cache,
                    () -> new java.io.ByteArrayInputStream(payload), WineLayerInstallerSelfTest::fakeLayer);
            check("bundled layer installs without a URL", error == null && WineLayerInstaller.isInstalled(opt, NAME));
            error = WineLayerInstaller.installBundled(NAME, sha, opt, cache,
                    () -> { throw new IOException("asset unavailable"); }, (a, d) -> false);
            check("existing layer never reads bundled source", error == null);

            opt = dir(root, "corrupt-offline-opt");
            cache = dir(root, "corrupt-offline-cache");
            error = WineLayerInstaller.installBundled(NAME, "0".repeat(64), opt, cache,
                    () -> new java.io.ByteArrayInputStream(payload), WineLayerInstallerSelfTest::fakeLayer);
            check("corrupt bundled layer rejected", error != null && !new File(opt, NAME).exists() && isEmpty(cache));
        }
        finally {
            try (Stream<java.nio.file.Path> paths = Files.walk(root.toPath())) {
                paths.sorted(Comparator.reverseOrder()).forEach((path) -> path.toFile().delete());
            }
        }

        if (failures > 0) {
            System.out.println("WineLayerInstallerSelfTest: " + failures + " failure(s)");
            System.exit(1);
        }
        System.out.println("WineLayerInstallerSelfTest: ok");
    }

    private static boolean fakeLayer(File archive, File destination) {
        try {
            new File(destination, "bin").mkdirs();
            Files.write(new File(destination, "bin/wine").toPath(), new byte[] {1});
            Files.write(new File(destination, "profile.json").toPath(), "{}".getBytes(StandardCharsets.UTF_8));
            return archive.isFile();
        }
        catch (IOException e) {
            return false;
        }
    }

    private static File dir(File root, String name) {
        File dir = new File(root, name);
        dir.mkdirs();
        return dir;
    }

    private static boolean isEmpty(File dir) {
        String[] names = dir.list();
        return names == null || names.length == 0;
    }

    private static String sha256(byte[] data) throws Exception {
        StringBuilder builder = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(data)) builder.append(String.format(Locale.US, "%02x", value));
        return builder.toString();
    }

    private static void check(String name, boolean ok) {
        if (ok) System.out.println("  ok   " + name);
        else {
            System.out.println("  FAIL " + name);
            failures++;
        }
    }
}

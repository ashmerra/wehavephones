package com.wehavephones.core;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

public final class WineFontPathsSelfTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("wine-font-paths-test-");
        try {
            File wine = root.resolve("opt/proton-test").toFile();
            Path fonts = wine.toPath().resolve("share/wine/fonts");
            Files.createDirectories(fonts);
            for (String name : new String[]{"tahoma.ttf", "tahomabd.ttf", "symbol.ttf"})
                Files.write(fonts.resolve(name), new byte[]{1});
            Path prefix = root.resolve("home/xuser/.wine");
            Files.createDirectories(prefix.resolve("drive_c/windows/Fonts"));
            // A user-installed font must continue to take precedence over Wine's copy.
            Files.write(prefix.resolve("drive_c/windows/Fonts/SYMBOL.TTF"), new byte[]{2});
            String registry = "WINE REGISTRY Version 2\r\n\r\n"
                    + "[Software\\\\Microsoft\\\\Windows NT\\\\CurrentVersion\\\\Fonts] 123\r\n"
                    + "\"Tahoma (TrueType)\"=\"tahoma.ttf\"\r\n"
                    + "\"Tahoma Bold (TrueType)\"=\"TAHOMABD.TTF\"\r\n"
                    + "\"Symbol (TrueType)\"=\"symbol.ttf\"\r\n"
                    + "\"Custom\"=\"custom.ttf\"\r\n"
                    + "\"Absolute\"=\"C:\\\\windows\\\\Fonts\\\\tahoma.ttf\"\r\n"
                    + "[Software\\\\Microsoft\\\\Windows\\\\CurrentVersion\\\\Fonts]\r\n"
                    + "\"Tahoma\"=\"tahoma.ttf\"\r\n"
                    + "[Software\\\\Unrelated]\r\n\"Other\"=\"tahoma.ttf\"\r\n";
            Path file = prefix.resolve("system.reg");
            Files.write(file, registry.getBytes(StandardCharsets.UTF_8));
            check(WineFontPaths.repair(root.toFile(), wine, prefix.toFile()) == 3,
                    "Repair both font keys and case-insensitive filenames");
            String repaired = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            String expected = registry.replace("\"Tahoma (TrueType)\"=\"tahoma.ttf\"",
                    "\"Tahoma (TrueType)\"=\"Z:\\\\opt\\\\proton-test\\\\share\\\\wine\\\\fonts\\\\tahoma.ttf\"")
                    .replace("\"Tahoma Bold (TrueType)\"=\"TAHOMABD.TTF\"",
                    "\"Tahoma Bold (TrueType)\"=\"Z:\\\\opt\\\\proton-test\\\\share\\\\wine\\\\fonts\\\\tahomabd.ttf\"")
                    .replace("\"Tahoma\"=\"tahoma.ttf\"",
                    "\"Tahoma\"=\"Z:\\\\opt\\\\proton-test\\\\share\\\\wine\\\\fonts\\\\tahoma.ttf\"");
            check(repaired.equals(expected), "Preserve custom fonts, other values and CRLF exactly");
            byte[] patchBytes = Files.readAllBytes(prefix.resolve("drive_c/windows/wehavephones-font-paths.reg"));
            check(patchBytes[0] == (byte)0xff && patchBytes[1] == (byte)0xfe,
                    "Guest registry patch uses UTF-16 with a BOM");
            String patch = new String(patchBytes, StandardCharsets.UTF_16LE);
            check(patch.contains("[HKEY_LOCAL_MACHINE\\Software\\Microsoft\\Windows NT\\CurrentVersion\\Fonts]"),
                    "Import font keys after wineboot resets them from wine.inf");
            check(!patch.contains("Symbol") && !patch.contains("Custom") && !patch.contains("Unrelated"),
                    "Import only selected Wine fonts; never copy unrelated registry values");
            check(WineFontPaths.repair(root.toFile(), wine, prefix.toFile()) == 0,
                    "A repaired registry is unchanged on subsequent starts");
            check(Files.readAllBytes(prefix.resolve("drive_c/windows/wehavephones-font-paths.reg")).length == patchBytes.length,
                    "Keep the guest repair available on subsequent starts");
            String launcher = WineFontPaths.startupBatch(prefix.toFile(), "Battle net.bat", " --test \"two words\"");
            check(launcher.equals("C:\\windows\\temp\\wehavephones-font-startup.bat"), "Wrap the batch launch");
            String batch = new String(Files.readAllBytes(prefix.resolve("drive_c/windows/temp/wehavephones-font-startup.bat")),
                    StandardCharsets.UTF_8);
            check(batch.contains("reg.exe import \"C:\\windows\\wehavephones-font-paths.reg\" >NUL\r\n"),
                    "Import before launching the installer or Battle.net");
            check(batch.endsWith("call \"Battle net.bat\" --test \"two words\"\r\n"),
                    "Preserve batch filename, working directory and arguments");
            check(batch.contains("if errorlevel 1 exit /b 1"), "Do not start CEF when the repair fails");
            check(WineFontPaths.repair(root.toFile(), root.resolve("missing").toFile(), prefix.toFile()) == 0,
                    "Missing Wine font directory is harmless");
            check(WineFontPaths.startupBatch(root.resolve("empty").toFile(), "original.bat", "").equals("original.bat"),
                    "Leave launches without a font repair unchanged");
            try (java.util.stream.Stream<Path> paths = Files.list(prefix)) {
                check(paths.noneMatch(p -> p.getFileName().toString().startsWith("font-paths-")),
                        "Leave no staged registry files");
            }
            System.out.println("WineFontPathsSelfTest PASS");
        }
        finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator)
                    Files.delete(path);
            }
        }
    }
}

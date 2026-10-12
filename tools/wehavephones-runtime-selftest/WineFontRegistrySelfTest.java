package com.wehavephones.core;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Host test for WineFontRegistry: the DirectWrite font list repair in system.reg, run by WineFontPaths. */
public final class WineFontRegistrySelfTest {
    private static int failures;

    // The broken Proton prefix as found on the phone (2026-10-12), trimmed.
    private static final String REG = "WINE REGISTRY Version 2\n"
            + ";; All keys relative to \\\\Machine\n\n"
            + "[Software\\\\Microsoft\\\\Windows NT\\\\CurrentVersion\\\\Fonts] 1760280000\n"
            + "#time=1dc3b8f9a1b2c3d\n"
            + "\"@SimSun (TrueType)\"=\"Z:\\\\usr\\\\share\\\\fonts\\\\truetype\\\\ms\\\\simsun.ttc\"\n"
            + "\"Arial (TrueType)\"=\"arial.ttf\"\n"
            + "\"Malgun Gothic (TrueType)\"=\"malgun.ttf\"\n"
            + "\"Microsoft YaHei (TrueType)\"=\"msyh.ttf\"\n"
            + "\"MS Gothic (TrueType)\"=\"msgothic.ttc\"\n"
            + "\"NSimSun (TrueType)\"=\"simsun.ttc\"\n"
            + "\"SimSun (TrueType)\"=\"simsun.ttc\"\n"
            + "\n"
            + "[Software\\\\Microsoft\\\\Windows NT\\\\CurrentVersion\\\\FontSubstitutes] 1760280000\n"
            + "\"MS Shell Dlg\"=\"Tahoma\"\n";

    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("wine-font-registry").toFile();
        try {
            File fonts = new File(root, "drive_c/windows/Fonts");
            File imageFs = new File(root, "imagefs");
            File ms = new File(imageFs, "usr/share/fonts/truetype/ms");
            fonts.mkdirs();
            ms.mkdirs();
            for (String name : new String[]{"arial.ttf", "msyh.ttc", "SimHei.ttf", "SourceHanSansCN-Regular.otf"}) {
                Files.write(new File(fonts, name).toPath(), new byte[]{1});
            }
            for (String name : new String[]{"simsun.ttc", "malgun.ttf"}) Files.write(new File(ms, name).toPath(), new byte[]{1});
            WineFontRegistry.FileCheck check = path -> WineFontRegistry.exists(path, fonts, imageFs);

            String fixed = WineFontRegistry.repair(REG, check);
            check("broken prefix is repaired", fixed != null);
            check("YaHei points at the real collection", fixed.contains("\"Microsoft YaHei (TrueType)\"=\"msyh.ttc\"\n"));
            check("SimHei is registered", fixed.contains("\"SimHei (TrueType)\"=\"SimHei.ttf\"\n"));
            check("Source Han Sans is registered", fixed.contains("\"Source Han Sans CN (TrueType)\"=\"SourceHanSansCN-Regular.otf\"\n"));
            check("missing PMingLiU is not registered", !fixed.contains("PMingLiU"));
            check("SimSun gets its image path",
                    fixed.contains("\"SimSun (TrueType)\"=\"Z:\\\\usr\\\\share\\\\fonts\\\\truetype\\\\ms\\\\simsun.ttc\"\n"));
            check("NSimSun gets its image path",
                    fixed.contains("\"NSimSun (TrueType)\"=\"Z:\\\\usr\\\\share\\\\fonts\\\\truetype\\\\ms\\\\simsun.ttc\"\n"));
            check("Malgun Gothic gets its image path",
                    fixed.contains("\"Malgun Gothic (TrueType)\"=\"Z:\\\\usr\\\\share\\\\fonts\\\\truetype\\\\ms\\\\malgun.ttf\"\n"));
            check("a value with no existing file is kept", fixed.contains("\"MS Gothic (TrueType)\"=\"msgothic.ttc\"\n"));
            check("a working value is kept", fixed.contains("\"Arial (TrueType)\"=\"arial.ttf\"\n"));
            check("@ entries are kept", fixed.contains("\"@SimSun (TrueType)\"="));
            check("new values stay in the Fonts key", fixed.indexOf("SimHei") < fixed.indexOf("FontSubstitutes"));
            check("the next key is untouched", fixed.endsWith("\n\n[Software\\\\Microsoft\\\\Windows NT\\\\CurrentVersion\\\\FontSubstitutes] 1760280000\n"
                    + "\"MS Shell Dlg\"=\"Tahoma\"\n"));
            check("repair is idempotent", WineFontRegistry.repair(fixed, check) == null);
            check("no Fonts key, no change", WineFontRegistry.repair("WINE REGISTRY Version 2\n", check) == null);
            check("bare names match case-insensitively", WineFontRegistry.exists("MSYH.TTC", fonts, imageFs));
            check("other drives are left alone", WineFontRegistry.exists("D:\\fonts\\x.ttf", fonts, imageFs));

            // End to end through WineFontPaths, which runs before every session start.
            File prefix = new File(root, "prefix");
            File prefixFonts = new File(prefix, "drive_c/windows/Fonts");
            prefixFonts.mkdirs();
            for (File font : fonts.listFiles()) Files.copy(font.toPath(), new File(prefixFonts, font.getName()).toPath());
            File reg = new File(prefix, "system.reg");
            Files.write(reg.toPath(), REG.getBytes(StandardCharsets.UTF_8));
            File wine = new File(imageFs, "opt/proton-test");
            new File(wine, "share/wine/fonts").mkdirs();
            check("session start repairs the registry", WineFontPaths.repair(imageFs, wine, prefix) == 6);
            check("session start writes the same repair",
                    fixed.equals(new String(Files.readAllBytes(reg.toPath()), StandardCharsets.UTF_8)));
            String patch = new String(Files.readAllBytes(new File(prefix, "drive_c/windows/wehavephones-font-paths.reg").toPath()),
                    StandardCharsets.UTF_16LE);
            check("wineboot-proof import carries YaHei", patch.contains("\"Microsoft YaHei (TrueType)\"=\"msyh.ttc\"\r\n"));
            check("wineboot-proof import carries SimSun",
                    patch.contains("\"SimSun (TrueType)\"=\"Z:\\\\usr\\\\share\\\\fonts\\\\truetype\\\\ms\\\\simsun.ttc\"\r\n"));
            check("import leaves other fonts out", !patch.contains("Arial") && !patch.contains("MS Gothic"));
            check("second session start changes nothing", WineFontPaths.repair(imageFs, wine, prefix) == 0);
        }
        finally {
            deleteTree(root);
        }
        if (failures > 0) {
            System.out.println("WineFontRegistrySelfTest: " + failures + " FAILED");
            System.exit(1);
        }
        System.out.println("WineFontRegistrySelfTest: PASS");
    }

    private static void check(String name, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + name);
        if (!ok) failures++;
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }
}

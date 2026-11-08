package com.wehavephones.core;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Host test for WineAcmCodecs: the system.ini [drivers32] codec repair run before each session. */
public final class WineAcmCodecsSelfTest {
    private static int failures;

    // The prefix's system.ini as found on the S23 (2026-11-07), with Windows line endings.
    private static final String INI = "[mci]\r\n"
            + "MPEGVideo=mciqtz32.dll\r\n"
            + "waveaudio=mciwave.dll\r\n"
            + "[drivers32]\r\n"
            + "msacm.imaadpcm=imaadp32.acm\r\n"
            + "msacm.msadpcm=msadp32.acm\r\n"
            + "msacm.msg711=msg711.acm\r\n"
            + "msacm.l3acm=l3codeca.acm\r\n"
            + "msacm.msgsm610=msgsm32.acm\r\n"
            + "vidc.mrle=msrle32.dll\r\n"
            + "; vidc.IV31=ir32_32.dll\r\n"
            + "[MCI32]\r\n"
            + "MPEGVideo=mciqtz32.dll\r\n";

    public static void main(String[] args) throws Exception {
        String off = WineAcmCodecs.disable(INI);
        check(off != null, "codecs are disabled");
        check(off != null && !off.contains("\nmsacm."), "no active codec line remains");
        check(off != null && off.contains(WineAcmCodecs.MARK + "msacm.l3acm=l3codeca.acm\r\n"), "lines are marked, CRLF kept");
        check(off != null && off.contains("\nvidc.mrle=msrle32.dll\r\n") && off.contains("[mci]\r\nMPEGVideo=mciqtz32.dll"),
                "video codecs and other sections are untouched");
        check(WineAcmCodecs.disable(off) == null, "a second run changes nothing");

        // Wine's prefix update re-adds an active line beside the marked one.
        String readded = off.replace("vidc.mrle=", "msacm.imaadpcm=imaadp32.acm\r\nvidc.mrle=");
        String again = WineAcmCodecs.disable(readded);
        check(again != null && again.equals(off), "a re-added codec is folded into its marked copy");

        String back = WineAcmCodecs.restore(off);
        check(INI.equals(back), "restore gives the original file back");
        check(WineAcmCodecs.restore(INI) == null, "restore leaves an untouched file alone");
        String both = WineAcmCodecs.restore(readded);
        check(both != null && both.split("msacm.imaadpcm=imaadp32.acm", -1).length == 2, "restore keeps one copy of a re-added codec");

        String other = "[drivers32]\nmsacm.x=y\n[mci32]\nmsacm.z=w\n";
        String scoped = WineAcmCodecs.disable(other);
        check(scoped != null && scoped.contains("\nmsacm.z=w") && scoped.contains(WineAcmCodecs.MARK + "msacm.x=y"),
                "only [drivers32] is edited");

        File root = Files.createTempDirectory("wine-acm").toFile();
        try {
            File ini = new File(root, "drive_c/windows/system.ini");
            ini.getParentFile().mkdirs();
            Files.write(ini.toPath(), INI.getBytes(StandardCharsets.ISO_8859_1));
            check(WineAcmCodecs.apply(root, false) == 5, "apply reports five changed lines");
            check(WineAcmCodecs.apply(root, false) == 0, "apply is idempotent");
            check(WineAcmCodecs.apply(root, true) == 5, "keep restores five lines");
            check(INI.equals(new String(Files.readAllBytes(ini.toPath()), StandardCharsets.ISO_8859_1)), "file round-trips");
            check(!new File(ini.getPath() + ".wehavephones-tmp").exists(), "no temp file is left");
            check(WineAcmCodecs.apply(new File(root, "missing"), false) == 0, "a prefix without system.ini is skipped");
        }
        finally {
            for (File f : new File(root, "drive_c/windows").listFiles()) f.delete();
            new File(root, "drive_c/windows").delete();
            new File(root, "drive_c").delete();
            root.delete();
        }

        if (failures > 0) {
            System.out.println("WineAcmCodecsSelfTest: " + failures + " FAILED");
            System.exit(1);
        }
        System.out.println("WineAcmCodecsSelfTest: PASS");
    }

    private static void check(boolean ok, String name) {
        System.out.println((ok ? "ok   " : "FAIL ") + name);
        if (!ok) failures++;
    }
}

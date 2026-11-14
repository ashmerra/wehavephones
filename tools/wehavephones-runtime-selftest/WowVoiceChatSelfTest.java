package com.wehavephones.core;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Host test for WowVoiceChat: the user.reg override that keeps WoW's voice chat process from loading. */
public final class WowVoiceChatSelfTest {
    private static int failures;

    // An excerpt of the S23 container's user.reg (2026-11-13).
    private static final String REG = "WINE REGISTRY Version 2\n"
            + ";; All keys relative to \\\\User\\\\S-1-5-21-0-0-0-1000\n"
            + "\n"
            + "#arch=win64\n"
            + "\n"
            + "[Software\\\\Wine\\\\AppDefaults\\\\ActOfWar.exe] 1791609822\n"
            + "#time=1dd5877840086dc\n"
            + "\"Version\"=\"winxp\"\n"
            + "\n"
            + "[Software\\\\Wine\\\\AppDefaults\\\\Battle.net.exe\\\\X11 Driver] 1791609830\n"
            + "#time=1dd58778882c292\n"
            + "\"ClientSideGraphics\"=\"N\"\n"
            + "\n"
            + "[Software\\\\Wine\\\\Drivers] 1791609830\n"
            + "#time=1dd58778882c292\n"
            + "\"Audio\"=\"alsa\"\n";

    private static final String HEADER = "[" + WowVoiceChat.KEY + "]";

    public static void main(String[] args) throws Exception {
        String off = WowVoiceChat.disable(REG, 1794700000000L);
        check(off != null && off.startsWith(REG), "the override is appended and the rest is kept");
        check(off != null && off.contains("\n\n" + HEADER + " 1794700000\n#time="), "the key gets Wine's header and a blank line before it");
        check(off != null && off.endsWith("\n" + WowVoiceChat.VALUE + "\n"), "vivoxsdk is disabled");
        check(off != null && WowVoiceChat.disable(off, 1794700000000L) == null, "a second run changes nothing");

        String on = WowVoiceChat.enable(off);
        check(REG.equals(on), "turning voice chat back on restores the file exactly");
        check(WowVoiceChat.enable(REG) == null, "nothing to remove without the override");

        // A user-set value in the same key stays when voice chat is turned back on.
        String shared = off.replace(WowVoiceChat.VALUE + "\n", "\"dsound\"=\"native\"\n" + WowVoiceChat.VALUE + "\n");
        String kept = WowVoiceChat.enable(shared);
        check(kept != null && kept.contains(HEADER) && kept.contains("\"dsound\"=\"native\"\n")
                && !kept.contains("vivoxsdk"), "other overrides in the key survive");

        // Wine writes keys case-insensitively and may sort the section between others.
        String middle = REG.replace("[Software\\\\Wine\\\\Drivers]",
                HEADER.toLowerCase() + " 1\n#time=1\n\"vivoxsdk\"=\"native\"\n\n[Software\\\\Wine\\\\Drivers]");
        String fixed = WowVoiceChat.disable(middle, 1794700000000L);
        check(fixed != null && fixed.contains("#time=1\n" + WowVoiceChat.VALUE + "\n\n[Software\\\\Wine\\\\Drivers]"),
                "an existing value in a lower-case key is replaced in place");
        String removed = WowVoiceChat.enable(fixed);
        check(REG.equals(removed), "removing a key between sections leaves its neighbours intact");

        String crlf = REG.replace("\n", "\r\n");
        String crlfOff = WowVoiceChat.disable(crlf, 1794700000000L);
        check(crlfOff != null && crlfOff.endsWith(WowVoiceChat.VALUE + "\r\n") && !crlfOff.replace("\r\n", "").contains("\n"),
                "Windows line endings are kept");
        check(crlf.equals(WowVoiceChat.enable(crlfOff)), "Windows line endings round-trip");

        File prefix = Files.createTempDirectory("wow-voice-chat").toFile();
        File reg = new File(prefix, "user.reg");
        Files.write(reg.toPath(), REG.getBytes(StandardCharsets.UTF_8));
        check(WowVoiceChat.apply(prefix, true), "apply writes the override");
        check(!WowVoiceChat.apply(prefix, true), "apply leaves a disabled prefix alone");
        String written = new String(Files.readAllBytes(reg.toPath()), StandardCharsets.UTF_8);
        check(written.contains(WowVoiceChat.VALUE), "user.reg holds the override");
        check(WowVoiceChat.apply(prefix, false), "apply removes the override");
        check(REG.equals(new String(Files.readAllBytes(reg.toPath()), StandardCharsets.UTF_8)), "user.reg is restored");
        check(!new File(prefix, "user.reg.wehavephones-tmp").exists(), "no temporary file is left");
        check(!WowVoiceChat.apply(new File(prefix, "missing"), true), "a prefix without user.reg is skipped");
        reg.delete();
        prefix.delete();

        if (failures > 0) {
            System.out.println("WowVoiceChatSelfTest FAIL: " + failures + " check(s)");
            System.exit(1);
        }
        System.out.println("WowVoiceChatSelfTest PASS: override added, replaced, removed and round-tripped");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("  FAIL: " + what);
        }
    }
}

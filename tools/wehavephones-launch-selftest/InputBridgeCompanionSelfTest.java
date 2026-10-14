package com.wehavephones.mobile;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Host test for InputBridgeCompanion: the default Input Bridge installer
 * location maps to the container's D: drive, the longest mapping wins, and
 * unmapped or space-containing paths are refused.
 */
public final class InputBridgeCompanionSelfTest {
    private static int failures;

    private static void check(String name, Object expected, Object actual) {
        if (expected == null ? actual == null : expected.equals(actual)) {
            System.out.println("PASS " + name);
        } else {
            System.out.println("FAIL " + name + ": expected <" + expected + "> got <" + actual + ">");
            failures++;
        }
    }

    private static List<String[]> drives(String... pairs) {
        List<String[]> list = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) list.add(new String[]{pairs[i], pairs[i + 1]});
        return list;
    }

    public static void main(String[] args) {
        File download = new File("/storage/emulated/0/Download");
        File exe = InputBridgeCompanion.hostExe(download);
        check("host path", "/storage/emulated/0/Download/InputBridge/Installer/ib.exe", exe.getPath());

        List<String[]> defaults = drives("D", "/storage/emulated/0/Download",
                "E", "/data/data/com.wehavephones/storage");
        check("default D: mapping", "D:\\InputBridge\\Installer\\ib.exe",
                InputBridgeCompanion.guestPath(exe, defaults));

        check("trailing slash on the drive", "D:\\InputBridge\\Installer\\ib.exe",
                InputBridgeCompanion.guestPath(exe, drives("d", "/storage/emulated/0/Download/")));

        check("longest mapping wins", "F:\\ib.exe", InputBridgeCompanion.guestPath(exe,
                drives("E", "/storage/emulated/0", "F", "/storage/emulated/0/Download/InputBridge/Installer")));

        check("no drive maps Download", null, InputBridgeCompanion.guestPath(exe,
                drives("E", "/data/data/com.wehavephones/storage")));

        check("sibling folder is not a prefix match", null, InputBridgeCompanion.guestPath(exe,
                drives("D", "/storage/emulated/0/Down")));

        check("space refused", null, InputBridgeCompanion.guestPath(
                new File("/storage/emulated/0/Download/Input Bridge/ib.exe"), defaults));

        if (failures > 0) {
            System.out.println(failures + " InputBridgeCompanion check(s) failed");
            System.exit(1);
        }
        System.out.println("InputBridgeCompanion self-test passed");
    }
}

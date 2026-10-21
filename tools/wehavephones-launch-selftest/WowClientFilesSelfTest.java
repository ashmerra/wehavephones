package com.wehavephones.mobile;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Host test for WowClientFiles and WowFirstBoot: Config.wtf CVar reading,
 * first-boot seeding and backups, and the Logs/ marker scan.
 */
public final class WowClientFilesSelfTest {
    private static int failures;

    public static void main(String[] args) throws Exception {
        String crlf = "SET plunderstormRealm \"0\"\r\nSET farclip \"1000\"\r\n";
        check("unset CVar is null", WowClientFiles.cvarValue(crlf, "accountName") == null);

        String fresh = WowFirstBoot.configure("", "us", true, "1024x576");
        check("fresh region seeded", "us".equals(WowClientFiles.cvarValue(fresh, "portal")));
        check("fresh language seeded", "enUS".equals(WowClientFiles.cvarValue(fresh, "textLocale")));
        check("fresh audio language seeded", "enUS".equals(WowClientFiles.cvarValue(fresh, "audioLocale")));
        check("fresh photosensitivity skipped", "11".equals(WowClientFiles.cvarValue(fresh, "showPhotosensitivityWarning")));
        check("fresh retail cinematic skipped", "15".equals(WowClientFiles.cvarValue(fresh, "playIntroMovie")));
        check("fresh game uses low resolution", "1024x576".equals(WowClientFiles.cvarValue(fresh, "GxWindowedResolution")));
        check("first boot idempotent", fresh.equals(WowFirstBoot.configure(fresh, "eu", true, "1024x576")));
        String custom = "SET portal \"EU\"\nSET textLocale \"deDE\"\nSET playIntroMovie \"0\"\nSET showPhotosensitivityWarning \"0\"\n";
        String preserved = WowFirstBoot.configure(custom, "us", true, "1024x576");
        check("existing region preserved", "EU".equals(WowClientFiles.cvarValue(preserved, "portal")));
        check("existing language preserved", "deDE".equals(WowClientFiles.cvarValue(preserved, "textLocale")));
        check("existing movie preference preserved", "0".equals(WowClientFiles.cvarValue(preserved, "playIntroMovie")));
        String regionChanged = WowFirstBoot.configure(custom, "tw", true, "1024x576", true);
        check("explicit region change replaces existing portal", "tw".equals(WowClientFiles.cvarValue(regionChanged, "portal")));
        check("region change preserves custom language", "deDE".equals(WowClientFiles.cvarValue(regionChanged, "textLocale")));
        check("region change preserves custom intro", "0".equals(WowClientFiles.cvarValue(regionChanged, "playIntroMovie")));
        String classic = WowFirstBoot.configure("", "eu", false, "1024x576");
        check("classic intro skipped", "14".equals(WowClientFiles.cvarValue(classic, "playIntroMovie")));
        check("classic photosensitivity skipped", "4".equals(WowClientFiles.cvarValue(classic, "showPhotosensitivityWarning")));
        check("unknown region rejected", !WowFirstBoot.validRegion("../invalid"));
        String china = WowFirstBoot.configure("", "cn", true, "1024x576");
        check("China region seeded", "cn".equals(WowClientFiles.cvarValue(china, "portal")));
        check("China language seeded", "zhCN".equals(WowClientFiles.cvarValue(china, "textLocale")));
        check("China audio language seeded", "zhCN".equals(WowClientFiles.cvarValue(china, "audioLocale")));
        check("China keeps a chosen language", "enUS".equals(WowClientFiles.cvarValue(
                WowFirstBoot.configure("SET textLocale \"enUS\"\n", "cn", true, "1024x576"), "textLocale")));
        check("no API flag leaves GxApi alone", WowClientFiles.cvarValue(fresh, "GxApi") == null);
        String played = "SET GxApi \"D3D12\"\r\nSET farclip \"1000\"\r\n";
        String d3d11 = WowFirstBoot.configure(played, "us", true, "1024x576", false, "-d3d11");
        check("D3D11 profile replaces the saved GxApi", "D3D11".equals(WowClientFiles.cvarValue(d3d11, "GxApi")));
        check("GxApi edit keeps other CVars", "1000".equals(WowClientFiles.cvarValue(d3d11, "farclip")));
        check("GxApi edit is idempotent", d3d11.equals(WowFirstBoot.configure(d3d11, "us", true, "1024x576", false, "-d3d11")));
        check("GxApi matches case-insensitively", "d3d11".equals(WowClientFiles.cvarValue(
                WowFirstBoot.configure("SET gxapi \"d3d11\"\n", "us", true, "1024x576", false, "-d3d11"), "GxApi")));
        check("null profile flag keeps the saved GxApi", "D3D12".equals(WowClientFiles.cvarValue(
                WowFirstBoot.configure(played, "us", true, "1024x576", false, null), "GxApi")));
        check("unknown flag does not map to GxApi", WowFirstBoot.gxApi("-dx9") == null);
        check("gamepad UI left alone by default", WowClientFiles.cvarValue(fresh, WowFirstBoot.GAMEPAD_UI_CVAR) == null);
        String gamepad = WowFirstBoot.configure(played, "us", false, "1024x576", false, null, true);
        check("gamepad UI seeded", "1".equals(WowClientFiles.cvarValue(gamepad, WowFirstBoot.GAMEPAD_UI_CVAR)));
        check("gamepad UI seed is idempotent", gamepad.equals(WowFirstBoot.configure(gamepad, "us", false, "1024x576", false, null, true)));
        check("gamepad UI seed replaces mouse UI", "1".equals(WowClientFiles.cvarValue(WowFirstBoot.configure(
                "SET InputDeviceInterfaceStyle \"0\"\n", "us", false, "1024x576", false, null, true), WowFirstBoot.GAMEPAD_UI_CVAR)));

        File dir = Files.createTempDirectory("wow-client-files").toFile();
        try {
            File config = new File(new File(dir, "WTF"), "Config.wtf");
            config.getParentFile().mkdirs();
            Files.write(config.toPath(), "SET accountName \"a@b.c\"\r\n".getBytes(StandardCharsets.UTF_8));

            check("first boot writes a fresh config", WowFirstBoot.prepare(dir, "us", true, "1024x576"));
            File backup = new File(config.getPath() + ".wehavephones-firstboot-backup");
            String original = new String(Files.readAllBytes(backup.toPath()), StandardCharsets.UTF_8);
            check("first boot backup preserves account config", original.equals("SET accountName \"a@b.c\"\r\n"));
            check("first boot file write is idempotent", !WowFirstBoot.prepare(dir, "eu", true, "1024x576"));
            check("original backup remains unchanged", original.equals(new String(Files.readAllBytes(backup.toPath()), StandardCharsets.UTF_8)));
            check("explicit region change writes existing game config", WowFirstBoot.prepare(dir, "eu", true, "1024x576", true));
            check("explicit region change persists", "eu".equals(WowClientFiles.cvarValue(new String(Files.readAllBytes(config.toPath()), StandardCharsets.UTF_8), "portal")));
            check("explicit region change is idempotent", !WowFirstBoot.prepare(dir, "eu", true, "1024x576", true));
            check("region change preserves initial backup", original.equals(new String(Files.readAllBytes(backup.toPath()), StandardCharsets.UTF_8)));

            File marker = new File(dir, WowFirstBoot.GAMEPAD_UI_MARKER);
            check("non-Forever launch leaves no gamepad UI marker", !marker.exists());
            check("Forever launch seeds the gamepad UI", WowFirstBoot.prepare(dir, "eu", false, "1024x576", false, null, true));
            check("gamepad UI persists", "1".equals(WowClientFiles.cvarValue(new String(Files.readAllBytes(config.toPath()), StandardCharsets.UTF_8), WowFirstBoot.GAMEPAD_UI_CVAR)));
            check("gamepad UI marker written", marker.isFile());
            String mouse = new String(Files.readAllBytes(config.toPath()), StandardCharsets.UTF_8).replace(
                    "SET InputDeviceInterfaceStyle \"1\"", "SET InputDeviceInterfaceStyle \"0\"");
            Files.write(config.toPath(), mouse.getBytes(StandardCharsets.UTF_8));
            check("player's mouse UI choice is kept", !WowFirstBoot.prepare(dir, "eu", false, "1024x576", false, null, true));
            Files.write(config.toPath(), mouse.replace("SET InputDeviceInterfaceStyle \"0\"\r\n", "").getBytes(StandardCharsets.UTF_8));
            check("dropped gamepad UI CVar is not re-seeded", !WowFirstBoot.prepare(dir, "eu", false, "1024x576", false, null, true));

            File gx = new File(dir, "gx.log");
            Files.write(gx.toPath(), "10/4 08:15:09.453  ---- Startup ----\n".getBytes(StandardCharsets.UTF_8));
            check("no marker before the device", !WowClientFiles.fileContains(gx, WowClientFiles.DEVICE_READY_MARKER, 1024));
            Files.write(gx.toPath(), "10/4 08:15:12.790  Device Create Successful\n".getBytes(StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.APPEND);
            check("marker after the device", WowClientFiles.fileContains(gx, WowClientFiles.DEVICE_READY_MARKER, 1024));
            check("scan limit respected", !WowClientFiles.fileContains(gx, WowClientFiles.DEVICE_READY_MARKER, 40));
            check("missing file", !WowClientFiles.fileContains(new File(dir, "missing.log"), WowClientFiles.DEVICE_READY_MARKER, 1024));
        }
        finally {
            for (File file : new File[]{new File(dir, "WTF/Config.wtf"), new File(dir, "WTF/Config.wtf.wehavephones-firstboot-backup"), new File(dir, WowFirstBoot.GAMEPAD_UI_MARKER), new File(dir, "WTF"), new File(dir, "gx.log"), dir}) file.delete();
        }

        System.out.println("[wow-client-files-selftest] " + failures + " failure(s)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void check(String name, boolean ok) {
        if (!ok) failures++;
        System.out.println((ok ? "  ok   " : "  FAIL ") + name);
    }
}

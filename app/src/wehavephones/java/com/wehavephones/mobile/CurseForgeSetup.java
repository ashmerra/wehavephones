package com.wehavephones.mobile;

import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.util.Locale;

/**
 * Pure parts of running CurseForge's own Windows app in the container: its
 * release feed and the batch file that installs and starts it. Kept free of
 * Android classes for the host self-test.
 */
public final class CurseForgeSetup {
    /**
     * The update feed of the standalone CurseForge app (electron-updater
     * {@code latest.yml}). Its official web installer is a .NET bootstrapper
     * that needs Wine Mono and WebView2, so the app package is fetched from
     * the same source the app updates itself from.
     */
    public static final String FEED_URL =
            "https://electron-updates.overwolf.com/electron-updates/electron/cfiahnpaolfnlgaihhmobmnjdafknjnjdpdabpcm/latest.yml";
    public static final String INSTALLER_NAME = "CurseForge-Setup.exe";
    public static final String VERSION_FILE = "installed-version.txt";
    /** Staging directory inside drive C for the installer, DLLs and version marker. */
    public static final String STAGE_DIR = "windows/wehavephones-curseforge";
    public static final String STAGE_GUEST = "C:\\windows\\wehavephones-curseforge";
    /** Install directory below each user's {@code AppData/Local}. */
    public static final String APP_DIR = "Programs/CurseForge";
    public static final String APP_EXE = "CurseForge.exe";
    /**
     * The container's DirectX DLLs are ARM64-only builds for the native ARM64
     * game, which the x64 app cannot load. Wine's own ARM64X builds next to
     * the app take precedence and let Chromium start.
     */
    public static final String[] HYBRID_DLLS = {"dxgi.dll", "d3d11.dll"};

    public static final class Release {
        public final String version;
        public final String url;
        public final String sha512;
        public final long size;

        Release(String version, String url, String sha512, long size) {
            this.version = version;
            this.url = url;
            this.sha512 = sha512;
            this.size = size;
        }
    }

    private CurseForgeSetup() {}

    /** The first file of an electron-updater {@code latest.yml}, or null if anything is missing or unsafe. */
    public static Release parseFeed(String yml) {
        if (yml == null) return null;
        String version = null;
        String url = null;
        String sha512 = null;
        long size = -1;
        boolean inFiles = false;
        boolean firstFile = false;
        boolean doneFirst = false;
        for (String raw : yml.split("\r?\n")) {
            if (raw.trim().isEmpty()) continue;
            boolean indented = raw.startsWith(" ") || raw.startsWith("\t");
            String line = raw.trim();
            if (!indented) {
                inFiles = line.equals("files:");
                if (line.startsWith("version:")) version = value(line);
                continue;
            }
            if (!inFiles || doneFirst) continue;
            if (line.startsWith("- ")) {
                if (firstFile) {
                    doneFirst = true;
                    continue;
                }
                firstFile = true;
                line = line.substring(2).trim();
            }
            if (!firstFile) continue;
            if (line.startsWith("url:")) url = value(line);
            else if (line.startsWith("sha512:")) sha512 = value(line);
            else if (line.startsWith("size:")) {
                try {
                    size = Long.parseLong(value(line));
                }
                catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        if (version == null || !version.matches("[0-9A-Za-z.+-]{1,40}")) return null;
        if (sha512 == null || !sha512.matches("[A-Za-z0-9+/]{86}==")) return null;
        if (size <= 0 || !isTrustedUrl(url)) return null;
        return new Release(version, url, sha512, size);
    }

    private static String value(String line) {
        String value = line.substring(line.indexOf(':') + 1).trim();
        if (value.length() >= 2 && (value.startsWith("'") && value.endsWith("'")
                || value.startsWith("\"") && value.endsWith("\""))) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }

    /** Only HTTPS downloads from Overwolf's servers, which host CurseForge. */
    static boolean isTrustedUrl(String url) {
        if (url == null) return false;
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            return "https".equals(uri.getScheme()) && host != null
                    && (host.equals("overwolf.com") || host.toLowerCase(Locale.ROOT).endsWith(".overwolf.com"));
        }
        catch (Exception e) {
            return false;
        }
    }

    /** CurseForge's settings file below each user's {@code AppData/Roaming}. */
    public static final String SETTINGS_FILE = "CurseForge/storage.json";
    /** {@code closeCurseForgeAction} value of "Exit CurseForge" (the default hides it). */
    static final int CLOSE_ACTION_EXIT = 1;

    /**
     * CurseForge's {@code storage.json} with closing set to exit and starting
     * with Windows off. Hidden in the background it would keep running, and
     * changing AddOns, after the player goes back to play. Unknown settings
     * are kept, and an unreadable file is replaced.
     */
    public static String configureSettings(String storage) {
        JSONObject root;
        try {
            root = storage != null && !storage.trim().isEmpty() ? new JSONObject(storage) : new JSONObject();
        }
        catch (JSONException e) {
            root = new JSONObject();
        }
        try {
            // electron-store keeps each section as a JSON string.
            JSONObject general;
            try {
                general = new JSONObject(root.optString("general-settings", "{}"));
            }
            catch (JSONException e) {
                general = new JSONObject();
            }
            general.put("closeCurseForgeAction", CLOSE_ACTION_EXIT);
            general.put("runOnStartup", false);
            root.put("general-settings", general.toString());
            return root.toString();
        }
        catch (JSONException e) {
            return storage;
        }
    }

    /** Update only a release this app installed, so a CurseForge self-update is never undone. */
    public static boolean needsUpdate(String installedVersion, Release latest) {
        return installedVersion != null && !installedVersion.trim().isEmpty() && latest != null
                && !installedVersion.trim().equals(latest.version);
    }

    /**
     * Installs a staged release when one is present, then starts the app.
     * {@code version} names the staged release and may be null.
     */
    public static String bat(String version) {
        String ps64 = "C:\\windows\\system32\\WindowsPowerShell\\v1.0\\powershell.exe";
        String ps32 = "C:\\windows\\syswow64\\WindowsPowerShell\\v1.0\\powershell.exe";
        StringBuilder bat = new StringBuilder();
        bat.append("@echo off\r\n")
                .append("set \"STAGE=").append(STAGE_GUEST).append("\"\r\n")
                .append("set \"APP=%LOCALAPPDATA%\\").append(APP_DIR.replace('/', '\\')).append("\"\r\n")
                .append("set \"PS64=").append(ps64).append("\"\r\n")
                .append("set \"PS32=").append(ps32).append("\"\r\n")
                // Restore PowerShell if an earlier install was interrupted.
                .append("if exist \"%PS64%.wehavephones\" move /y \"%PS64%.wehavephones\" \"%PS64%\" >NUL\r\n")
                .append("if exist \"%PS32%.wehavephones\" move /y \"%PS32%.wehavephones\" \"%PS32%\" >NUL\r\n")
                .append("if not exist \"%STAGE%\\").append(INSTALLER_NAME).append("\" goto launch\r\n")
                // The installer asks PowerShell whether CurseForge runs, and Wine's
                // stub always answers yes. Without it the installer uses tasklist.
                .append("move /y \"%PS64%\" \"%PS64%.wehavephones\" >NUL\r\n")
                .append("move /y \"%PS32%\" \"%PS32%.wehavephones\" >NUL\r\n")
                .append("\"%STAGE%\\").append(INSTALLER_NAME).append("\" /S\r\n")
                .append("set RESULT=%errorlevel%\r\n")
                .append("move /y \"%PS64%.wehavephones\" \"%PS64%\" >NUL\r\n")
                .append("move /y \"%PS32%.wehavephones\" \"%PS32%\" >NUL\r\n")
                .append("del /q \"%STAGE%\\").append(INSTALLER_NAME).append("\"\r\n")
                .append("if not \"%RESULT%\"==\"0\" exit /b %RESULT%\r\n");
        if (version != null) {
            bat.append(">\"%STAGE%\\").append(VERSION_FILE).append("\" echo ").append(version).append("\r\n");
        }
        bat.append(":launch\r\n")
                .append("if not exist \"%APP%\\").append(APP_EXE).append("\" exit /b 1\r\n")
                // CurseForge adds itself to the user's startup programs on first run.
                .append("reg.exe delete \"HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run\"")
                .append(" /v com.overwolf.curseforge /f >NUL 2>&1\r\n");
        for (String dll : HYBRID_DLLS) {
            bat.append("copy /y \"%STAGE%\\").append(dll).append("\" \"%APP%\\").append(dll).append("\" >NUL\r\n");
        }
        // .NET's write-xor-execute double mapping crashes the mods agent under emulation.
        bat.append("set DOTNET_EnableWriteXorExecute=0\r\n")
                // Chromium's sandbox and GPU process do not start under this runtime.
                .append("\"%APP%\\").append(APP_EXE).append("\" --no-sandbox --disable-gpu\r\n");
        return bat.toString();
    }
}

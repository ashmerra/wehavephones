package com.wehavephones.mobile;

import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.util.Locale;

/**
 * Windows addon managers that run in the game's container: their release
 * feeds and the batch file that installs and starts them. Both are Electron
 * apps packaged by electron-builder, so one install and start path serves
 * both. Kept free of Android classes for the host self-test.
 */
public enum AddonApp {
    /**
     * CurseForge's standalone app reaches the whole CurseForge catalog without
     * an API key. Its official web installer is a .NET bootstrapper that needs
     * Wine Mono and WebView2, so the full installer comes from the app's own
     * update feed.
     */
    CURSEFORGE("curseforge", "CurseForge",
            "https://electron-updates.overwolf.com/electron-updates/electron/cfiahnpaolfnlgaihhmobmnjdafknjnjdpdabpcm/latest.yml",
            null, "overwolf.com", "CurseForge", "CurseForge.exe", "CurseForge/storage.json", "com.overwolf.curseforge", true,
            null),
    /** WowUp (its Wago edition) adds Wago, WoWInterface, Tukui and GitHub addons without keys. */
    WOWUP("wowup", "WowUp",
            "https://github.com/WowUp/WowUp/releases/latest/download/latest.yml",
            "https://github.com/WowUp/WowUp/releases/download/v%s/", "github.com", "wowup", "WowUp.exe",
            "WowUp/preferences.json", null, false, "container-fixes.js");

    public static final String VERSION_FILE = "installed-version.txt";
    /** Desktop size for the apps, more room than the low game default. */
    public static final String DESKTOP_SIZE = "1280x800";
    /**
     * Node script that adapts WowUp to the container, run with WowUp's own
     * Electron before each start. It makes three same-length edits to
     * app.asar, so the archive index stays valid (WowUp does not check the
     * archive's integrity):
     * <ul>
     * <li>No tray icon. WowUp creates one at every start, and updating it hangs
     * this Wine build's taskbar, so the window would never appear.</li>
     * <li>ARM64 game names. The x64 app looks for {@code WowClassic.exe}, but the
     * container runs Blizzard's ARM64 builds.</li>
     * <li>Drive C for Battle.net's product list. Its disk listing finds no
     * drives under Wine.</li>
     * </ul>
     * WowUp is GPL-3.0, and the changes stay on the player's device.
     */
    static final String WOWUP_CONTAINER_FIXES_JS =
            "process.noAsar = true;\n"
            + "const fs = require(\"fs\");\n"
            + "const file = process.argv[2];\n"
            + "const data = fs.readFileSync(file);\n"
            + "// [anchor, old, new]: the first old code shortly after anchor becomes new,\n"
            + "// padded with spaces to the same length.\n"
            + "const patches = [\n"
            + "    [\"function createTray(window, config) {\",\n"
            + "        \"_trayRef === null || _trayRef === void 0 ? void 0 : _trayRef.destroy();\", \"return false;\"],\n"
            + "    [\"class WarcraftPlatformWin {\", \"process.arch === \\\"arm64\\\"\", \"true\"],\n"
            + "    [\"getBlizzardAgentPath() {\", \"nodeDiskInfo.getDiskInfo()\", \"[{ mounted: \\\"C:\\\" }]\"],\n"
            + "];\n"
            + "let changed = false;\n"
            + "for (const [anchor, from, to] of patches) {\n"
            + "    for (let start = data.indexOf(anchor); start >= 0; start = data.indexOf(anchor, start + 1)) {\n"
            + "        const at = data.indexOf(from, start);\n"
            + "        if (at < 0 || at - start > 400) continue;\n"
            + "        const code = Buffer.alloc(Buffer.byteLength(from), \" \");\n"
            + "        code.write(to);\n"
            + "        code.copy(data, at);\n"
            + "        changed = true;\n"
            + "        break;\n"
            + "    }\n"
            + "}\n"
            + "if (changed) fs.writeFileSync(file, data);\n";
    /**
     * The container's DirectX DLLs are ARM64-only builds for the native ARM64
     * game, which these x64 apps cannot load. Wine's own ARM64X builds next to
     * the app take precedence and let Chromium start.
     */
    public static final String[] HYBRID_DLLS = {"dxgi.dll", "d3d11.dll"};
    /** {@code closeCurseForgeAction} value of "Exit CurseForge" (the default hides it). */
    static final int CURSEFORGE_CLOSE_EXIT = 1;

    public final String id;
    public final String displayName;
    public final String feedUrl;
    /** Base for a feed's relative file names, formatted with the version, or null for absolute URLs. */
    final String downloadBase;
    /** Downloads must use HTTPS from this host or its subdomains. */
    final String trustedHost;
    /** The installer's directory name below {@code AppData/Local/Programs}, as it appears on disk. */
    final String installDir;
    public final String exe;
    /** The app's settings file below each user's {@code AppData/Roaming}. */
    public final String settingsFile;
    /** The value the app adds under the user's {@code Run} key, or null. */
    final String startupValue;
    /** The app runs a .NET helper, which needs write-xor-execute off under emulation. */
    final boolean dotnetHelper;
    /** File name of a Node script run with the app's own Electron before each start, or null. */
    public final String patchScript;

    AddonApp(String id, String displayName, String feedUrl, String downloadBase, String trustedHost,
            String installDir, String exe, String settingsFile, String startupValue, boolean dotnetHelper,
            String patchScript) {
        this.id = id;
        this.displayName = displayName;
        this.feedUrl = feedUrl;
        this.downloadBase = downloadBase;
        this.trustedHost = trustedHost;
        this.installDir = installDir;
        this.exe = exe;
        this.settingsFile = settingsFile;
        this.startupValue = startupValue;
        this.dotnetHelper = dotnetHelper;
        this.patchScript = patchScript;
    }

    public String installerName() {
        return displayName + "-Setup.exe";
    }

    /** Staging directory inside drive C for the installer, DLLs and version marker. */
    public String stageDir() {
        return "windows/wehavephones-" + id;
    }

    String stageGuest() {
        return "C:\\windows\\wehavephones-" + id;
    }

    /** Install directory below each user's {@code AppData/Local}. */
    public String appDir() {
        return "Programs/" + installDir;
    }

    public String batName() {
        return "wehavephones-" + id + ".bat";
    }

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

    /** The first file of an electron-updater {@code latest.yml}, or null if anything is missing or unsafe. */
    public Release parseFeed(String yml) {
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
        if (url != null && downloadBase != null && !url.contains("://")) {
            // A release asset name, pinned to the release's tag.
            if (!url.matches("[A-Za-z0-9._+-]{1,120}")) return null;
            url = String.format(Locale.ROOT, downloadBase, version) + url;
        }
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

    boolean isTrustedUrl(String url) {
        if (url == null) return false;
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            return "https".equals(uri.getScheme()) && host != null
                    && (host.equals(trustedHost) || host.toLowerCase(Locale.ROOT).endsWith("." + trustedHost));
        }
        catch (Exception e) {
            return false;
        }
    }

    /** Update only a release this app installed, so an app's own update is never undone. */
    public static boolean needsUpdate(String installedVersion, Release latest) {
        return installedVersion != null && !installedVersion.trim().isEmpty() && latest != null
                && !installedVersion.trim().equals(latest.version);
    }

    /**
     * The app's settings with closing set to exit, starting with Windows off
     * and hardware acceleration off. Hidden in the background an app would
     * keep running, and changing AddOns, after the player goes back to play.
     * Unknown settings are kept, and an unreadable file is replaced.
     */
    public String configureSettings(String settings) {
        JSONObject root;
        try {
            root = settings != null && !settings.trim().isEmpty() ? new JSONObject(settings) : new JSONObject();
        }
        catch (JSONException e) {
            root = new JSONObject();
        }
        try {
            if (this == CURSEFORGE) {
                // electron-store keeps each section as a JSON string.
                JSONObject general;
                try {
                    general = new JSONObject(root.optString("general-settings", "{}"));
                }
                catch (JSONException e) {
                    general = new JSONObject();
                }
                general.put("closeCurseForgeAction", CURSEFORGE_CLOSE_EXIT);
                general.put("runOnStartup", false);
                root.put("general-settings", general.toString());
            }
            else {
                // WowUp keeps every preference as a string.
                root.put("collapse_to_tray", "false");
                root.put("use_hardware_acceleration", "false");
            }
            return root.toString();
        }
        catch (JSONException e) {
            return settings;
        }
    }

    /**
     * Installs a staged release when one is present, then starts the app.
     * {@code version} names the staged release and may be null.
     */
    public String bat(String version) {
        String ps64 = "C:\\windows\\system32\\WindowsPowerShell\\v1.0\\powershell.exe";
        String ps32 = "C:\\windows\\syswow64\\WindowsPowerShell\\v1.0\\powershell.exe";
        String installer = installerName();
        StringBuilder bat = new StringBuilder();
        bat.append("@echo off\r\n")
                .append("set \"STAGE=").append(stageGuest()).append("\"\r\n")
                .append("set \"APP=%LOCALAPPDATA%\\").append(appDir().replace('/', '\\')).append("\"\r\n")
                .append("set \"PS64=").append(ps64).append("\"\r\n")
                .append("set \"PS32=").append(ps32).append("\"\r\n")
                // Restore PowerShell if an earlier install was interrupted.
                .append("if exist \"%PS64%.wehavephones\" move /y \"%PS64%.wehavephones\" \"%PS64%\" >NUL\r\n")
                .append("if exist \"%PS32%.wehavephones\" move /y \"%PS32%.wehavephones\" \"%PS32%\" >NUL\r\n")
                .append("if not exist \"%STAGE%\\").append(installer).append("\" goto launch\r\n")
                // The installer asks PowerShell whether the app runs, and Wine's
                // stub always answers yes. Without it the installer uses tasklist.
                .append("move /y \"%PS64%\" \"%PS64%.wehavephones\" >NUL\r\n")
                .append("move /y \"%PS32%\" \"%PS32%.wehavephones\" >NUL\r\n")
                .append("\"%STAGE%\\").append(installer).append("\" /S\r\n")
                .append("set RESULT=%errorlevel%\r\n")
                .append("move /y \"%PS64%.wehavephones\" \"%PS64%\" >NUL\r\n")
                .append("move /y \"%PS32%.wehavephones\" \"%PS32%\" >NUL\r\n")
                .append("del /q \"%STAGE%\\").append(installer).append("\"\r\n")
                .append("if not \"%RESULT%\"==\"0\" exit /b %RESULT%\r\n");
        if (version != null) {
            bat.append(">\"%STAGE%\\").append(VERSION_FILE).append("\" echo ").append(version).append("\r\n");
        }
        bat.append(":launch\r\n")
                .append("if not exist \"%APP%\\").append(exe).append("\" exit /b 1\r\n");
        if (startupValue != null) {
            // The app adds itself to the user's startup programs on first run.
            bat.append("reg.exe delete \"HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run\"")
                    .append(" /v ").append(startupValue).append(" /f >NUL 2>&1\r\n");
        }
        for (String dll : HYBRID_DLLS) {
            bat.append("copy /y \"%STAGE%\\").append(dll).append("\" \"%APP%\\").append(dll).append("\" >NUL\r\n");
        }
        if (dotnetHelper) {
            // .NET's write-xor-execute double mapping crashes the helper under emulation.
            bat.append("set DOTNET_EnableWriteXorExecute=0\r\n");
        }
        if (patchScript != null) {
            // The app's Electron runs the script as Node (its RunAsNode fuse is on).
            bat.append("set ELECTRON_RUN_AS_NODE=1\r\n")
                    .append("\"%APP%\\").append(exe).append("\" \"%STAGE%\\").append(patchScript)
                    .append("\" \"%APP%\\resources\\app.asar\"\r\n")
                    .append("set ELECTRON_RUN_AS_NODE=\r\n");
        }
        // Chromium's sandbox and GPU process do not start under this runtime.
        bat.append("\"%APP%\\").append(exe).append("\" --no-sandbox --disable-gpu\r\n");
        return bat.toString();
    }
}

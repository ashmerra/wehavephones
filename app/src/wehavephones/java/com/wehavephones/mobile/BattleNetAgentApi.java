package com.wehavephones.mobile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Requests to and answers from Blizzard's Agent, the service inside the
 * Battle.net install that downloads, updates and removes games. Battle.net
 * itself drives it over HTTP on 127.0.0.1 (the port is in
 * {@code C:\ProgramData\Battle.net\Agent\Agent.dat}). The request bodies
 * follow Blizzard's own client and the community documentation at
 * wowdev.wiki/Agent, as used by barncastle's Battle.Net-Installer and
 * DroidDeck. Pure Java plus org.json, host-tested (BattleNetAgentSelfTest).
 *
 * <p>The Agent only answers programs it can find in the Windows TCP table,
 * which Wine builds from its own sockets, so the app reaches it through
 * {@code agentpipe.exe} in the container (see {@link BattleNetAgentClient}).
 */
public final class BattleNetAgentApi {
    public static final String USER_AGENT = "phoenix-agent/1.0";
    /** Agent.dat holds the port of the running Agent. */
    public static final String AGENT_DAT = "Agent.dat";
    public static final int PRIORITY = 900;

    /** Agent job states seen on device (wowdev.wiki lists them without names). */
    public static final int STATE_WORKING = 1003;
    public static final int STATE_DONE = 1004;
    public static final int STATE_QUEUED = 1008;
    public static final int STATE_PREPARING = 1000;
    public static final int STATE_FAILED = 1200;
    /** extended_status.state: bytes downloaded and written, then patches applied (a count). */
    public static final int PHASE_DOWNLOAD = 500;
    public static final int PHASE_APPLY = 2000;
    /** extended_status.state after the patches: the written files are checked (bytes). */
    public static final int PHASE_FINISH = 1000;
    /** "Failed to initialize the tact instance": a retry usually goes through. */
    public static final int ERROR_TACT_INIT = 5016;

    private BattleNetAgentApi() {}

    // ---- request bodies -----------------------------------------------------------------

    /** CDN region the Agent downloads from; China has its own hosts. */
    public static String patchHost(String region) {
        String zone = region == null ? "us" : region.trim().toLowerCase(Locale.ROOT);
        if (!zone.matches("[a-z]{2}")) zone = "us";
        return "cn".equals(zone) ? "cn.patch.battlenet.com.cn" : zone + ".patch.battle.net";
    }

    /** {@code POST /install}: registers the product (no download yet). */
    public static JSONObject installRequest(String uid, String productCode, String region) throws JSONException {
        return new JSONObject()
                .put("uid", uid)
                .put("instructions_patch_url", "http://" + patchHost(region) + ":1119/" + productCode)
                .put("instructions_product", "NGDP")
                .put("monitor_pid", 12345)
                .put("priority", priority());
    }

    /** {@code POST /install/<uid>}: the confirmed install form; the download starts. */
    public static JSONObject installForm(String gameDir, String locale, String accountCountry, String geoCountry) throws JSONException {
        String lang = agentLocale(locale);
        return new JSONObject()
                .put("account_country", accountCountry)
                .put("finalized", true)
                .put("game_dir", gameDir)
                .put("geo_ip_country", geoCountry)
                .put("language", new JSONArray().put(lang))
                .put("selected_asset_locale", lang)
                .put("selected_locale", lang)
                .put("shortcut", "none")
                .put("tome_torrent", "");
    }

    /** {@code POST /update}: patch an installed (or registered but incomplete) product. */
    public static JSONObject updateRequest(String uid) throws JSONException {
        return new JSONObject().put("uid", uid).put("priority", priority());
    }

    /** {@code POST /repair}: check every file and fetch what is missing or damaged. */
    public static JSONObject repairRequest(String uid) throws JSONException {
        return new JSONObject().put("uid", uid).put("priority", priority());
    }

    /**
     * {@code POST /uninstall}. Compaction rewrites the shared game data without
     * this game's files, which frees its space but takes a while for the WoW
     * games that share one {@code Data} folder.
     */
    public static JSONObject uninstallRequest(String uid, boolean compact) throws JSONException {
        return new JSONObject().put("uid", uid).put("run_compaction", compact);
    }

    private static JSONObject priority() throws JSONException {
        return new JSONObject().put("insert_at_head", true).put("value", PRIORITY);
    }

    /** A language tag as Battle.net writes it ("enUS"); anything else is "enUS". */
    public static String agentLocale(String locale) {
        String value = locale == null ? "" : locale.trim();
        if (!value.matches("[a-zA-Z]{4}")) return "enUS";
        return value.substring(0, 2).toLowerCase(Locale.ROOT) + value.substring(2).toUpperCase(Locale.ROOT);
    }

    public static final String PIPE_NAME = "agentpipe.exe";

    /**
     * The batch file that starts the Agent on its own (it then serves
     * programs in the container, not only Battle.net) and the pipe to it.
     */
    public static String agentBat(String agentExe, int pipePort, String secret, int idleSeconds) {
        return "@echo off\r\n"
                + "start \"\" \"" + agentExe + "\" --internalclienttools\r\n"
                + "start \"\" \"C:\\windows\\" + PIPE_NAME + "\" " + pipePort + " " + secret + " " + idleSeconds + "\r\n";
    }

    // ---- HTTP framing -------------------------------------------------------------------

    /** One HTTP/1.1 request; the Agent refuses a Host header without its own port. */
    public static byte[] httpRequest(String method, String path, int agentPort, String token, String body) {
        byte[] content = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        StringBuilder head = new StringBuilder()
                .append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                .append("Host: 127.0.0.1:").append(agentPort).append("\r\n")
                .append("User-Agent: ").append(USER_AGENT).append("\r\n")
                .append("Connection: close\r\n")
                .append("Content-Length: ").append(content.length).append("\r\n");
        if (token != null && !token.isEmpty()) head.append("Authorization: ").append(token).append("\r\n");
        if (content.length > 0) head.append("Content-Type: application/json\r\n");
        head.append("\r\n");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] headBytes = head.toString().getBytes(StandardCharsets.US_ASCII);
        out.write(headBytes, 0, headBytes.length);
        out.write(content, 0, content.length);
        return out.toByteArray();
    }

    public static final class Response {
        public final int status;
        public final String body;

        public Response(int status, String body) {
            this.status = status;
            this.body = body;
        }

        public boolean ok() {
            return status >= 200 && status < 300;
        }

        /** The body as JSON, or null when it is empty or not an object. */
        public JSONObject json() {
            if (body == null || body.trim().isEmpty()) return null;
            try {
                return new JSONObject(body);
            }
            catch (JSONException e) {
                return null;
            }
        }

        @Override
        public String toString() {
            return "HTTP " + status;
        }
    }

    /** Parses a complete response read until the Agent closed the connection. */
    public static Response parseResponse(byte[] data) {
        int split = indexOf(data, new byte[] {'\r', '\n', '\r', '\n'});
        if (split < 0) return new Response(0, "");
        String head = new String(data, 0, split, StandardCharsets.ISO_8859_1);
        String statusLine = head.split("\r\n", 2)[0];
        int status = 0;
        String[] parts = statusLine.split(" ");
        if (parts.length >= 2 && parts[0].startsWith("HTTP/")) {
            try {
                status = Integer.parseInt(parts[1]);
            }
            catch (NumberFormatException ignored) {}
        }
        int start = split + 4;
        int length = data.length - start;
        for (String line : head.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                try {
                    length = Math.min(length, Integer.parseInt(line.substring(colon + 1).trim()));
                }
                catch (NumberFormatException ignored) {}
            }
        }
        return new Response(status, new String(data, start, Math.max(0, length), StandardCharsets.UTF_8));
    }

    private static int indexOf(byte[] data, byte[] pattern) {
        outer:
        for (int i = 0; i + pattern.length <= data.length; i++) {
            for (int j = 0; j < pattern.length; j++) if (data[i + j] != pattern[j]) continue outer;
            return i;
        }
        return -1;
    }

    /** The Agent's port from Agent.dat, or -1. */
    public static int parsePort(String agentDat) {
        if (agentDat == null) return -1;
        String text = agentDat.trim();
        if (!text.matches("\\d{2,5}")) return -1;
        int port = Integer.parseInt(text);
        return port > 0 && port < 65536 ? port : -1;
    }

    // ---- answers ------------------------------------------------------------------------

    /** What an install, update or repair job reports ({@code GET /install|update|repair/<uid>}). */
    public static final class Progress {
        public int state;
        public int error;
        public String errorMessage = "";
        public double progress;
        public double playableProgress;
        public long downloadCurrent;
        public long downloadTotal;
        public long downloadRate;
        public long downloadRemaining;
        /** Seconds, or -1 when the Agent gave no usable estimate. */
        public long etaSeconds = -1;
        public int phase;
        public String unitType = "";
        public long phaseCurrent;
        public long phaseTotal;
        public boolean downloadComplete;
        public boolean installed;
        public boolean playable;
        public boolean paused;

        public boolean failed() {
            return error > 0 || state == STATE_FAILED;
        }

        /** Finished: the Agent says done, or everything is down and the game plays. */
        public boolean done() {
            return !failed() && (state == STATE_DONE || downloadComplete && playable);
        }

        /** Work has visibly begun (used to tell a finished job from one that never started). */
        public boolean started() {
            return state == STATE_WORKING || state == STATE_PREPARING || downloadTotal > 0 || phase != 0;
        }

        public Step step() {
            if (failed()) return Step.FAILED;
            if (done()) return Step.DONE;
            if (phase == PHASE_APPLY) return Step.APPLYING;
            if (phase == PHASE_FINISH && (downloadTotal > 0 || progress >= 1.0)) return Step.FINISHING;
            if (downloadTotal > 0 && downloadCurrent >= downloadTotal) return Step.WRITING;
            if (downloadTotal > 0) return Step.DOWNLOADING;
            // Check files: no download, extended_status counts the bytes verified.
            if (phase == 0 && phaseTotal > 0 && state == STATE_WORKING) return Step.CHECKING;
            if (state == STATE_QUEUED) return Step.QUEUED;
            return Step.PREPARING;
        }

        /** extended_status as a fraction (bytes written or patches applied), or -1. */
        public double phaseFraction() {
            return phaseTotal > 0 ? Math.min(1.0, (double)phaseCurrent / phaseTotal) : -1;
        }

        /** 0..1 within the current step: bytes downloaded, then written, applied and checked. */
        public double fraction() {
            switch (step()) {
                case DONE: return 1.0;
                case DOWNLOADING:
                    return downloadTotal > 0 ? Math.min(1.0, (double)downloadCurrent / downloadTotal) : clamp(progress);
                case CHECKING:
                    return Math.max(clamp(progress), Math.max(0.0, phaseFraction()));
                case WRITING:
                case APPLYING:
                case FINISHING:
                    return Math.max(0.0, phaseFraction());
                default: return 0.0;
            }
        }

        /** 0..1 across writing, applying and finishing, for the bar's Install segment. */
        public double installFraction() {
            switch (step()) {
                case WRITING: return fraction() / 3.0;
                case APPLYING: return (1.0 + fraction()) / 3.0;
                case FINISHING: return (2.0 + fraction()) / 3.0;
                case DONE: return 1.0;
                default: return 0.0;
            }
        }

        /** A short machine-readable summary for logs. */
        public String describe() {
            return String.format(Locale.US, "state=%d phase=%d err=%d %d/%d rate=%d eta=%d done=%b playable=%b",
                    state, phase, error, downloadCurrent, downloadTotal, downloadRate, etaSeconds, downloadComplete, playable);
        }
    }

    public enum Step { QUEUED, PREPARING, CHECKING, DOWNLOADING, WRITING, APPLYING, FINISHING, DONE, FAILED }

    public static Progress parseProgress(JSONObject json) {
        Progress p = new Progress();
        if (json == null) return p;
        p.state = json.optInt("state", 0);
        p.error = json.optInt("error", 0);
        p.errorMessage = json.optString("error_message", "");
        if (p.errorMessage.isEmpty()) {
            JSONObject details = json.optJSONObject("error_details");
            if (details != null) p.errorMessage = details.optString("message", "");
        }
        p.progress = clamp(json.optDouble("progress", 0.0));
        p.playableProgress = clamp(json.optDouble("playable_progress", 0.0));
        p.downloadCurrent = firstLong(json, "download_current");
        p.downloadTotal = firstLong(json, "download_total");
        p.downloadRate = Math.max(0, json.optLong("download_rate", 0));
        p.downloadRemaining = Math.max(0, json.optLong("download_remaining", 0));
        long eta = json.optLong("estimated_time_in_sec", -1);
        p.etaSeconds = eta > 0 && eta < 30L * 86400L ? eta : -1;
        JSONObject ext = json.optJSONObject("extended_status");
        if (ext != null) {
            p.phase = ext.optInt("state", 0);
            p.unitType = ext.optString("unit_type", "");
            p.phaseCurrent = Math.max(0, ext.optLong("current", 0));
            p.phaseTotal = Math.max(0, ext.optLong("total", 0));
        }
        p.downloadComplete = json.optBoolean("download_complete", false);
        p.installed = json.optBoolean("installed", false);
        p.playable = json.optBoolean("playable", false);
        p.paused = json.optBoolean("paused", false);
        if (p.etaSeconds < 0 && p.downloadRate > 0 && p.downloadRemaining > 0) {
            p.etaSeconds = p.downloadRemaining / p.downloadRate;
        }
        return p;
    }

    /** Arrays like {@code "download_current": [415514, 0, 0]} carry the total first. */
    private static long firstLong(JSONObject json, String key) {
        JSONArray array = json.optJSONArray(key);
        if (array != null) return array.length() > 0 ? Math.max(0, array.optLong(0, 0)) : 0;
        return Math.max(0, json.optLong(key, 0));
    }

    private static double clamp(double value) {
        if (Double.isNaN(value)) return 0.0;
        return Math.max(0.0, Math.min(1.0, value));
    }

    /** {@code GET /game/<uid>}: one product as the Agent knows it. */
    public static final class GameState {
        public String uid = "";
        public boolean installed;
        public boolean playable;
        public String localVersion = "";
        public String installDir = "";
        public String subpath = "";
        public String region = "";
        public String selectedLocale = "";
        public final List<String> textLanguages = new ArrayList<>();
        public final List<String> speechLanguages = new ArrayList<>();
        public final List<String> supportedLanguages = new ArrayList<>();
        /** The version Blizzard serves for the selected region ("" when unknown). */
        public String latestVersion = "";

        public boolean updateAvailable() {
            return installed && !latestVersion.isEmpty() && !localVersion.isEmpty() && !latestVersion.equals(localVersion);
        }
    }

    public static GameState parseGame(String uid, JSONObject json) {
        GameState g = new GameState();
        g.uid = uid == null ? "" : uid;
        if (json == null) return g;
        g.installed = json.optBoolean("installed", false);
        g.playable = json.optBoolean("playable", false);
        g.localVersion = json.optString("local_version", "");
        g.installDir = json.optString("install_dir", "");
        g.region = json.optString("region", "");
        g.selectedLocale = json.optString("selected_locale", "");
        JSONObject shared = json.optJSONObject("shared_container_info");
        if (shared != null) g.subpath = shared.optString("subpath", "");
        strings(json.optJSONArray("text_languages"), g.textLanguages);
        strings(json.optJSONArray("speech_languages"), g.speechLanguages);
        strings(json.optJSONArray("supported_text_languages"), g.supportedLanguages);
        JSONObject regional = json.optJSONObject("regional_version_info");
        if (regional != null) {
            String chosen = null;
            for (Iterator<String> it = regional.keys(); it.hasNext(); ) {
                String key = it.next();
                JSONObject info = regional.optJSONObject(key);
                if (info == null) continue;
                if (info.optBoolean("selected", false) || chosen == null && key.equalsIgnoreCase(g.region)) {
                    chosen = info.optString("display_version", "");
                    if (info.optBoolean("selected", false)) break;
                }
            }
            if (chosen != null) g.latestVersion = chosen;
        }
        return g;
    }

    private static void strings(JSONArray array, List<String> out) {
        if (array == null) return;
        for (int i = 0; i < array.length(); i++) {
            String value = array.optString(i, "");
            if (!value.isEmpty()) out.add(value);
        }
    }

    /** Product ids from {@code GET /game} ({@code {"wow": {"link": ...}, ...}}). */
    public static List<String> parseGameList(JSONObject json) {
        List<String> out = new ArrayList<>();
        if (json == null) return out;
        for (Iterator<String> it = json.keys(); it.hasNext(); ) out.add(it.next());
        return out;
    }

    /** The token from {@code GET /agent}, or "". */
    public static String parseToken(JSONObject agent) {
        return agent == null ? "" : agent.optString("authorization", "");
    }

    /**
     * A WoW install directory as the Agent wants it: all WoW games share
     * {@code World of Warcraft} and the Agent picks each game's subfolder.
     */
    public static String gameDir(String wowGuestRoot) {
        String dir = wowGuestRoot == null || wowGuestRoot.isEmpty()
                ? "C:\\Program Files (x86)\\World of Warcraft" : wowGuestRoot;
        dir = dir.replace('\\', '/');
        while (dir.endsWith("/")) dir = dir.substring(0, dir.length() - 1);
        return dir;
    }

    // ---- display ------------------------------------------------------------------------

    /** "1.2 GB", "640 MB" (decimal units, as Battle.net shows them). */
    public static String formatBytes(long bytes) {
        if (bytes >= 1_000_000_000L) return String.format(Locale.US, "%.1f GB", bytes / 1e9);
        if (bytes >= 1_000_000L) return String.format(Locale.US, "%d MB", Math.round(bytes / 1e6));
        if (bytes >= 1_000L) return String.format(Locale.US, "%d KB", Math.round(bytes / 1e3));
        return bytes + " B";
    }

    /** "8.3 MB/s". */
    public static String formatRate(long bytesPerSecond) {
        if (bytesPerSecond >= 1_000_000L) return String.format(Locale.US, "%.1f MB/s", bytesPerSecond / 1e6);
        return String.format(Locale.US, "%d KB/s", Math.max(0, Math.round(bytesPerSecond / 1e3)));
    }

    /** "9 min", "1 h 05 min", "40 s". */
    public static String formatEta(long seconds) {
        if (seconds < 0) return "";
        if (seconds < 60) return seconds + " s";
        long minutes = (seconds + 59) / 60;
        if (minutes < 60) return minutes + " min";
        return String.format(Locale.US, "%d h %02d min", minutes / 60, minutes % 60);
    }
}

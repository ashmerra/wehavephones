package com.wehavephones.mobile;

import android.os.Process;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ProcessScanner {
    public static final class Entry {
        public final int pid;
        public final String cmdline;
        public final String name;
        /** First NUL-separated argument (Wine sets it to the Windows exe path). */
        public final String argv0;

        Entry(int pid, String cmdline, String name) {
            this(pid, cmdline, name, cmdline);
        }

        Entry(int pid, String cmdline, String name, String argv0) {
            this.pid = pid;
            this.cmdline = cmdline;
            this.name = name;
            this.argv0 = argv0 != null ? argv0 : "";
        }
    }

    private ProcessScanner() {}

    public static List<Entry> sameUidProcesses() {
        ArrayList<Entry> entries = new ArrayList<>();
        File[] children = new File("/proc").listFiles();
        if (children == null) return entries;

        int myUid = Process.myUid();
        int myPid = Process.myPid();
        for (File child : children) {
            String directoryName = child.getName();
            if (!isNumeric(directoryName)) continue;
            int pid;
            try {
                pid = Integer.parseInt(directoryName);
            }
            catch (NumberFormatException e) {
                continue;
            }
            if (pid == myPid) continue;
            if (!isSameUid(pid, myUid)) continue;
            String raw = readText("/proc/" + pid + "/cmdline");
            int argEnd = raw.indexOf('\u0000');
            String argv0 = (argEnd >= 0 ? raw.substring(0, argEnd) : raw).trim();
            String cmdline = raw.replace('\u0000', ' ').trim();
            String name = readText("/proc/" + pid + "/comm").trim();
            if (cmdline.isEmpty() && name.isEmpty()) continue;
            entries.add(new Entry(pid, cmdline, name, argv0));
        }
        return entries;
    }

    public static Entry findWowProcess() {
        for (Entry entry : sameUidProcesses()) {
            if (isWowProcess(entry)) return entry;
        }
        return null;
    }

    /** True when a Battle.net/Agent/CEF launcher process of this uid is running. */
    public static boolean isLauncherRunning() {
        for (Entry entry : sameUidProcesses()) {
            if (WeHavePhonesLauncherReaper.isLauncherProcess(entry)) return true;
        }
        return false;
    }

    /** True when Battle.net itself (main or CEF helper, not Agent) of this uid is running. */
    public static boolean isBattleNetRunning() {
        for (Entry entry : sameUidProcesses()) {
            if (WeHavePhonesLauncherReaper.isBattleNetProcess(entry)) return true;
        }
        return false;
    }

    /** Includes orphaned guest processes after Android recreates the app. */
    public static boolean isWineSessionRunning() {
        for (Entry entry : sameUidProcesses()) {
            if (isWineSessionProcess(entry)) return true;
        }
        return false;
    }

    static boolean isWineSessionProcess(Entry entry) {
        // Zombies retain comm but have no command line and cannot own a session.
        if (entry == null || entry.cmdline.isEmpty()) return false;
        String executable = program(entry).toLowerCase(Locale.ROOT);
        return executable.endsWith(".exe") || executable.equals("wine")
                || executable.equals("wine-preloader") || executable.equals("wineserver");
    }

    /**
     * The game process itself: its argv[0] (a Windows path under Wine, may
     * contain spaces) or comm is a WoW executable. Arguments are not searched,
     * so a launcher or shell whose arguments mention the exe does not match.
     */
    public static boolean isWowProcess(Entry entry) {
        if (entry == null) return false;
        return matchesExecutable(entry.argv0) || matchesExecutable(entry.name);
    }

    /**
     * The executable a process runs: argv[0] (Wine's Windows exe path) cut
     * after its first ".exe", else comm. Never includes arguments.
     */
    public static String program(Entry entry) {
        String argv0 = entry.argv0.replace("\"", "");
        int end = argv0.toLowerCase(Locale.ROOT).indexOf(".exe");
        return end >= 0 ? argv0.substring(0, end + 4) : entry.name;
    }

    /**
     * Wine service processes (winedevice, plugplay, svchost, rpcss) can outlive
     * a session as zombie leaders that keep one thread alive, five per session.
     * Android counts them as phantom processes; on Android 17 (Pixel 8a) the
     * pile-up made system_server crash while trimming phantom processes.
     */
    public static List<Entry> leftoverProcesses() {
        ArrayList<Entry> leftovers = new ArrayList<>();
        for (Entry entry : sameUidProcesses()) {
            char state = processState(entry.pid);
            if (isLeftoverProcess(entry, state, state == 'Z' ? liveThreadCommand(entry.pid) : "")) leftovers.add(entry);
        }
        return leftovers;
    }

    /** SIGKILL ends a leftover's remaining thread so init can reap it. Only call without a session. */
    public static int reapLeftoverProcesses() {
        int killed = 0;
        for (Entry entry : leftoverProcesses()) {
            Process.sendSignal(entry.pid, Process.SIGNAL_KILL);
            killed++;
        }
        return killed;
    }

    /**
     * A zombie leader (no command line) of a guest process. Blizzard programs
     * rename their main thread, so a Battle.net leftover's comm is "Main" (one
     * held 396 MB of swap on the S23 across sessions). The command line its
     * remaining threads still report ({@code threadCommand}) names the exe.
     */
    static boolean isLeftoverProcess(Entry entry, char state, String threadCommand) {
        if (entry == null || state != 'Z' || !entry.cmdline.isEmpty()) return false;
        String name = entry.name.toLowerCase(Locale.ROOT);
        if (name.contains(".exe") || name.startsWith("wine")) return true;
        int argEnd = threadCommand != null ? threadCommand.indexOf('\u0000') : -1;
        String argv0 = threadCommand == null ? "" : argEnd >= 0 ? threadCommand.substring(0, argEnd) : threadCommand;
        return program(new Entry(entry.pid, argv0, "", argv0)).toLowerCase(Locale.ROOT).endsWith(".exe");
    }

    /** The raw command line of a thread other than the (zombie) leader, or "" when none is left. */
    static String liveThreadCommand(int pid) {
        File[] tasks = new File("/proc/" + pid + "/task").listFiles();
        if (tasks == null) return "";
        for (File task : tasks) {
            if (task.getName().equals(String.valueOf(pid))) continue;
            String command = readText(task.getPath() + "/cmdline");
            if (!command.isEmpty()) return command;
        }
        return "";
    }

    /** The state letter from /proc/[pid]/stat, or '?' when unreadable. */
    static char processState(int pid) {
        String stat = readText("/proc/" + pid + "/stat");
        int end = stat.lastIndexOf(')');
        return end >= 0 && end + 2 < stat.length() ? stat.charAt(end + 2) : '?';
    }

    public static int killWineProcesses() {
        int killed = 0;
        for (Entry entry : sameUidProcesses()) {
            String haystack = (entry.cmdline + " " + entry.name).toLowerCase(Locale.ROOT);
            boolean isGuest = haystack.contains("wine") || haystack.contains("wow")
                    || haystack.contains("battle.net") || haystack.contains("imagefs");
            if (!isGuest) continue;
            try {
                Process.killProcess(entry.pid);
                killed++;
            }
            catch (Exception ignored) {}
        }
        return killed;
    }

    public static String describeProcesses() {
        StringBuilder builder = new StringBuilder();
        List<Entry> entries = sameUidProcesses();
        for (Entry entry : entries) {
            if (builder.length() > 0) builder.append('\n');
            String cmdline = entry.cmdline;
            if (cmdline.length() > 160) cmdline = cmdline.substring(0, 157) + "...";
            builder.append(entry.pid).append(' ').append(entry.name);
            if (!cmdline.isEmpty()) builder.append(' ').append(cmdline);
        }
        return builder.toString();
    }

    private static boolean matchesExecutable(String token) {
        if (token == null || token.isEmpty()) return false;
        String cleaned = token.replace("\"", "");
        int index = Math.max(cleaned.lastIndexOf('/'), cleaned.lastIndexOf('\\'));
        String basename = index >= 0 ? cleaned.substring(index + 1) : cleaned;
        return WowFlavor.isGameExecutable(basename);
    }

    private static boolean isNumeric(String value) {
        if (value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) return false;
        }
        return true;
    }

    private static boolean isSameUid(int pid, int uid) {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/" + pid + "/status"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("Uid:")) continue;
                String[] fields = line.substring(4).trim().split("\\s+");
                for (String field : fields) {
                    if (field.equals(String.valueOf(uid))) return true;
                }
                return false;
            }
        }
        catch (IOException e) {
            return false;
        }
        return false;
    }

    /** Reads up to 4 KiB. /proc files report length 0, so the size is not checked. */
    private static String readText(String path) {
        File file = new File(path);
        if (!file.isFile()) return "";
        try (FileInputStream inStream = new FileInputStream(file)) {
            byte[] buffer = new byte[4096];
            int total = 0;
            while (total < buffer.length) {
                int read = inStream.read(buffer, total, buffer.length - total);
                if (read == -1) break;
                total += read;
            }
            return new String(buffer, 0, total, StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            return "";
        }
    }
}

package com.wehavephones.mobile;

import android.content.Context;

import com.wehavephones.container.Container;
import com.wehavephones.core.FileUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Live memory telemetry for the diagnostics panel: host MemAvailable/SwapFree,
 * memory PSI, and same-uid per-process RSS for the Wine/Battle.net/WoW/Agent/
 * wineserver set. Also writes the JSON Lines report consumed by
 * {@code scripts/test-wehavephones-memory.sh} / host analysis.
 */
public final class WeHavePhonesMemoryDiagnostics {
    private static final String[] PROCESS_TOKENS = {"wine", "battle.net", "wow", "agent", "wineserver"};
    public static final String REPORTS_DIR = "memory-reports";

    private WeHavePhonesMemoryDiagnostics() {}

    public static MemoryReport.Sample sample() {
        MemoryReport.Sample sample = new MemoryReport.Sample();
        sample.timeMillis = System.currentTimeMillis();
        readMemInfo(sample);
        readPsi(sample);
        readProcesses(sample);
        return sample;
    }

    public static String format(MemoryReport.Sample sample) {
        StringBuilder builder = new StringBuilder();
        builder.append("MemAvailable: ").append(mb(sample.memAvailableKb)).append(" MB\n");
        builder.append("SwapFree: ").append(mb(sample.swapFreeKb)).append(" MB\n");
        builder.append("PSI memory avg10: some=").append(percent(sample.psiSomeAvg10))
                .append("% full=").append(percent(sample.psiFullAvg10)).append("%\n");
        builder.append("Same-uid processes: ").append(sample.processes.size()).append('\n');
        for (MemoryReport.ProcessRss process : sample.processes) {
            builder.append("  ").append(String.format(Locale.US, "%6d", process.pid));
            if (process.rssKb >= 0) builder.append("  ").append(mb(process.rssKb)).append(" MB");
            else builder.append("  ? MB");
            builder.append("  ").append(shortName(process.name)).append('\n');
        }
        return builder.toString().trim();
    }

    public static String reportHeader(Context context) {
        Container container = WeHavePhonesContainers.resolve(context);
        StringBuilder overrides = new StringBuilder();
        for (String label : WeHavePhonesMemoryOverrides.appliedLabels(context, container)) {
            if (overrides.length() > 0) overrides.append("; ");
            overrides.append(label);
        }
        return MemoryReport.headerLine("default",
                overrides.toString(), System.currentTimeMillis());
    }

    /** Write the collected samples as JSONL under files/wehavephones/memory-reports/. */
    public static File writeReport(Context context, List<MemoryReport.Sample> samples) {
        File dir = new File(WeHavePhonesPaths.wehavephonesDir(context), REPORTS_DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) return null;
        File file = new File(dir, "memory-" + WeHavePhonesPaths.utcStamp() + ".jsonl");
        StringBuilder content = new StringBuilder();
        content.append(reportHeader(context)).append('\n');
        for (MemoryReport.Sample sample : samples) content.append(MemoryReport.sampleLine(sample)).append('\n');
        if (!FileUtils.writeString(file, content.toString())) return null;
        return file;
    }

    static String shortName(String name) {
        if (name == null) return "";
        String cleaned = name.trim();
        return cleaned.length() > 96 ? cleaned.substring(0, 93) + "..." : cleaned;
    }

    private static void readMemInfo(MemoryReport.Sample sample) {
        File file = new File("/proc/meminfo");
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("MemAvailable:")) sample.memAvailableKb = parseKb(line);
                else if (line.startsWith("SwapFree:")) sample.swapFreeKb = parseKb(line);
                if (sample.memAvailableKb >= 0 && sample.swapFreeKb >= 0) break;
            }
        }
        catch (IOException ignored) {}
    }

    private static void readPsi(MemoryReport.Sample sample) {
        File file = new File("/proc/pressure/memory");
        if (!file.isFile()) return;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] fields = line.trim().split("\\s+");
                if (fields.length == 0) continue;
                double avg10 = -1.0;
                for (int i = 1; i < fields.length; i++) {
                    if (fields[i].startsWith("avg10=")) {
                        try {
                            avg10 = Double.parseDouble(fields[i].substring("avg10=".length()));
                        }
                        catch (NumberFormatException ignored) {}
                    }
                }
                if ("some".equals(fields[0])) sample.psiSomeAvg10 = avg10;
                else if ("full".equals(fields[0])) sample.psiFullAvg10 = avg10;
            }
        }
        catch (IOException ignored) {}
    }

    private static void readProcesses(MemoryReport.Sample sample) {
        ArrayList<MemoryReport.ProcessRss> processes = new ArrayList<>();
        for (ProcessScanner.Entry entry : ProcessScanner.sameUidProcesses()) {
            String haystack = (entry.cmdline + " " + entry.name).toLowerCase(Locale.ROOT);
            boolean matches = false;
            for (String token : PROCESS_TOKENS) {
                if (haystack.contains(token)) {
                    matches = true;
                    break;
                }
            }
            if (!matches) continue;
            processes.add(new MemoryReport.ProcessRss(entry.pid, entry.name, readRssKb(entry.pid)));
        }
        Collections.sort(processes, new Comparator<MemoryReport.ProcessRss>() {
            @Override
            public int compare(MemoryReport.ProcessRss first, MemoryReport.ProcessRss second) {
                return Long.compare(second.rssKb, first.rssKb);
            }
        });
        sample.processes.addAll(processes);
    }

    private static long readRssKb(int pid) {
        File file = new File("/proc/" + pid + "/status");
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("VmRSS:")) {
                    return parseKb(line);
                }
            }
        }
        catch (IOException ignored) {}
        return -1;
    }

    private static long parseKb(String line) {
        String[] fields = line.trim().split("\\s+");
        if (fields.length < 2) return -1;
        try {
            return Long.parseLong(fields[1]);
        }
        catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String mb(long kb) {
        return kb >= 0 ? String.valueOf(kb / 1024) : "?";
    }

    private static String percent(double value) {
        return value >= 0 ? String.format(Locale.US, "%.1f", value) : "?";
    }
}

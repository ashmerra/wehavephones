package com.wehavephones.mobile;

import android.content.Context;
import android.os.Environment;

import com.wehavephones.container.Container;
import com.wehavephones.core.FileUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

public final class WeHavePhonesDiagnostics {
    private WeHavePhonesDiagnostics() {}

    public static String buildReport(Context context) {
        WeHavePhonesStatus status = WeHavePhonesStatus.load(context);
        StringBuilder builder = new StringBuilder();

        builder.append("== WeHavePhones diagnostics ==\n");
        builder.append("Package: ").append(context.getPackageName()).append('\n');
        builder.append("User id: ").append(android.os.Process.myUid()).append('\n');

        builder.append("\n-- Runtime profile --\n");
        if (status.profiles == null) {
            builder.append("Bundled runtime profiles unavailable\n");
        }
        else {
            builder.append("Mode: ").append(status.profiles.getMode().name().toLowerCase(java.util.Locale.US)).append('\n');
            builder.append("Battle.net stage: ").append(status.profiles.getBnetStageProfile().name).append('\n');
            builder.append("WoW stage: ").append(status.profiles.getWowStageProfile().name).append('\n');
            builder.append("Single stage: ").append(status.profiles.getSingleStageProfile().name).append('\n');
            builder.append("Active profile: ").append(status.profile != null ? status.profile.name : "none").append('\n');
            builder.append("Applied: ").append(status.profileApplied ? "yes" : "no").append('\n');
            if (status.container != null && status.profile != null) {
                builder.append("bnet.bat matches: ").append(WeHavePhonesStatus.bnetBatMatches(context, status.container, status.profiles, status.profile) ? "yes" : "no").append('\n');
            }
            appendAppliedNtdll(context, builder);
        }

        builder.append("\n-- ImageFs --\n");
        builder.append("Path: ").append(WeHavePhonesPaths.imageFsDir(context).getPath()).append('\n');
        builder.append("Valid: ").append(status.imageFsValid ? "yes (v" + status.imageFsVersion + ")" : "no").append('\n');

        builder.append("\n-- Container --\n");
        if (status.container == null) {
            builder.append("No container found\n");
        }
        else {
            Container container = status.container;
            builder.append("Path: ").append(container.getRootDir().getPath()).append('\n');
            builder.append("Name: ").append(container.getName()).append(" (#").append(container.id).append(")\n");
            builder.append("wineVersion: ").append(container.getWineVersion()).append('\n');
            builder.append("dxwrapper: ").append(container.getDXWrapper()).append('\n');
            builder.append("graphicsDriver: ").append(container.getGraphicsDriver()).append('\n');
            builder.append("Battle.net installed: ").append(status.bnetInstalled ? "yes" : "no").append('\n');
            builder.append("Desktop dir: ").append(container.getDesktopDir().getPath()).append('\n');
        }

        builder.append("\n-- WoW games (Battle.net) --\n");
        if (status.games.isEmpty()) {
            builder.append("None detected\n");
        }
        else {
            BattleNetGames.Game selected = BattleNetGames.pick(status.games, WeHavePhonesConfig.getProductDir(context));
            for (BattleNetGames.Game game : status.games) {
                builder.append(game == selected ? "* " : "  ");
                builder.append(game.getDisplayName()).append(game.installed ? " [installed]" : " [not installed]")
                        .append(" flavor=").append(game.flavor.name().toLowerCase(java.util.Locale.US));
                if (!game.directoryName.isEmpty()) builder.append(" dir=").append(game.directoryName);
                if (!game.productCode.isEmpty()) builder.append(" product=").append(game.productCode);
                if (!game.uid.isEmpty()) builder.append(" uid=").append(game.uid);
                if (!game.version.isEmpty()) builder.append(" version=").append(game.version);
                if (game.product != null && !game.product.getExecutable().isEmpty()) builder.append(" exe=").append(game.product.getExecutable());
                builder.append(" from=").append(String.join(",", game.sources)).append('\n');
            }
        }

        builder.append("\n-- Memory --\n");
        for (String label : WeHavePhonesMemoryOverrides.appliedLabels(context, status.container)) {
            builder.append(label).append('\n');
        }
        builder.append(memInfo());

        builder.append("\n-- Logs --\n");
        File wineLog = new File(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "WeHavePhones"), "wine.log");
        builder.append("wine.log: ").append(wineLog.getPath()).append(wineLog.isFile() ? " (" + wineLog.length() + " bytes)" : " (missing)").append('\n');
        File logDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "WeHavePhones");
        builder.append("log dir: ").append(logDir.getPath()).append(logDir.isDirectory() ? "" : " (missing)").append('\n');
        File guestLog = new File(logDir, "guest.log");
        builder.append("guest.log: ").append(guestLog.isFile() ? guestLog.getPath() + " (" + guestLog.length() + " bytes)" : "missing").append('\n');

        builder.append("\n-- Last one-button launch --\n");
        builder.append(OneButtonLauncher.lastRunSummary(context));

        builder.append("\n-- Processes (same uid) --\n");
        String processes = ProcessScanner.describeProcesses();
        builder.append(processes.isEmpty() ? "None" : processes).append('\n');

        return builder.toString();
    }

    private static void appendAppliedNtdll(Context context, StringBuilder builder) {
        File imageFs = WeHavePhonesPaths.imageFsDir(context);
        File optDir = new File(imageFs, "opt");
        File[] layers = optDir.listFiles();
        if (layers == null) return;
        for (File layer : layers) {
            File ntdll = new File(layer, "lib/wine/aarch64-windows/ntdll.dll");
            if (!ntdll.isFile()) continue;
            String sha = sha256(FileUtils.read(ntdll));
            builder.append("ntdll ").append(layer.getName()).append(": ")
                    .append(sha.length() > 16 ? sha.substring(0, 16) + "..." : sha)
                    .append(" (").append(ntdll.length()).append(" bytes)\n");
        }
    }

    private static String memInfo() {
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("MemTotal:") || line.startsWith("MemAvailable:") || line.startsWith("SwapTotal:") || line.startsWith("SwapFree:")) {
                    builder.append(line.trim()).append('\n');
                }
            }
        }
        catch (IOException e) {
            builder.append("Unavailable\n");
        }
        return builder.toString();
    }

    private static String sha256(byte[] data) {
        if (data == null) return "";
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder builder = new StringBuilder();
            for (byte value : hash) builder.append(String.format("%02x", value));
            return builder.toString();
        }
        catch (Exception e) {
            return "";
        }
    }

}

package com.wehavephones.mobile;

import android.content.Context;

import com.wehavephones.container.Container;
import com.wehavephones.core.FileUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Applies the memory trims and the game resolution from Settings on top of the
 * runtime profile that is active for the session. The runtime profile JSON is
 * never modified; only the container config and the WoW {@code Config.wtf}
 * files are touched (and a leftover {@code C:/wehavephones-dxvk.conf} from the old
 * low-memory mode removed), always after a snapshot and always verified by
 * read-back.
 */
public final class WeHavePhonesMemoryOverrides {
    public static final String SNAPSHOT_PREFIX = "memory-";
    public static final String MANIFEST_NAME = "memory.json";
    private static final int MAX_SNAPSHOTS = 10;

    public static final class Result {
        public boolean success = true;
        public boolean changed;
        public File snapshotDir;
        public final ArrayList<String> steps = new ArrayList<>();
        public final ArrayList<String> errors = new ArrayList<>();

        void step(String message) {
            steps.add(message);
        }

        void fail(String message) {
            errors.add(message);
            success = false;
        }

        public String summary() {
            StringBuilder builder = new StringBuilder();
            for (String step : steps) builder.append(step).append('\n');
            for (String error : errors) builder.append("ERROR: ").append(error).append('\n');
            return builder.toString().trim();
        }
    }

    private static final class ConfigPlan {
        final WowProduct product;
        final File file;
        final String original;
        final LinkedHashMap<String, String> settings;
        String updated;

        ConfigPlan(WowProduct product, File file, String original, LinkedHashMap<String, String> settings) {
            this.product = product;
            this.file = file;
            this.original = original;
            this.settings = settings;
        }
    }

    private WeHavePhonesMemoryOverrides() {}

    /**
     * Compose and apply the overrides. Idempotent: when everything is already
     * in place nothing is written and no snapshot is taken. On a failed
     * read-back every touched file is rolled back.
     */
    public static Result apply(Context context, Container container) {
        Result result = new Result();
        if (container == null) {
            result.fail("No container available for memory overrides");
            return result;
        }

        String beforeEnv = container.getEnvVars();
        String afterEnv = MemoryTransforms.composeEnv(beforeEnv);
        String beforeScreen = container.getScreenSize();
        String afterScreen = WeHavePhonesConfig.getGameScreenSize(context);

        // Only existing files: the game writes a full Config.wtf on its first run.
        ArrayList<ConfigPlan> plans = new ArrayList<>();
        LinkedHashMap<String, String> settings = MemoryTransforms.resolutionCvars(afterScreen);
        for (WowProduct product : WowInstallationScanner.scan(container)) {
            File configFile = new File(new File(product.directory, "WTF"), "Config.wtf");
            if (!configFile.isFile()) continue;
            String original = FileUtils.readString(configFile);
            if (original == null) continue;
            ConfigPlan plan = new ConfigPlan(product, configFile, original, settings);
            plan.updated = MemoryTransforms.editConfigWtf(original, settings);
            plans.add(plan);
        }

        File dxvkFile = new File(WeHavePhonesPaths.driveC(container), MemoryTransforms.LEGACY_DXVK_CONFIG_FILE_NAME);
        byte[] dxvkBefore = dxvkFile.isFile() ? FileUtils.read(dxvkFile) : null;

        boolean envChanged = !afterEnv.equals(beforeEnv != null ? beforeEnv : "");
        boolean screenChanged = !afterScreen.equals(beforeScreen != null ? beforeScreen : "");
        boolean dxvkChanged = dxvkBefore != null;
        ArrayList<ConfigPlan> configChanged = new ArrayList<>();
        for (ConfigPlan plan : plans) {
            if (!plan.updated.equals(plan.original)) configChanged.add(plan);
        }

        if (!envChanged && !screenChanged && !dxvkChanged && configChanged.isEmpty()) {
            result.changed = false;
            result.step("Memory overrides and game resolution " + afterScreen + " already applied");
            return result;
        }

        result.changed = true;
        byte[] containerBefore = FileUtils.read(container.getConfigFile());
        try {
            result.snapshotDir = createSnapshot(context, container, configChanged, dxvkBefore);
            result.step("Memory snapshot saved: " + result.snapshotDir.getName());
        }
        catch (Exception e) {
            result.fail("Could not snapshot memory state: " + e.getMessage());
            return result;
        }

        boolean containerWritten = false;
        try {
            if (envChanged || screenChanged) {
                container.setEnvVars(afterEnv);
                container.setScreenSize(afterScreen);
                container.saveData();
                ArrayList<String> mismatches = verifyContainer(container, afterScreen);
                if (!mismatches.isEmpty()) {
                    restoreContainerFile(container, containerBefore);
                    result.fail("Container read-back verification failed: " + join(mismatches));
                    return result;
                }
                containerWritten = true;
                result.step("Container memory overrides applied: " + describeEnvChanges()
                        + (screenChanged ? " screenSize=" + afterScreen : ""));
            }

            if (dxvkChanged) {
                if (!FileUtils.delete(dxvkFile) || dxvkFile.exists()) {
                    result.fail("Could not remove " + dxvkFile.getPath());
                    rollback(result, container, containerBefore, containerWritten, dxvkFile, dxvkBefore, plans);
                    return result;
                }
                result.step("Removed the old low-memory " + MemoryTransforms.LEGACY_DXVK_CONFIG_FILE_NAME);
            }

            for (ConfigPlan plan : configChanged) {
                if (!FileUtils.write(plan.file, plan.updated.getBytes(StandardCharsets.UTF_8))) {
                    result.fail("Could not write " + plan.file.getPath());
                    rollback(result, container, containerBefore, containerWritten, dxvkFile, dxvkBefore, plans);
                    return result;
                }
                String readBack = FileUtils.readString(plan.file);
                if (!MemoryTransforms.configWtfContains(readBack, plan.settings)) {
                    result.fail("Config.wtf read-back verification failed: " + plan.file.getPath());
                    rollback(result, container, containerBefore, containerWritten, dxvkFile, dxvkBefore, plans);
                    return result;
                }
                result.step("Game resolution " + afterScreen + " set for " + plan.product.directoryName);
            }
        }
        catch (Exception e) {
            rollback(result, container, containerBefore, containerWritten, dxvkFile, dxvkBefore, plans);
            result.fail("Memory overrides apply failed: " + e);
        }
        return result;
    }

    /** Human-readable list of the overrides applied at the next Play. */
    public static ArrayList<String> appliedLabels(Context context, Container container) {
        ArrayList<String> labels = new ArrayList<>();
        for (Map.Entry<String, String> entry : MemoryTransforms.envOverrides().entrySet()) {
            labels.add("env " + entry.getKey() + "=" + entry.getValue());
        }
        labels.add("env " + MemoryTransforms.ENV_DXVK_HUD + " removed");
        labels.add("Game resolution " + WeHavePhonesConfig.getGameScreenSize(context) + " (screenSize + GxMaximize + GxWindowedResolution)");
        labels.add("Battle.net auto-close after WoW starts");
        if (container != null) {
            labels.add("container screenSize=" + container.getScreenSize());
        }
        File snapshot = resolveSnapshot(context);
        if (snapshot != null) {
            labels.add("Last memory snapshot: " + snapshot.getName());
        }
        return labels;
    }

    public static File resolveSnapshot(Context context) {
        String name = WeHavePhonesConfig.getMemorySnapshot(context);
        File dir = WeHavePhonesPaths.snapshotsDir(context);
        if (name != null && !name.isEmpty()) {
            File named = new File(dir, name);
            if (named.isDirectory()) return named;
        }
        return latestSnapshot(dir);
    }

    static File latestSnapshot(File snapshotsDir) {
        File[] entries = snapshotsDir.listFiles();
        if (entries == null) return null;
        File latest = null;
        for (File entry : entries) {
            if (!entry.isDirectory() || !entry.getName().startsWith(SNAPSHOT_PREFIX)) continue;
            if (latest == null || entry.getName().compareTo(latest.getName()) > 0) latest = entry;
        }
        return latest;
    }

    private static File createSnapshot(Context context, Container container,
                                       ArrayList<ConfigPlan> plans, byte[] dxvkBefore) throws Exception {
        File dir = new File(WeHavePhonesPaths.snapshotsDir(context), SNAPSHOT_PREFIX + WeHavePhonesPaths.utcStamp());
        int suffix = 1;
        while (dir.exists()) {
            dir = new File(WeHavePhonesPaths.snapshotsDir(context), SNAPSHOT_PREFIX + WeHavePhonesPaths.utcStamp() + "-" + (++suffix));
        }
        if (!dir.mkdirs()) throw new Exception("Could not create " + dir);

        FileUtils.copy(container.getConfigFile(), new File(dir, "container.json"));

        JSONObject manifest = new JSONObject();
        manifest.put("kind", "wehavephones-memory-snapshot");
        manifest.put("dxvkConfExisted", dxvkBefore != null);
        JSONArray products = new JSONArray();
        for (ConfigPlan plan : plans) {
            String safeName = plan.product.directoryName.replaceAll("[^A-Za-z0-9._-]", "_");
            String backupName = "config-" + safeName + ".wtf";
            FileUtils.write(new File(dir, backupName), plan.original.getBytes(StandardCharsets.UTF_8));
            JSONObject entry = new JSONObject();
            entry.put("product", plan.product.directoryName);
            entry.put("path", plan.file.getPath());
            entry.put("backup", backupName);
            products.put(entry);
        }
        manifest.put("products", products);
        FileUtils.writeString(new File(dir, MANIFEST_NAME), manifest.toString(2));
        if (dxvkBefore != null) {
            FileUtils.write(new File(dir, MemoryTransforms.LEGACY_DXVK_CONFIG_FILE_NAME + ".bak"), dxvkBefore);
        }
        WeHavePhonesConfig.setMemorySnapshot(context, dir.getName());
        pruneSnapshots(WeHavePhonesPaths.snapshotsDir(context));
        return dir;
    }

    private static ArrayList<String> verifyContainer(Container container, String expectedScreen) throws Exception {
        ArrayList<String> mismatches = new ArrayList<>();
        JSONObject applied = new JSONObject(FileUtils.readString(container.getConfigFile()));
        String envVars = applied.optString("envVars", "");
        for (Map.Entry<String, String> entry : MemoryTransforms.envOverrides().entrySet()) {
            if (!envVars.contains(entry.getKey() + "=" + entry.getValue())) {
                mismatches.add(entry.getKey() + "=" + entry.getValue());
            }
        }
        if (!MemoryTransforms.hasDxvkChunkSize(envVars)) {
            mismatches.add(MemoryTransforms.ENV_DXVK_CONFIG + " without " + MemoryTransforms.DXVK_CHUNK_OPTION);
        }
        if (envVars.contains(MemoryTransforms.ENV_DXVK_HUD + "=")) {
            mismatches.add(MemoryTransforms.ENV_DXVK_HUD + " still present");
        }
        if (!expectedScreen.equals(applied.optString("screenSize", ""))) {
            mismatches.add("screenSize=" + applied.optString("screenSize", "?"));
        }
        return mismatches;
    }

    private static void restoreContainerFile(Container container, byte[] before) {
        if (before != null) FileUtils.write(container.getConfigFile(), before);
    }

    private static void rollback(Result result, Container container, byte[] containerBefore,
                                 boolean containerWritten, File dxvkFile, byte[] dxvkBefore,
                                 ArrayList<ConfigPlan> plans) {
        if (containerWritten) {
            restoreContainerFile(container, containerBefore);
            result.step("Rolled back container config after failure");
        }
        if (dxvkBefore != null) {
            FileUtils.write(dxvkFile, dxvkBefore);
        }
        else if (dxvkFile.isFile()) {
            FileUtils.delete(dxvkFile);
        }
        for (ConfigPlan plan : plans) {
            if (!plan.updated.equals(plan.original)) {
                FileUtils.write(plan.file, plan.original.getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    private static String describeEnvChanges() {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : MemoryTransforms.envOverrides().entrySet()) {
            if (builder.length() > 0) builder.append(' ');
            builder.append(entry.getKey()).append('=').append(entry.getValue());
        }
        builder.append(' ').append(MemoryTransforms.ENV_DXVK_HUD).append("=removed");
        return builder.toString();
    }

    private static void pruneSnapshots(File snapshotsDir) {
        File[] entries = snapshotsDir.listFiles();
        if (entries == null) return;
        ArrayList<File> memories = new ArrayList<>();
        for (File entry : entries) {
            if (entry.isDirectory() && entry.getName().startsWith(SNAPSHOT_PREFIX)) memories.add(entry);
        }
        if (memories.size() <= MAX_SNAPSHOTS) return;
        java.util.Collections.sort(memories, (first, second) -> first.getName().compareTo(second.getName()));
        for (int i = 0; i < memories.size() - MAX_SNAPSHOTS; i++) FileUtils.delete(memories.get(i));
    }

    private static String join(ArrayList<String> values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (builder.length() > 0) builder.append(", ");
            builder.append(value);
        }
        return builder.toString();
    }
}

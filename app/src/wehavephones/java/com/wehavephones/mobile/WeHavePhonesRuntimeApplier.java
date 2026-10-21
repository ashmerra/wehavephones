package com.wehavephones.mobile;

import android.content.Context;

import com.wehavephones.R;
import com.wehavephones.container.Container;
import com.wehavephones.mobile.devices.DeviceProfileSelector;
import com.wehavephones.core.FileUtils;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;

public final class WeHavePhonesRuntimeApplier {
    public enum Stage {
        BATTLE_NET, WOW, SINGLE
    }

    public static final class Result {
        public boolean success = true;
        public Container container;
        public WeHavePhonesRuntimeProfiles.Profile profile;
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

    private WeHavePhonesRuntimeApplier() {}

    public static Result apply(Context context, Stage stage) {
        Result result = new Result();
        WeHavePhonesRuntimeProfiles profiles = WeHavePhonesRuntimeProfiles.loadOrNull(context);
        if (profiles == null) {
            result.fail("Bundled runtime profiles could not be read");
            return result;
        }
        WeHavePhonesRuntimeProfiles.Profile profile;
        if (stage == Stage.WOW) {
            profile = profiles.getWowStageProfile();
        }
        else if (profiles.isHandoff()) {
            WeHavePhonesRuntimeProfiles.Profile selected = profiles.getProfile(WeHavePhonesConfig.getRuntimeProfile(context));
            profile = selected != null ? selected : profiles.getBnetStageProfile();
        }
        else {
            profile = profiles.getSingleStageProfile();
        }
        return apply(context, profiles, profile, stage);
    }

    public static Result apply(Context context, WeHavePhonesRuntimeProfiles profiles,
                               WeHavePhonesRuntimeProfiles.Profile profile, Stage stage) {
        Result result = new Result();
        result.profile = profile;
        if (profile == null) {
            result.fail("No runtime profile is configured for stage " + stage);
            return result;
        }

        Container container = WeHavePhonesContainers.resolve(context);
        if (container == null) {
            result.fail("No Wine container found. Run first-run setup first");
            return result;
        }
        result.container = container;

        if (!checkWineLayer(context, profile, result)) return result;

        try {
            result.snapshotDir = snapshot(context, container, profile.name);
            result.step("Snapshot saved: " + result.snapshotDir.getName());
        }
        catch (Exception e) {
            result.fail("Could not snapshot container state: " + e.getMessage());
            return result;
        }

        applyContainerConfig(container, profile, result);

        WeHavePhonesMemoryOverrides.Result memory = WeHavePhonesMemoryOverrides.apply(context, container);
        for (String step : memory.steps) result.step(step);
        for (String error : memory.errors) result.fail(error);

        if (stage == Stage.BATTLE_NET) {
            container.setScreenSize(WeHavePhonesConfig.BNET_SETUP_SCREEN_SIZE);
            container.saveData();
            result.step("Battle.net setup resolution " + WeHavePhonesConfig.BNET_SETUP_SCREEN_SIZE);
        }

        if (stage != Stage.WOW) {
            writeBat(WeHavePhonesPaths.bnetBat(container), profiles.buildBnetBat(profile).getBytes(StandardCharsets.UTF_8), "bnet.bat", result);
        }
        if (stage != Stage.BATTLE_NET) {
            WowProduct product = resolveProduct(context, container, result);
            if (product != null) {
                try {
                    if (WowFirstBoot.prepare(product.directory, WeHavePhonesConfig.getGameRegion(context),
                            product.getFlavor() == WowFlavor.RETAIL,
                            WeHavePhonesConfig.getGameScreenSize(context), WeHavePhonesConfig.isGameRegionOverride(context),
                            DeviceProfileSelector.wowApiArg(context),
                            product.getFlavor().family == WowFlavor.Family.FOREVER)) result.step("WoW first-boot settings prepared");
                }
                catch (java.io.IOException e) {
                    result.fail("Could not prepare WoW first boot: " + e.getMessage());
                }
            }
            String productDir = product != null ? product.directoryName : WeHavePhonesRuntimeProfiles.DEFAULT_PRODUCT_DIR;
            String executable = product != null && !product.getExecutable().isEmpty()
                    ? product.getExecutable() : WeHavePhonesRuntimeProfiles.DEFAULT_WOW_EXECUTABLE;
            writeBat(WeHavePhonesPaths.wowBat(container), profiles.buildWowBat(productDir, executable, DeviceProfileSelector.wowApiArg(context)).getBytes(StandardCharsets.UTF_8), "wow.bat", result);
        }

        deployArtifacts(context, container, profile, "ntdll", profile.ntdll, true, result);
        deployArtifacts(context, container, profile, "files", profile.files, false, result);
        return result;
    }

    public static File snapshot(Context context, Container container, String reason) throws Exception {
        File dir = new File(WeHavePhonesPaths.snapshotsDir(context), WeHavePhonesPaths.utcStamp() + "-" + reason);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new Exception("Could not create snapshot dir " + dir);
        copyIfExists(container.getConfigFile(), new File(dir, "container.json"));
        copyIfExists(WeHavePhonesPaths.bnetBat(container), new File(dir, "bnet.bat"));
        copyIfExists(WeHavePhonesPaths.wowBat(container), new File(dir, "wow.bat"));
        pruneSnapshots(WeHavePhonesPaths.snapshotsDir(context), 20);
        return dir;
    }

    private static void applyContainerConfig(Container container, WeHavePhonesRuntimeProfiles.Profile profile, Result result) {
        try {
            File configFile = container.getConfigFile();
            byte[] before = FileUtils.read(configFile);

            if (!profile.wineVersion.isEmpty()) container.setWineVersion(profile.wineVersion);

            String envVars = container.getEnvVars();
            for (Map.Entry<String, String> entry : profile.env.entrySet()) {
                String updated = setEnvVar(envVars, entry.getKey(), entry.getValue());
                if (updated == null) {
                    result.fail("Invalid environment override: " + entry.getKey());
                    return;
                }
                envVars = updated;
            }
            if (!profile.env.isEmpty()) container.setEnvVars(envVars);

            if (!profile.dxwrapper.isEmpty()) {
                container.setDXWrapper(profile.dxwrapper);
                String assets = !profile.dxwrapperAssets.isEmpty() ? profile.dxwrapperAssets : profile.dxwrapper;
                container.putExtra("dxwrapper", assets);
            }

            if (!profile.graphics.isEmpty()) {
                container.setGraphicsDriverConfig(applyGraphics(container.getGraphicsDriverConfig(), profile.graphics));
            }

            container.saveData();

            JSONObject applied = new JSONObject(FileUtils.readString(configFile));
            ArrayList<String> mismatches = verifyContainer(applied, profile, container);
            if (!mismatches.isEmpty()) {
                FileUtils.write(configFile, before);
                result.fail("Container read-back verification failed: " + join(mismatches));
            }
            else {
                result.step("Container config applied: wine=" + container.getWineVersion()
                        + (profile.env.isEmpty() ? "" : " env=" + profile.env.keySet()));
            }
        }
        catch (Exception e) {
            result.fail("Container config update failed: " + e);
        }
    }

    private static ArrayList<String> verifyContainer(JSONObject applied, WeHavePhonesRuntimeProfiles.Profile profile, Container container) {
        ArrayList<String> mismatches = new ArrayList<>();
        if (!profile.wineVersion.isEmpty() && !profile.wineVersion.equals(applied.optString("wineVersion"))) {
            mismatches.add("wineVersion=" + applied.optString("wineVersion"));
        }
        String envVars = applied.optString("envVars", "");
        for (Map.Entry<String, String> entry : profile.env.entrySet()) {
            if (!envVars.contains(entry.getKey() + "=" + entry.getValue())) mismatches.add(entry.getKey());
        }
        if (!profile.dxwrapper.isEmpty() && !profile.dxwrapper.equals(applied.optString("dxwrapper"))) {
            mismatches.add("dxwrapper=" + applied.optString("dxwrapper"));
        }
        if (!profile.graphics.isEmpty()) {
            String graphics = applied.optString("graphicsDriverConfig", "");
            for (Map.Entry<String, String> entry : profile.graphics.entrySet()) {
                if (!graphics.contains(entry.getKey() + "=" + entry.getValue())) mismatches.add("graphics:" + entry.getKey());
            }
        }
        return mismatches;
    }

    private static void writeBat(File file, byte[] content, String label, Result result) {
        boolean written = FileUtils.write(file, content);
        byte[] readBack = written ? FileUtils.read(file) : null;
        if (readBack != null && Arrays.equals(readBack, content)) {
            result.step(label + " written and verified: " + file.getPath());
        }
        else {
            result.fail(label + " write/read-back failed: " + file.getPath());
        }
    }

    private static boolean checkWineLayer(Context context, WeHavePhonesRuntimeProfiles.Profile profile, Result result) {
        if (profile.wineVersion.isEmpty()) return true;
        File layerDir = new File(WeHavePhonesPaths.imageFsDir(context), "opt/" + profile.wineVersion);
        if (layerDir.isDirectory()) return true;

        StringBuilder message = new StringBuilder(context.getString(
                R.string.wehavephones_runtime_layer_missing, profile.wineVersion, layerDir.getPath()));
        WeHavePhonesRuntimeProfiles.Layer layer = profile.layer;
        if (layer != null && layer.hasDownload()) {
            message.append(' ').append(context.getString(R.string.wehavephones_runtime_layer_missing_source,
                    layer.url, layer.sizeLabel, layer.sha256, layer.name));
        }
        result.fail(message.toString());
        return false;
    }

    private static void deployArtifacts(Context context, Container container, WeHavePhonesRuntimeProfiles.Profile profile,
                                        String label, ArrayList<WeHavePhonesRuntimeProfiles.Artifact> artifacts,
                                        boolean announceEmpty, Result result) {
        if (artifacts.isEmpty()) {
            if (announceEmpty) {
                result.step(context.getString(R.string.wehavephones_runtime_artifacts_none, label, profile.name));
            }
            return;
        }
        File imageFsDir = WeHavePhonesPaths.imageFsDir(context);
        int errorsBefore = result.errors.size();
        for (WeHavePhonesRuntimeProfiles.Artifact artifact : artifacts) {
            // Later artifacts can depend on earlier ones, so stop at the
            // first failure.
            if (result.errors.size() > errorsBefore) break;
            File target = resolveTarget(imageFsDir, container, profile, artifact.target);
            if (target == null) {
                result.fail(context.getString(R.string.wehavephones_runtime_artifact_invalid_target, label, artifact.target));
                continue;
            }
            byte[] bundled = FileUtils.read(context, "wehavephones/" + artifact.asset);
            if (bundled == null) {
                if (target.isFile()) {
                    result.step(context.getString(R.string.wehavephones_runtime_artifact_already, label, artifact.asset, target.getName()));
                }
                else {
                    result.fail(context.getString(R.string.wehavephones_runtime_artifact_missing_asset, label, artifact.asset, target.getPath()));
                }
                continue;
            }
            String digest = sha256(bundled);
            if (!artifact.sha256.isEmpty() && !digest.equalsIgnoreCase(artifact.sha256)) {
                result.fail(context.getString(R.string.wehavephones_runtime_artifact_sha_mismatch, artifact.asset));
                continue;
            }
            if (target.isFile() && Arrays.equals(FileUtils.read(target), bundled)) {
                result.step(context.getString(R.string.wehavephones_runtime_artifact_current, label, target.getName()));
                continue;
            }
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                result.fail(context.getString(R.string.wehavephones_runtime_artifact_mkdir_failed, parent.getPath()));
                continue;
            }
            if (target.isFile()) {
                byte[] existing = FileUtils.read(target);
                if (existing != null) {
                    FileUtils.write(new File(result.snapshotDir, label + "-" + target.getName() + ".bak"), existing);
                }
            }
            if (!FileUtils.write(target, bundled)) {
                result.fail(context.getString(R.string.wehavephones_runtime_artifact_write_failed, label, target.getPath()));
                continue;
            }
            byte[] readBack = FileUtils.read(target);
            if (readBack == null || !Arrays.equals(readBack, bundled)) {
                result.fail(context.getString(R.string.wehavephones_runtime_artifact_readback_failed, label, target.getPath()));
                continue;
            }
            String shortDigest = digest.length() >= 12 ? digest.substring(0, 12) + "..." : digest;
            result.step(context.getString(R.string.wehavephones_runtime_artifact_deployed, label, target.getName(), shortDigest));
        }
    }

    private static File resolveTarget(File imageFsDir, Container container, WeHavePhonesRuntimeProfiles.Profile profile, String target) {
        if (target.startsWith("container:")) {
            String relative = target.substring("container:".length());
            return new File(container.getRootDir(), relative);
        }
        String resolved = target.replace("{wineVersion}", profile.wineVersion);
        return new File(imageFsDir, resolved);
    }

    private static WowProduct resolveProduct(Context context, Container container, Result result) {
        WowProduct selected = WowInstallationScanner.selected(context, container);
        if (selected == null) {
            result.step("No WoW product detected; wow.bat falls back to " + WeHavePhonesRuntimeProfiles.DEFAULT_PRODUCT_DIR);
        }
        return selected;
    }

    private static String setEnvVar(String envVars, String key, String value) {
        if (envVars == null) envVars = "";
        if (key == null || key.isEmpty() || !key.matches("[A-Za-z_][A-Za-z0-9_]*")) return null;
        if (value == null || value.matches(".*[\\s].*")) return null;

        StringBuilder builder = new StringBuilder();
        String prefix = key + "=";
        for (String token : envVars.split("\\s+")) {
            if (token.isEmpty() || token.startsWith(prefix)) continue;
            if (builder.length() > 0) builder.append(' ');
            builder.append(token);
        }
        if (builder.length() > 0) builder.append(' ');
        builder.append(prefix).append(value);
        return builder.toString();
    }

    private static String applyGraphics(String graphicsConfig, Map<String, String> overrides) {
        java.util.LinkedHashMap<String, String> values = new java.util.LinkedHashMap<>();
        if (graphicsConfig != null) {
            for (String token : graphicsConfig.split(";")) {
                int index = token.indexOf('=');
                if (index <= 0) continue;
                values.put(token.substring(0, index), token.substring(index + 1));
            }
        }
        values.putAll(overrides);
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (builder.length() > 0) builder.append(';');
            builder.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return builder.toString();
    }

    private static void copyIfExists(File source, File target) {
        if (source == null || !source.isFile()) return;
        FileUtils.write(target, FileUtils.read(source));
    }

    private static void pruneSnapshots(File snapshotsDir, int keep) {
        File[] entries = snapshotsDir.listFiles();
        if (entries == null || entries.length <= keep) return;
        Arrays.sort(entries, (first, second) -> first.getName().compareTo(second.getName()));
        for (int i = 0; i < entries.length - keep; i++) FileUtils.delete(entries[i]);
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder builder = new StringBuilder();
            for (byte value : hash) builder.append(String.format("%02x", value));
            return builder.toString();
        }
        catch (Exception e) {
            return "";
        }
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

package com.wehavephones.mobile.devices;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import com.wehavephones.BuildConfig;
import com.wehavephones.core.DefaultVersion;
import com.wehavephones.core.GPUInformation;
import com.wehavephones.core.GpuProbe;
import com.wehavephones.core.GraphicsPlan;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;

/**
 * Turns the device rules into a {@link GraphicsPlan} for this session: match the device to a
 * profile, then try the profile's drivers in order, each through a cached test load, and fall
 * back to the system driver. A driver picked on the Play screen is tried first; if it is not offered,
 * not installed or fails its probe, the session uses the automatic order. Runs in the process that
 * starts the session; the first probe of a driver loads it, later sessions reuse the cached result.
 */
public final class DeviceProfileSelector {
    private static final String TAG = DeviceRulesStore.TAG;
    private static final String KEY_INFLIGHT = "probe_inflight";
    private static final String KEY_INFLIGHT_LABEL = "probe_inflight_label";
    private static final String PROBE_PREFIX = "probe.";
    private static final String KEY_DRIVER_CHOICE = "driver_choice";
    private static final Object LOCK = new Object();

    /** Test-loads one driver; production uses {@link GpuProbe}. */
    public interface Prober {
        GpuProbe.ProbeResult probe(Context context, String driverId);
    }

    /** Makes a driver loadable; production uses {@link DeviceDriverStore}. */
    public interface Availability {
        boolean ensureAvailable(Context context, DeviceRules.Driver driver);
    }

    private DeviceProfileSelector() {}

    /** Never throws and never returns null; call it off the UI thread when a probe may be needed. */
    public static GraphicsPlan select(Context context) {
        return select(context, new Prober() {
            @Override
            public GpuProbe.ProbeResult probe(Context ctx, String driverId) {
                return GpuProbe.probe(ctx, driverId);
            }
        });
    }

    public static GraphicsPlan select(Context context, Prober prober) {
        synchronized (LOCK) {
            GraphicsPlan plan;
            try {
                DeviceRulesStore.Loaded loaded = DeviceRulesStore.load(context);
                DeviceIdentity identity = DeviceIdentity.detect(context);
                plan = select(context, identity, loaded, prober, new Availability() {
                    @Override
                    public boolean ensureAvailable(Context ctx, DeviceRules.Driver driver) {
                        return DeviceDriverStore.ensureAvailable(ctx, driver);
                    }
                });
            }
            catch (RuntimeException e) {
                Log.e(TAG, "device profile selection failed", e);
                plan = legacyPlan(context, e);
            }
            savePlan(context, plan);
            return plan;
        }
    }

    public static GraphicsPlan select(Context context, DeviceIdentity identity, DeviceRulesStore.Loaded loaded,
                                      Prober prober, Availability availability) {
        SharedPreferences preferences = context.getSharedPreferences(DeviceRulesStore.PREFERENCES, Context.MODE_PRIVATE);
        GraphicsPlan plan = new GraphicsPlan();
        recoverCrashedProbe(preferences, plan);

        DeviceRules rules = loaded.rules;
        DeviceRules.Match match = rules.match(identity);
        DeviceRules.Profile profile = match.profile;
        plan.rulesSource = loaded.source;
        plan.rulesVersion = rules.getRulesVersion();
        plan.ruleId = match.rule.id;
        plan.profileName = profile.name;
        plan.profileStatus = profile.status;
        note(plan, "rules " + loaded.source + " v" + rules.getRulesVersion());
        note(plan, "device renderer=\"" + identity.glRenderer + "\" vendor=" + identity.gpuVendor
            + " model=" + identity.adrenoModel + " chip=" + DeviceIdentity.chipIdHex(identity.chipId));
        note(plan, "rule " + match.rule.id + " -> profile " + profile.name + " (" + profile.status + ")");

        DeviceRules.Driver chosen = pickedDriver(context, preferences, profile, prober, availability, plan);
        if (chosen == null && !plan.driverChoice.isEmpty()) note(plan, "automatic driver order");
        for (DeviceRules.Driver driver : profile.driverEntries) {
            // The pick was already tried above, whatever its result.
            if (driver.id.equals(plan.driverChoice)) continue;
            if (chosen != null) {
                note(plan, "probe " + driver.id + "=not-tried");
                continue;
            }
            if (driver.isSystem()) {
                note(plan, "probe " + driver.id + "=not-tried");
                chosen = driver;
                continue;
            }
            if (!availability.ensureAvailable(context, driver)) {
                note(plan, "probe " + driver.id + "=unavailable (not installed and not downloadable now)");
                continue;
            }
            GpuProbe.ProbeResult result = probeCached(context, preferences, driver, prober, plan);
            if (result.ok) chosen = driver;
        }
        if (chosen == null) {
            note(plan, "no listed driver worked; using the system driver");
            chosen = rules.getDriver(DeviceRules.SYSTEM_DRIVER);
        }

        plan.driverId = chosen.id;
        plan.useAdrenotools = !chosen.isSystem();
        plan.env.putAll(profile.env);
        plan.vulkanCompatLayer = profile.vulkanCompatLayer;
        plan.wowApiArg = profile.wowApiArg;
        note(plan, "driver " + plan.driverId + (plan.useAdrenotools ? " (adrenotools)" : " (system)")
            + " compatLayer=" + plan.vulkanCompatLayer + " env=" + plan.env);
        return plan;
    }

    // The Play screen pick when the profile offers it and it loads; null means use the automatic order.
    private static DeviceRules.Driver pickedDriver(Context context, SharedPreferences preferences, DeviceRules.Profile profile,
                                                   Prober prober, Availability availability, GraphicsPlan plan) {
        String choice = preferences.getString(KEY_DRIVER_CHOICE, "");
        if (choice == null || choice.isEmpty()) return null;
        DeviceRules.Driver driver = profile.pickerDriver(choice);
        if (driver == null) {
            note(plan, "driver choice " + choice + " is not offered for profile " + profile.name + "; using Automatic");
            return null;
        }
        plan.driverChoice = driver.id;
        note(plan, "driver choice " + driver.id + " (picked on the Play screen)");
        if (driver.isSystem()) {
            note(plan, "probe " + driver.id + "=not-tried");
            return driver;
        }
        if (!availability.ensureAvailable(context, driver)) {
            note(plan, "probe " + driver.id + "=unavailable (not installed and not downloadable now)");
            note(plan, "driver choice " + driver.id + " is not downloaded; using Automatic");
            return null;
        }
        GpuProbe.ProbeResult result = probeCached(context, preferences, driver, prober, plan);
        if (result.ok) return driver;
        note(plan, "driver choice " + driver.id + " failed to load; using Automatic");
        return null;
    }

    /** The driver picked on the Play screen, or "" for Automatic. */
    public static String driverChoice(Context context) {
        String choice = context.getSharedPreferences(DeviceRulesStore.PREFERENCES, Context.MODE_PRIVATE)
            .getString(KEY_DRIVER_CHOICE, "");
        return choice != null ? choice : "";
    }

    /**
     * Saves the Play screen pick ("" or null for Automatic). Picking a driver again forgets its
     * cached probe result, so a driver that failed once is test-loaded again at the next start.
     */
    public static void setDriverChoice(Context context, DeviceRules.Driver driver) {
        SharedPreferences preferences = context.getSharedPreferences(DeviceRulesStore.PREFERENCES, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        if (driver == null) {
            editor.remove(KEY_DRIVER_CHOICE);
        }
        else {
            editor.putString(KEY_DRIVER_CHOICE, driver.id);
            if (!driver.isSystem()) editor.remove(probeKey(driver));
        }
        editor.commit();
    }

    /**
     * The profile this device matches, without probing or extracting any driver; null when the
     * rules cannot be read. Call it off the UI thread (the first call may query the GL renderer).
     */
    public static DeviceRules.Profile matchedProfile(Context context) {
        try {
            DeviceRules.Match match = DeviceRulesStore.load(context).rules.match(DeviceIdentity.detect(context));
            return match != null ? match.profile : null;
        }
        catch (RuntimeException e) {
            Log.w(TAG, "could not match the device profile: " + e);
            return null;
        }
    }

    /**
     * The driver Automatic uses at the next start, without probing or downloading: the first of the
     * profile's drivers whose cached test load has not failed, else the system driver when the
     * profile offers it. Null without a profile.
     */
    public static DeviceRules.Driver automaticDriver(Context context, DeviceRules.Profile profile) {
        if (profile == null) return null;
        SharedPreferences preferences = context.getSharedPreferences(DeviceRulesStore.PREFERENCES, Context.MODE_PRIVATE);
        for (DeviceRules.Driver driver : profile.driverEntries) {
            if (driver.isSystem()) return driver;
            String cached = preferences.getString(probeKey(driver), null);
            if (cached == null || GpuProbe.ProbeResult.fromJson(cached).ok) return driver;
        }
        return profile.pickerDriver(DeviceRules.SYSTEM_DRIVER);
    }

    /** The WoW graphics API argument of the matched profile (for example "-d3d11"); null keeps "-d3d12". */
    public static String wowApiArg(Context context) {
        DeviceRules.Profile profile = matchedProfile(context);
        return profile != null ? profile.wowApiArg : null;
    }

    /** The plan of the most recent session on this device, or null. */
    public static GraphicsPlan lastPlan(Context context) {
        File file = lastPlanFile(context);
        if (!file.isFile()) return null;
        try (InputStream in = new FileInputStream(file)) {
            return GraphicsPlan.fromJson(new String(DeviceRulesStore.readAll(in, 256 * 1024), StandardCharsets.UTF_8));
        }
        catch (IOException e) {
            return null;
        }
    }

    /** Forget all cached probe results so every driver is test-loaded again. */
    public static void clearProbeCache(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(DeviceRulesStore.PREFERENCES, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            if (entry.getKey().startsWith(PROBE_PREFIX)) editor.remove(entry.getKey());
        }
        editor.commit();
    }

    /** "driverA=ok; system=not-tried" for the device report, from the plan's probe lines. */
    public static String driverProbes(GraphicsPlan plan) {
        StringBuilder text = new StringBuilder();
        for (String line : plan.log) {
            if (!line.startsWith("probe ")) continue;
            String entry = line.substring("probe ".length());
            int detail = entry.indexOf(' ');
            if (detail >= 0) entry = entry.substring(0, detail);
            if (text.length() > 0) text.append("; ");
            text.append(entry);
        }
        return text.toString();
    }

    private static GpuProbe.ProbeResult probeCached(Context context, SharedPreferences preferences,
                                                    DeviceRules.Driver driver, Prober prober, GraphicsPlan plan) {
        String key = probeKey(driver);
        String cached = preferences.getString(key, null);
        if (cached != null) {
            GpuProbe.ProbeResult result = GpuProbe.ProbeResult.fromJson(cached);
            note(plan, "probe " + driver.id + "=" + (result.ok ? "ok" : "failed") + " (cached" + detail(result) + ")");
            return result;
        }
        // A native crash during the probe kills the process; the marker lets the next run see it.
        preferences.edit().putString(KEY_INFLIGHT, key).putString(KEY_INFLIGHT_LABEL, driver.id).commit();
        GpuProbe.ProbeResult result;
        try {
            result = prober.probe(context, driver.id);
        }
        catch (RuntimeException e) {
            result = new GpuProbe.ProbeResult();
            result.stage = "probe";
            result.error = "probe threw " + e;
        }
        if (result == null) {
            result = new GpuProbe.ProbeResult();
            result.stage = "probe";
            result.error = "no probe result";
        }
        preferences.edit().putString(key, result.toJson()).remove(KEY_INFLIGHT).remove(KEY_INFLIGHT_LABEL).commit();
        note(plan, "probe " + driver.id + "=" + (result.ok ? "ok" : "failed") + " (" + detail(result).substring(2) + ")");
        return result;
    }

    private static String probeKey(DeviceRules.Driver driver) {
        return PROBE_PREFIX + sha256Hex(driver.id + "|" + driver.sha256 + "|" + Build.FINGERPRINT + "|" + BuildConfig.VERSION_CODE);
    }

    /** The cached probe result of a driver under the current rules, or null (system, never probed, unknown id). */
    public static GpuProbe.ProbeResult cachedProbe(Context context, String driverId) {
        try {
            DeviceRules.Driver driver = DeviceRulesStore.load(context).rules.getDriver(driverId);
            if (driver == null || driver.isSystem()) return null;
            SharedPreferences preferences = context.getSharedPreferences(DeviceRulesStore.PREFERENCES, Context.MODE_PRIVATE);
            String cached = preferences.getString(probeKey(driver), null);
            return cached != null ? GpuProbe.ProbeResult.fromJson(cached) : null;
        }
        catch (RuntimeException e) {
            return null;
        }
    }

    private static void recoverCrashedProbe(SharedPreferences preferences, GraphicsPlan plan) {
        String key = preferences.getString(KEY_INFLIGHT, null);
        if (key == null) return;
        String label = preferences.getString(KEY_INFLIGHT_LABEL, "?");
        GpuProbe.ProbeResult result = new GpuProbe.ProbeResult();
        result.stage = "crash";
        result.error = "crashed during probe";
        preferences.edit().putString(key, result.toJson()).remove(KEY_INFLIGHT).remove(KEY_INFLIGHT_LABEL).commit();
        note(plan, "driver " + label + " crashed during an earlier probe; marked failed");
    }

    private static String detail(GpuProbe.ProbeResult result) {
        StringBuilder text = new StringBuilder(", ");
        if (!result.stage.isEmpty()) text.append("stage ").append(result.stage);
        if (!result.deviceName.isEmpty()) text.append(text.length() > 2 ? ", " : "").append(result.deviceName);
        if (!result.driverInfo.isEmpty()) text.append(text.length() > 2 ? ", " : "").append(result.driverInfo);
        if (!result.error.isEmpty()) text.append(text.length() > 2 ? ", " : "").append(result.error);
        if (text.length() == 2) text.append("no detail");
        return text.toString();
    }

    // What the pre-rules code did: possibly-Adreno devices get the pinned Turnip, others the system driver with BCn emulation.
    private static GraphicsPlan legacyPlan(Context context, RuntimeException cause) {
        GraphicsPlan plan = new GraphicsPlan();
        plan.ruleId = "legacy-fallback";
        plan.profileName = "legacy";
        plan.profileStatus = "untested";
        plan.rulesSource = "none";
        note(plan, "rules unavailable (" + cause + "); using the built-in defaults");
        boolean adreno;
        try {
            adreno = GPUInformation.mayBeAdreno(context);
        }
        catch (RuntimeException e) {
            adreno = true;
        }
        if (adreno) {
            plan.driverId = "turnip" + DefaultVersion.ADRENOTOOLS_TURNIP;
            plan.useAdrenotools = true;
        }
        else {
            plan.vulkanCompatLayer = true;
        }
        return plan;
    }

    private static void note(GraphicsPlan plan, String line) {
        plan.log.add(line);
        Log.i(TAG, line);
    }

    private static File lastPlanFile(Context context) {
        return new File(new File(new File(context.getFilesDir(), "wehavephones"), "devices"), "last-plan.json");
    }

    private static void savePlan(Context context, GraphicsPlan plan) {
        File file = lastPlanFile(context);
        File dir = file.getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) return;
        File part = new File(file.getPath() + ".part");
        try (OutputStream out = new FileOutputStream(part)) {
            out.write(plan.toJson().getBytes(StandardCharsets.UTF_8));
        }
        catch (IOException e) {
            part.delete();
            return;
        }
        if (file.exists()) file.delete();
        if (!part.renameTo(file)) part.delete();
    }

    private static String sha256Hex(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte value : hash) builder.append(String.format(Locale.US, "%02x", value));
            return builder.toString();
        }
        catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

package com.wehavephones.mobile.devices;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.wehavephones.core.DefaultVersion;
import com.wehavephones.core.GPUInformation;
import com.wehavephones.core.GpuProbe;
import com.wehavephones.core.GraphicsPlan;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Host tests for the device rules: parsing, matching, remote acceptance, driver install and selection. */
public final class DeviceRulesSelfTest {
    private static int checks;
    private static final ArrayList<String> failures = new ArrayList<>();
    private static File workRoot;
    private static String bundledJson;
    private static int bundledVersion;
    private static int counter;

    private static final String TURNIP = "turnip26.3.0-devel-1b51639c20";
    private static final String GEN8 = "turnip-gen8-stevenmxz-v37";
    private static final String V32 = "turnip-v32-rp6sched";
    private static final String RUTABAGA = "turnip-gamesir-rutabaga-v3";
    private static final String[] BANNERS = {
        "turnip-banner-26.3.0-20261015-r4-a8xx", "turnip-banner-26.3.0-20261015-r5-a8xx",
        "turnip-banner-26.3.0-20261015-r6-a8xx", "turnip-banner-26.3.0-20261015-r7-a8xx",
        "turnip-banner-27.0.0-20261016-r3-a8xx", "turnip-banner-27.0.0-20261016-r4-a8xx"
    };

    public static void main(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("usage: DeviceRulesSelfTest <device-rules.json> <v6-baseline.json>");
        bundledJson = new String(Files.readAllBytes(new File(args[0]).toPath()), StandardCharsets.UTF_8);
        bundledVersion = new JSONObject(bundledJson).getInt("rulesVersion");
        workRoot = Files.createTempDirectory("device-rules-selftest").toFile();
        try {
            testIdentity();
            testParseValidation();
            testBundledMatching();
            testOptionalTurnipUpdate(new String(Files.readAllBytes(new File(args[1]).toPath()), StandardCharsets.UTF_8));
            testCriteriaSemantics();
            testRemoteAcceptance();
            testCachedRulesRevalidated();
            testDriverStore();
            testSelector();
            testDriverChoice();
            testEquivalence();
            testGraphicsPlanJson();
        }
        finally {
            deleteRecursively(workRoot);
        }
        if (!failures.isEmpty()) {
            for (String failure : failures) System.out.println("FAIL: " + failure);
            System.out.println(failures.size() + " of " + checks + " checks failed");
            System.exit(1);
        }
        System.out.println("DeviceRulesSelfTest OK (" + checks + " checks)");
    }

    // ---- helpers ----

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) failures.add(message);
    }

    private static void eq(Object expected, Object actual, String message) {
        checks++;
        boolean same = expected == null ? actual == null : expected.equals(actual);
        if (!same) failures.add(message + ": expected <" + expected + "> but was <" + actual + ">");
    }

    private static Context newContext() throws IOException {
        File dir = new File(workRoot, "ctx" + (counter++));
        File assets = new File(dir, "assets");
        File rules = new File(assets, "wehavephones/device-rules.json");
        rules.getParentFile().mkdirs();
        Files.write(rules.toPath(), bundledJson.getBytes(StandardCharsets.UTF_8));
        File files = new File(dir, "files");
        files.mkdirs();
        return new Context(files, assets);
    }

    private static DeviceIdentity identity(String renderer, long chipId) {
        DeviceIdentity id = new DeviceIdentity();
        id.glRenderer = renderer;
        id.gpuVendor = DeviceIdentity.vendorFromRenderer(renderer);
        id.adrenoModel = DeviceIdentity.adrenoModelFromRenderer(renderer);
        id.chipId = chipId;
        id.manufacturer = "samsung";
        id.sdkInt = 36;
        id.socModel = "SM8550";
        id.fingerprint = "test/fingerprint";
        return id;
    }

    private interface Edit {
        void apply(JSONObject root) throws Exception;
    }

    private static String mutate(Edit edit) throws Exception {
        JSONObject root = new JSONObject(bundledJson);
        edit.apply(root);
        return root.toString();
    }

    private static DeviceRules parseBundled() throws Exception {
        return DeviceRules.parse(bundledJson);
    }

    private static boolean rejects(String json) {
        try {
            DeviceRules.parse(json);
            return false;
        }
        catch (Exception e) {
            return true;
        }
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null && !Files.isSymbolicLink(file.toPath())) for (File child : children) deleteRecursively(child);
        file.delete();
    }

    private static String sha256(byte[] data) throws Exception {
        StringBuilder text = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(data)) text.append(String.format("%02x", b));
        return text.toString();
    }

    // ---- identity ----

    private static void testIdentity() {
        eq("adreno", DeviceIdentity.vendorFromRenderer("Adreno (TM) 740"), "vendor adreno");
        eq("adreno", DeviceIdentity.vendorFromRenderer("ANGLE (Qualcomm, Adreno (TM) 650, OpenGL ES 3.2)"), "vendor adreno via ANGLE");
        eq("mali", DeviceIdentity.vendorFromRenderer("Mali-G715"), "vendor mali");
        eq("mali", DeviceIdentity.vendorFromRenderer("Immortalis-G720"), "vendor immortalis is mali family");
        eq("xclipse", DeviceIdentity.vendorFromRenderer("Samsung Xclipse 940"), "vendor xclipse");
        eq("powervr", DeviceIdentity.vendorFromRenderer("PowerVR Rogue GE8320"), "vendor powervr");
        eq("other", DeviceIdentity.vendorFromRenderer("llvmpipe (LLVM 15)"), "vendor other");
        eq("unknown", DeviceIdentity.vendorFromRenderer(""), "vendor empty");
        eq("unknown", DeviceIdentity.vendorFromRenderer(null), "vendor null");
        eq(740, DeviceIdentity.adrenoModelFromRenderer("Adreno (TM) 740"), "model 740");
        eq(840, DeviceIdentity.adrenoModelFromRenderer("Adreno (TM) 840"), "model 840");
        eq(0, DeviceIdentity.adrenoModelFromRenderer("Adreno"), "model none");
        eq(0, DeviceIdentity.adrenoModelFromRenderer("Mali-G715"), "model mali");
        eq("0x44050A21", DeviceIdentity.chipIdHex(0x44050A21L), "chip hex");
        eq("", DeviceIdentity.chipIdHex(-1), "chip hex unavailable");

        DeviceIdentity id = identity("Adreno (TM) 840", 0x44050A21L);
        id.kgslModel = "Adreno840v2";
        id.socManufacturer = "QTI";
        id.socModel = "SM8850";
        id.manufacturer = "OnePlus";
        id.model = "CPH2747";
        id.release = "16";
        id.sdkInt = 36;
        String report = id.toReportText();
        check(report.contains("gl_renderer: Adreno (TM) 840\n"), "report renderer");
        check(report.contains("gpu_vendor: adreno\n"), "report vendor");
        check(report.contains("adreno_model: 840\n"), "report model");
        check(report.contains("chip_id: 0x44050A21\n"), "report chip");
        check(report.contains("kgsl_model: Adreno840v2\n"), "report kgsl");
        check(report.contains("soc: QTI SM8850\n"), "report soc");
        check(report.contains("device: OnePlus CPH2747\n"), "report device");
        check(report.contains("android: 16 (SDK 36)\n"), "report android");
        check(report.contains("fingerprint: test/fingerprint\n"), "report fingerprint");
        eq(840, id.toJson().optInt("adrenoModel"), "identity json model");
    }

    // ---- parsing and validation ----

    private static void testParseValidation() throws Exception {
        DeviceRules rules = parseBundled();
        eq(bundledVersion, rules.getRulesVersion(), "bundled rulesVersion");
        eq(1, rules.getSchema(), "bundled schema");
        check(rules.getDriver("system").isSystem(), "system driver");
        eq("vulkan.ad07xx.so", rules.getDriver(TURNIP).libraryName, "turnip library");
        eq("tzst", rules.getDriver(TURNIP).archiveKind(), "archive kind");
        eq("catch-all", rules.getRules().get(rules.getRules().size() - 1).id, "catch-all last");

        check(rejects("not json"), "garbage rejected");
        check(rejects(mutate(r -> r.put("schema", 2))), "newer schema rejected");
        check(rejects(mutate(r -> r.put("schema", "1"))), "string schema rejected");
        check(rejects(mutate(r -> r.put("rulesVersion", 0))), "rulesVersion 0 rejected");
        check(rejects(mutate(r -> r.put("rulesVersion", "2"))), "string rulesVersion rejected");
        check(rejects(mutate(r -> r.getJSONObject("drivers").remove("system"))), "no system driver rejected");
        check(rejects(mutate(r -> r.getJSONArray("rules").getJSONObject(0).put("profile", "nope"))), "unknown profile rejected");
        check(rejects(mutate(r -> r.getJSONObject("profiles").getJSONObject("mali").put("drivers", new JSONArray().put("nope")))), "unknown driver rejected");
        check(rejects(mutate(r -> r.getJSONObject("profiles").getJSONObject("mali").put("drivers", new JSONArray()))), "empty driver list rejected");
        check(rejects(mutate(r -> r.getJSONObject("profiles").getJSONObject("mali").put("status", "great"))), "unknown status rejected");
        check(rejects(mutate(r -> r.getJSONArray("rules").remove(r.getJSONArray("rules").length() - 1))), "missing catch-all rejected");
        check(rejects(mutate(r -> r.getJSONArray("rules").getJSONObject(1).put("id", "adreno-84x"))), "duplicate rule id rejected");
        check(rejects(mutate(r -> r.getJSONArray("rules").getJSONObject(1).getJSONObject("match").put("chipId", new JSONArray().put("0x123")))), "short chip id rejected");
        check(rejects(mutate(r -> r.getJSONArray("rules").getJSONObject(1).getJSONObject("match").put("chipId", new JSONArray().put("44050A21")))), "chip id without 0x rejected");
        check(rejects(mutate(r -> r.getJSONArray("rules").getJSONObject(0).getJSONObject("match").put("adrenoModel", new JSONArray().put("849-840")))), "reversed range rejected");
        check(rejects(mutate(r -> r.getJSONArray("rules").getJSONObject(0).getJSONObject("match").put("adrenoModel", new JSONArray().put("84")))), "short model rejected");
        check(rejects(mutate(r -> r.getJSONArray("rules").getJSONObject(0).getJSONObject("match").put("gpuVendor", new JSONArray().put("nvidia")))), "unknown vendor rejected");
        check(rejects(mutate(r -> r.getJSONObject("drivers").getJSONObject(TURNIP).put("sha256", "abc"))), "bad sha256 rejected");
        check(rejects(mutate(r -> r.getJSONObject("drivers").getJSONObject(TURNIP).put("asset", "../x.tzst"))), "traversal asset rejected");
        check(rejects(mutate(r -> r.getJSONObject("drivers").getJSONObject(TURNIP).put("asset", JSONObject.NULL))), "driver without asset or url rejected");
        check(rejects(mutate(r -> r.getJSONObject("drivers").getJSONObject(TURNIP).put("url", "http://example.com/a.zip"))), "http url rejected");
        check(rejects(mutate(r -> r.getJSONObject("profiles").getJSONObject("adreno-7xx").getJSONObject("env").put("LD_PRELOAD", "x"))), "LD_ env rejected");
        check(rejects(mutate(r -> r.getJSONObject("profiles").getJSONObject("adreno-7xx").getJSONObject("env").put("TU_DEBUG", "a b"))), "env with space rejected");
        check(rejects(mutate(r -> r.getJSONObject("profiles").getJSONObject("mali").put("vulkanCompatLayer", "yes"))), "string boolean rejected");
        check(rejects(mutate(r -> r.getJSONObject("profiles").getJSONObject("mali").put("wowApiArg", "d3d11 --evil"))), "bad wowApiArg rejected");
        check(!rejects(mutate(r -> r.put("futureKey", "ignored"))), "unknown top-level key ignored");
        check(!rejects(mutate(r -> r.getJSONObject("profiles").getJSONObject("mali").put("wowApiArg", "-d3d11"))), "wowApiArg -d3d11 accepted");
        check(!rejects(mutate(r -> r.getJSONObject("profiles").getJSONObject("mali").put("futureField", 1))), "unknown profile key ignored");

        // Driver picker fields.
        check(rejects(mutate(r -> r.getJSONObject("profiles").getJSONObject("adreno-7xx").put("choices", new JSONArray().put("nope")))), "unknown choice rejected");
        check(rejects(mutate(r -> r.getJSONObject("profiles").getJSONObject("adreno-7xx").put("choices", new JSONArray().put(TURNIP)))), "choice already in drivers rejected");
        check(rejects(mutate(r -> r.getJSONObject("profiles").getJSONObject("adreno-7xx").put("choices", new JSONArray().put(GEN8).put(GEN8)))), "duplicate choice rejected");
        check(rejects(mutate(r -> r.getJSONObject("profiles").getJSONObject("adreno-7xx").put("choices", GEN8))), "choices must be an array");
        DeviceRules picker = DeviceRules.parse(mutate(r ->
            r.getJSONObject("profiles").getJSONObject("adreno-7xx").put("choices", new JSONArray().put(GEN8).put(V32))));
        DeviceRules.Profile s23 = picker.getProfile("adreno-7xx");
        eq(java.util.Arrays.asList(TURNIP, "system", GEN8, V32), ids(s23.pickerDrivers()), "picker lists drivers, then choices");
        check(s23.pickerDriver("nope") == null, "unknown picker id");
        eq(java.util.Arrays.asList("system"), ids(picker.getProfile("mali").pickerDrivers()), "Mali offers only the system driver");
    }

    /**
     * The v6 catalog plus the intended graphics API changes: Adreno 7xx runs WoW in D3D11 since v8
     * and every other profile that had no flag since v9. Applying them to the baseline keeps every
     * other v6 default frozen.
     */
    private static String withD3D11ApiChange(String v6Json) throws Exception {
        JSONObject baseline = new JSONObject(v6Json);
        JSONObject bundledProfiles = new JSONObject(bundledJson).getJSONObject("profiles");
        JSONObject profiles = baseline.getJSONObject("profiles");
        for (String name : new String[] {"adreno-7xx", "adreno-6xx", "adreno-other", "mali", "non-adreno-other", "unknown-gpu"}) {
            JSONObject bundled = bundledProfiles.getJSONObject(name);
            eq("-d3d11", bundled.getString("wowApiArg"), name + " runs WoW with D3D11");
            JSONObject old = profiles.getJSONObject(name);
            check(old.isNull("wowApiArg"), "v6 " + name + " kept the default graphics API");
            old.put("wowApiArg", "-d3d11");
            old.put("notes", bundled.getString("notes"));
        }
        return baseline.toString();
    }

    private static java.util.List<String> ids(java.util.List<DeviceRules.Driver> drivers) {
        ArrayList<String> list = new ArrayList<>();
        for (DeviceRules.Driver driver : drivers) list.add(driver.id);
        return list;
    }

    // The exact committed v6 catalog freezes every pre-existing default, rule and saved driver id.
    private static void testOptionalTurnipUpdate(String v6Json) throws Exception {
        eq(6, new JSONObject(v6Json).getInt("rulesVersion"), "baseline is the pre-update v6 catalog");
        String baselineJson = withD3D11ApiChange(v6Json);
        JSONObject before = new JSONObject(baselineJson);
        JSONObject after = new JSONObject(bundledJson);
        DeviceRules oldRules = DeviceRules.parse(baselineJson);
        DeviceRules newRules = parseBundled();
        check(after.getInt("rulesVersion") > 6, "optional update bumps rulesVersion");
        JSONObject oldHeader = new JSONObject(baselineJson);
        JSONObject newHeader = new JSONObject(bundledJson);
        for (String key : new String[] {"rulesVersion", "updatedAt", "drivers", "profiles"}) {
            oldHeader.remove(key);
            newHeader.remove(key);
        }
        check(oldHeader.similar(newHeader), "all rule matching, schema and app-version requirements unchanged");
        JSONObject oldDrivers = before.getJSONObject("drivers");
        JSONObject newDrivers = after.getJSONObject("drivers");
        for (String id : oldDrivers.keySet()) {
            check(oldDrivers.getJSONObject(id).similar(newDrivers.getJSONObject(id)), "existing driver unchanged: " + id);
        }
        eq(oldDrivers.length() + 7, newDrivers.length(), "exactly seven requested releases added");
        JSONObject oldProfiles = before.getJSONObject("profiles");
        JSONObject newProfiles = after.getJSONObject("profiles");
        eq(oldProfiles.keySet(), newProfiles.keySet(), "no profiles added or removed");

        // Exercise the real matcher over all numeric Adreno models, plus every other vendor below.
        for (int model = 0; model < 1000; model++) {
            DeviceIdentity id = identity("Adreno (TM) " + model, -1);
            eq(oldRules.match(id).rule.id, newRules.match(id).rule.id, "unchanged rule for model " + model);
            eq(oldRules.match(id).profile.name, newRules.match(id).profile.name, "unchanged profile for model " + model);
        }
        Map<String, DeviceIdentity> identities = new HashMap<>();
        identities.put("adreno-6xx", identity("Adreno (TM) 650", -1));
        identities.put("adreno-7xx", identity("Adreno (TM) 740", -1));
        identities.put("adreno-8xx", identity("Adreno (TM) 830", -1));
        identities.put("adreno-84x", identity("Adreno (TM) 840", 0x44050A31L));
        identities.put("adreno-other", identity("Adreno (TM)", -1));
        identities.put("mali", identity("Mali-G715", -1));
        identities.put("non-adreno-other", identity("Samsung Xclipse 940", -1));
        identities.put("unknown-gpu", identity("", -1));
        eq(oldProfiles.keySet(), identities.keySet(), "regression exercises every existing profile");
        for (String name : oldProfiles.keySet()) {
            JSONObject oldProfile = new JSONObject(oldProfiles.getJSONObject(name).toString());
            JSONObject newProfile = new JSONObject(newProfiles.getJSONObject(name).toString());
            oldProfile.remove("choices");
            newProfile.remove("choices");
            check(oldProfile.similar(newProfile), "every profile default and ordered automatic list unchanged: " + name);
            DeviceRules.Profile oldP = oldRules.getProfile(name);
            DeviceRules.Profile newP = newRules.getProfile(name);
            ArrayList<String> expected = new ArrayList<>(ids(oldP.pickerDrivers()));
            boolean a8xx = name.equals("adreno-8xx") || name.equals("adreno-84x");
            if (a8xx || name.equals("adreno-6xx") || name.equals("adreno-7xx")) expected.add(RUTABAGA);
            if (a8xx) java.util.Collections.addAll(expected, BANNERS);
            eq(expected, ids(newP.pickerDrivers()), "exact optional applicability and old picker order: " + name);
            DeviceIdentity id = identities.get(name);
            eq(name, newRules.match(id).profile.name, "representative matches " + name);
            eq(DeviceProfileSelector.automaticDriver(newContext(), oldP).id,
                DeviceProfileSelector.automaticDriver(newContext(), newP).id, "picker Automatic unchanged: " + name);

            // Fail each prefix to cover every fallback position, with all optional choices available.
            for (int prefix = 0; prefix <= oldP.drivers.size(); prefix++) {
                FakeProber oldProbe = new FakeProber();
                FakeProber newProbe = new FakeProber();
                for (int i = 0; i < prefix; i++) {
                    oldProbe.outcomes.put(oldP.drivers.get(i), false);
                    newProbe.outcomes.put(oldP.drivers.get(i), false);
                }
                GraphicsPlan oldPlan = DeviceProfileSelector.select(newContext(), id,
                    new DeviceRulesStore.Loaded(oldRules, "bundled"), oldProbe, available());
                GraphicsPlan newPlan = DeviceProfileSelector.select(newContext(), id,
                    new DeviceRulesStore.Loaded(newRules, "bundled"), newProbe, available());
                samePlan(oldPlan, newPlan, name + " fallback " + prefix);
                eq(oldProbe.calls, newProbe.calls, "no optional probes in Automatic: " + name + " fallback " + prefix);
            }
            FakeProber missingProbe = new FakeProber();
            GraphicsPlan missing = DeviceProfileSelector.select(newContext(), id,
                new DeviceRulesStore.Loaded(newRules, "bundled"), missingProbe,
                available(oldP.drivers.toArray(new String[0])));
            eq("system", missing.driverId, "missing defaults never auto-select an optional driver: " + name);
            check(missingProbe.calls.isEmpty(), "missing defaults never probe optional choices: " + name);

            // Save using the old catalog, then resolve using the new catalog without re-saving.
            for (DeviceRules.Driver driver : oldP.pickerDrivers()) {
                Context ctx = newContext();
                DeviceProfileSelector.setDriverChoice(ctx, driver);
                GraphicsPlan plan = DeviceProfileSelector.select(ctx, id,
                    new DeviceRulesStore.Loaded(newRules, "bundled"), new FakeProber(), available());
                eq(driver.id, plan.driverId, "existing saved pick still used: " + name + "/" + driver.id);
                eq(driver.id, DeviceProfileSelector.driverChoice(ctx), "saved preference preserved: " + name + "/" + driver.id);
                eq(oldP.env, plan.env, "saved pick preserves environment: " + name);
                eq(oldP.wowApiArg, plan.wowApiArg, "saved pick preserves graphics API: " + name);
                eq(oldP.vulkanCompatLayer, plan.vulkanCompatLayer, "saved pick preserves compat layer: " + name);
            }
            for (DeviceRules.Driver driver : newP.choiceEntries) {
                if (oldDrivers.has(driver.id)) continue;
                check(driver.description.contains("Experimental") && driver.description.contains("unverified"),
                    "experimental compatibility warning: " + driver.id);
                Context ctx = newContext();
                DeviceProfileSelector.setDriverChoice(ctx, driver);
                GraphicsPlan plan = DeviceProfileSelector.select(ctx, id,
                    new DeviceRulesStore.Loaded(newRules, "bundled"), new FakeProber(), available());
                eq(driver.id, plan.driverId, "new explicit pick works: " + name + "/" + driver.id);
                eq(oldP.env, plan.env, "new pick preserves environment: " + name);
                eq(oldP.wowApiArg, plan.wowApiArg, "new pick preserves graphics API: " + name);
                eq(oldP.vulkanCompatLayer, plan.vulkanCompatLayer, "new pick preserves compat layer: " + name);
                Context failed = newContext();
                DeviceProfileSelector.setDriverChoice(failed, driver);
                FakeProber failureProbe = new FakeProber();
                failureProbe.outcomes.put(driver.id, false);
                GraphicsPlan fallback = DeviceProfileSelector.select(failed, id,
                    new DeviceRulesStore.Loaded(newRules, "bundled"), failureProbe, available());
                GraphicsPlan baseline = DeviceProfileSelector.select(newContext(), id,
                    new DeviceRulesStore.Loaded(oldRules, "bundled"), new FakeProber(), available());
                baseline.driverChoice = driver.id;
                samePlan(baseline, fallback, "failed new choice returns to unchanged defaults: " + name + "/" + driver.id);
                eq(driver.id, DeviceProfileSelector.driverChoice(failed), "failed choice does not rewrite the saved pick");
            }
        }
        // Optional entries never participate in startup prefetch.
        DeviceDriverStore.Downloader savedDownloader = DeviceDriverStore.downloader;
        try {
            DeviceDriverStore.downloader = (url, target, max) -> {
                for (String id : newDrivers.keySet()) {
                    if (!oldDrivers.has(id)) check(!url.equals(newRules.getDriver(id).url), "no optional prefetch: " + id);
                }
                throw new IOException("offline regression fixture");
            };
            for (String name : oldProfiles.keySet()) DeviceDriverStore.prefetchNow(newContext(), newRules.getProfile(name));
        }
        finally {
            DeviceDriverStore.downloader = savedDownloader;
        }
    }

    private static void samePlan(GraphicsPlan before, GraphicsPlan after, String message) throws Exception {
        JSONObject oldPlan = new JSONObject(before.toJson());
        JSONObject newPlan = new JSONObject(after.toJson());
        for (String key : new String[] {"rulesVersion", "log"}) {
            oldPlan.remove(key);
            newPlan.remove(key);
        }
        check(oldPlan.similar(newPlan), "all graphics plan defaults unchanged: " + message);
    }

    // ---- matching ----

    private static DeviceRules.Match match(String renderer, long chip) throws Exception {
        return parseBundled().match(identity(renderer, chip));
    }

    private static void testBundledMatching() throws Exception {
        eq("adreno-7xx", match("Adreno (TM) 740", -1).rule.id, "S23 rule");
        eq("adreno-7xx", match("Adreno (TM) 740", -1).profile.name, "S23 profile");
        eq("tested", match("Adreno (TM) 740", -1).profile.status, "S23 status");
        eq("mali", match("Mali-G715", -1).rule.id, "Pixel 8a rule");
        eq("mali", match("Mali-G715", -1).profile.name, "Pixel 8a profile");
        eq("adreno-84x", match("Adreno (TM) 840", 0x44050A31L).rule.id, "OnePlus 15 rule");
        eq("adreno-84x", match("Adreno (TM) 840", 0x44050A31L).profile.name, "OnePlus 15 profile");
        eq("adreno-84x", match("Adreno (TM) 840", 0x44050A21L).profile.name, "A840 0x44050A21 profile");
        eq("adreno-84x", match("Adreno (TM) 840", -1).profile.name, "A840 without chip id");
        eq("adreno-84x-chip", match("Adreno (TM)", 0x44050A21L).rule.id, "chip-only match with unknown renderer model");
        eq("adreno-84x-chip", match("", 0x44050A31L).rule.id, "chip-only match with empty renderer");
        eq("adreno-8xx", match("Adreno (TM) 830", 0x44050001L).rule.id, "A830 rule");
        eq("adreno-8xx", match("Adreno (TM) 830", -1).profile.name, "A830 profile");
        eq("adreno-8xx", match("Adreno (TM) 825", 0x44030000L).rule.id, "A825 rule (Lenovo TB390FU, issue #17)");
        eq("adreno-8xx", match("Adreno (TM) 810", -1).rule.id, "A810 rule");
        eq("adreno-6xx", match("Adreno (TM) 650", -1).rule.id, "A650 rule");
        eq("adreno-other", match("Adreno (TM)", -1).rule.id, "Adreno without model");
        eq("catch-all", match("", -1).rule.id, "empty renderer catch-all");
        eq("unknown-gpu", match("", -1).profile.name, "empty renderer profile");
        eq("non-adreno-other", match("Samsung Xclipse 940", -1).rule.id, "Xclipse rule");
        eq("non-adreno-other", match("PowerVR Rogue", -1).rule.id, "PowerVR rule");
        eq("non-adreno-other", match("llvmpipe", -1).rule.id, "other rule");
        eq("mali", match("Immortalis-G720", -1).rule.id, "Immortalis rule");

        DeviceRules rules = parseBundled();
        DeviceRules.Profile p84 = rules.getProfile("adreno-84x");
        // Every Turnip build drew glitches on a OnePlus 15 (issue #13); they stay in the picker.
        eq(java.util.Arrays.asList("system"), p84.drivers, "84x uses the system driver");
        eq(java.util.Arrays.asList(V32, GEN8), ids(p84.choiceEntries.subList(0, 2)), "84x picker offers V32 and Gen 8 first");
        eq("-d3d11", p84.wowApiArg, "84x wowApiArg");
        DeviceRules.Profile p8xx = rules.getProfile("adreno-8xx");
        eq(V32, p8xx.drivers.get(0), "8xx first driver");
        eq(GEN8, p8xx.drivers.get(1), "8xx second driver");
        eq(TURNIP, p8xx.drivers.get(2), "8xx third driver");
        eq("noconform,sysmem", p8xx.env.get("TU_DEBUG"), "8xx TU_DEBUG");
        eq("-d3d11", p8xx.wowApiArg, "8xx wowApiArg");
        eq("community", p8xx.status, "8xx status");
        // V32 is downloaded, never bundled, and stays out of the profiles of GPUs it draws broken on.
        DeviceRules.Driver v32 = rules.getDriver(V32);
        check(v32.asset == null && v32.url != null, "V32 is a url driver");
        eq("zip", v32.archiveKind(), "V32 archive kind");
        check(!rules.getProfile("adreno-7xx").drivers.contains(V32), "V32 not used on Adreno 7xx");
        // Every rule has a source note.
        for (DeviceRules.Rule rule : rules.getRules()) check(!rule.source.isEmpty(), "rule " + rule.id + " has a source");
    }

    private static DeviceRules singleRule(String matchJson) throws Exception {
        String json = mutate(r -> {
            JSONArray list = new JSONArray();
            list.put(new JSONObject("{\"id\":\"test\",\"match\":" + matchJson + ",\"profile\":\"mali\",\"source\":\"t\"}"));
            list.put(new JSONObject("{\"id\":\"catch-all\",\"match\":{},\"profile\":\"unknown-gpu\",\"source\":\"t\"}"));
            r.put("rules", list);
        });
        return DeviceRules.parse(json);
    }

    private static boolean matches(DeviceRules rules, DeviceIdentity id) {
        return "test".equals(rules.match(id).rule.id);
    }

    private static void testCriteriaSemantics() throws Exception {
        DeviceRules wildcard = singleRule("{\"chipId\":[\"0x44050AFF\"]}");
        check(matches(wildcard, identity("", 0x44050A21L)), "FF wildcard matches 0x44050A21");
        check(matches(wildcard, identity("", 0x44050A31L)), "FF wildcard matches 0x44050A31");
        check(matches(wildcard, identity("", 0x44050AFFL)), "FF wildcard matches itself");
        check(!matches(wildcard, identity("", 0x44050B21L)), "FF wildcard does not match another byte");
        check(!matches(wildcard, identity("", 0x45050A21L)), "FF wildcard keeps the other bytes");
        check(!matches(wildcard, identity("", -1)), "unknown chip id never matches");
        check(!matches(wildcard, identity("", 0xFFFF44050A21L)), "bytes above the pattern must be zero");

        DeviceRules exact = singleRule("{\"chipId\":[\"0x44050A21\"]}");
        check(matches(exact, identity("", 0x44050A21L)), "exact chip matches");
        check(!matches(exact, identity("", 0x44050A31L)), "exact chip does not match a sibling");

        DeviceRules wide = singleRule("{\"chipId\":[\"0xFFFF44050A31\"]}");
        check(matches(wide, identity("", 0x44050A31L)), "leading FF bytes are wildcards");

        DeviceRules series = singleRule("{\"adrenoSeries\":[7,8]}");
        check(matches(series, identity("Adreno (TM) 740", -1)), "series 7");
        check(matches(series, identity("Adreno (TM) 840", -1)), "series 8");
        check(!matches(series, identity("Adreno (TM) 650", -1)), "series 6 not listed");
        check(!matches(series, identity("Mali-G715", -1)), "mali has no series");

        DeviceRules models = singleRule("{\"adrenoModel\":[\"618\",\"740-750\"]}");
        check(matches(models, identity("Adreno (TM) 618", -1)), "single model");
        check(matches(models, identity("Adreno (TM) 750", -1)), "range end");
        check(!matches(models, identity("Adreno (TM) 751", -1)), "past range");

        DeviceRules soc = singleRule("{\"socModel\":[\"sm8550\"]}");
        DeviceIdentity s23 = identity("Adreno (TM) 740", -1);
        check(matches(soc, s23), "soc model is case-insensitive");
        s23.socModel = "";
        check(!matches(soc, s23), "empty soc never matches");

        DeviceRules maker = singleRule("{\"manufacturer\":[\"SAMSUNG\"]}");
        check(matches(maker, identity("", -1)), "manufacturer is case-insensitive");

        DeviceRules sdk = singleRule("{\"minSdk\":30,\"maxSdk\":33}");
        DeviceIdentity old = identity("", -1);
        old.sdkInt = 31;
        check(matches(sdk, old), "sdk inside range");
        old.sdkInt = 29;
        check(!matches(sdk, old), "sdk below range");
        old.sdkInt = 34;
        check(!matches(sdk, old), "sdk above range");

        DeviceRules both = singleRule("{\"gpuVendor\":[\"adreno\"],\"adrenoModel\":[\"740\"]}");
        check(matches(both, identity("Adreno (TM) 740", -1)), "AND of keys");
        check(!matches(both, identity("Mali-G715", -1)), "AND of keys fails on vendor");

        DeviceRules future = singleRule("{\"gpuVendor\":[\"adreno\"],\"someNewKey\":[\"x\"]}");
        check(!matches(future, identity("Adreno (TM) 740", -1)), "a rule with an unknown criterion never matches");
        eq("catch-all", future.match(identity("Adreno (TM) 740", -1)).rule.id, "unknown criterion falls through");
    }

    // ---- remote acceptance ----

    private static KeyPair newKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private static String sign(KeyPair pair, byte[] data) throws Exception {
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(pair.getPrivate());
        signature.update(data);
        return Base64.getEncoder().encodeToString(signature.sign()) + "\n";
    }

    private static final class FakeFetcher implements DeviceRulesStore.Fetcher {
        byte[] rules;
        byte[] signature;
        IOException failure;
        int calls;

        @Override
        public byte[] fetch(String url, int maxBytes) throws IOException {
            calls++;
            if (failure != null) throw failure;
            if (url.equals(DeviceRulesStore.RULES_URL)) return rules;
            if (url.equals(DeviceRulesStore.SIGNATURE_URL)) return signature;
            throw new IOException("unexpected url " + url);
        }
    }

    private static FakeFetcher remote(KeyPair signer, String json) throws Exception {
        FakeFetcher fetcher = new FakeFetcher();
        fetcher.rules = json.getBytes(StandardCharsets.UTF_8);
        fetcher.signature = sign(signer, fetcher.rules).getBytes(StandardCharsets.US_ASCII);
        return fetcher;
    }

    /** Remote versions in these tests count from the bundled rulesVersion: rel(1) equals it, rel(2) is the next one. */
    private static int rel(int rulesVersion) {
        return bundledVersion - 1 + rulesVersion;
    }

    private static String version(int rulesVersion, int minApp) throws Exception {
        return mutate(r -> {
            r.put("rulesVersion", rel(rulesVersion));
            r.put("minAppVersionCode", minApp);
        });
    }

    private static void testRemoteAcceptance() throws Exception {
        KeyPair key = newKey();
        KeyPair other = newKey();
        long now = 1_000_000_000L;

        check(DeviceRulesStore.RULES_URL.startsWith("https://raw.githubusercontent.com/ashmerra/wehavephones/main/"), "rules url");
        eq(DeviceRulesStore.RULES_URL + ".sig", DeviceRulesStore.SIGNATURE_URL, "signature url");
        check(DeviceRulesStore.embeddedKey() != null, "embedded key parses");

        Context ctx = newContext();
        eq("bundled", DeviceRulesStore.load(ctx, key.getPublic()).source, "starts bundled");

        DeviceRulesStore.RefreshResult good = DeviceRulesStore.refresh(ctx, remote(key, version(2, 1)), key.getPublic(), 1, now);
        eq("updated", good.status, "good signature accepted: " + good);
        eq(rel(2), good.rulesVersion, "updated version");
        DeviceRulesStore.Loaded loaded = DeviceRulesStore.load(ctx, key.getPublic());
        eq("remote", loaded.source, "load uses the remote cache");
        eq(rel(2), loaded.rules.getRulesVersion(), "cached rulesVersion");

        // Same version again is current, not an error.
        eq("current", DeviceRulesStore.refresh(ctx, remote(key, version(2, 1)), key.getPublic(), 1, now).status, "same cached version is current");
        // Older than the cache but newer than bundled.
        Context withThree = newContext();
        eq("updated", DeviceRulesStore.refresh(withThree, remote(key, version(3, 1)), key.getPublic(), 1, now).status, "v3 accepted");
        DeviceRulesStore.RefreshResult lower = DeviceRulesStore.refresh(withThree, remote(key, version(2, 1)), key.getPublic(), 1, now);
        eq("rejected", lower.status, "lower than cached rejected: " + lower);
        eq(rel(3), DeviceRulesStore.load(withThree, key.getPublic()).rules.getRulesVersion(), "cache stays at v3");

        // Equal to bundled and lower than bundled.
        Context fresh = newContext();
        eq("rejected", DeviceRulesStore.refresh(fresh, remote(key, version(1, 1)), key.getPublic(), 1, now).status, "equal to bundled rejected");
        eq("bundled", DeviceRulesStore.load(fresh, key.getPublic()).source, "rejected rules not cached");

        // Tampered bytes with the old signature.
        FakeFetcher tampered = remote(key, version(5, 1));
        tampered.rules = new String(tampered.rules, StandardCharsets.UTF_8).replace("\"updatedAt\"", "\"updatedAT\"").getBytes(StandardCharsets.UTF_8);
        eq("rejected", DeviceRulesStore.refresh(fresh, tampered, key.getPublic(), 1, now).status, "tampered rules rejected");
        // Signed by a different key.
        eq("rejected", DeviceRulesStore.refresh(fresh, remote(other, version(5, 1)), key.getPublic(), 1, now).status, "wrong key rejected");
        // Garbage signature.
        FakeFetcher garbage = remote(key, version(5, 1));
        garbage.signature = "not base64 at all!".getBytes(StandardCharsets.US_ASCII);
        eq("rejected", DeviceRulesStore.refresh(fresh, garbage, key.getPublic(), 1, now).status, "garbage signature rejected");
        // App too old for the rules.
        DeviceRulesStore.RefreshResult tooNew = DeviceRulesStore.refresh(fresh, remote(key, version(6, 2)), key.getPublic(), 1, now);
        eq("rejected", tooNew.status, "minAppVersionCode above this build rejected: " + tooNew);
        eq("updated", DeviceRulesStore.refresh(fresh, remote(key, version(6, 2)), key.getPublic(), 2, now).status, "same file accepted by a newer app");
        // Unsupported schema, correctly signed.
        Context schemaCtx = newContext();
        eq("rejected", DeviceRulesStore.refresh(schemaCtx, remote(key, mutate(r -> { r.put("rulesVersion", rel(9)); r.put("schema", 2); })), key.getPublic(), 1, now).status, "unsupported schema rejected");
        // Valid signature, invalid content (no catch-all).
        eq("rejected", DeviceRulesStore.refresh(schemaCtx, remote(key, mutate(r -> { r.put("rulesVersion", rel(9)); r.getJSONArray("rules").remove(r.getJSONArray("rules").length() - 1); })), key.getPublic(), 1, now).status, "invalid content rejected");
        eq("bundled", DeviceRulesStore.load(schemaCtx, key.getPublic()).source, "nothing cached after rejections");

        // Network failure and size cap.
        FakeFetcher failing = new FakeFetcher();
        failing.failure = new IOException("offline");
        eq("error", DeviceRulesStore.refresh(schemaCtx, failing, key.getPublic(), 1, now).status, "network failure is an error");
        long retryAt = schemaCtx.getSharedPreferences(DeviceRulesStore.PREFERENCES, 0).getLong("rules_last_check_ms", 0);
        eq(now - DeviceRulesStore.REFRESH_INTERVAL_MS + DeviceRulesStore.RETRY_AFTER_ERROR_MS, retryAt, "failed check retries sooner than 6 h");
        DeviceRulesStore.refresh(schemaCtx, remote(key, version(1, 1)), key.getPublic(), 1, now);
        eq(now, schemaCtx.getSharedPreferences(DeviceRulesStore.PREFERENCES, 0).getLong("rules_last_check_ms", 0), "completed check persists its time");
        check(DeviceRulesStore.readAll(new java.io.ByteArrayInputStream(new byte[1000]), 1000).length == 1000, "read at the cap");
        boolean capped = false;
        try {
            DeviceRulesStore.readAll(new java.io.ByteArrayInputStream(new byte[1001]), 1000);
        }
        catch (IOException e) {
            capped = true;
        }
        check(capped, "body larger than the cap is refused");
        eq(256 * 1024, DeviceRulesStore.MAX_RULES_BYTES, "256 KB cap");
        eq(6L * 60 * 60 * 1000, DeviceRulesStore.REFRESH_INTERVAL_MS, "6 h interval");
    }

    private static void testCachedRulesRevalidated() throws Exception {
        KeyPair key = newKey();
        Context ctx = newContext();
        long now = 1_000_000_000L;
        eq("updated", DeviceRulesStore.refresh(ctx, remote(key, version(2, 1)), key.getPublic(), 1, now).status, "seed cache");
        File cached = new File(DeviceRulesStore.cacheDir(ctx), "device-rules.json");
        check(cached.isFile(), "cache file written under files/wehavephones/device-rules/");

        // Edit the cached file on disk: signature no longer matches.
        String edited = new String(Files.readAllBytes(cached.toPath()), StandardCharsets.UTF_8).replace("\"rulesVersion\":" + rel(2), "\"rulesVersion\":" + rel(9));
        Files.write(cached.toPath(), edited.getBytes(StandardCharsets.UTF_8));
        eq("bundled", DeviceRulesStore.load(ctx, key.getPublic()).source, "tampered cache ignored");

        // A cache that is not newer than the bundled rules (after an app update) is ignored.
        FakeFetcher same = remote(key, version(2, 1));
        String original = bundledJson;
        bundledJson = version(2, 1);
        try {
            Context upgraded = newContext();
            File dir = DeviceRulesStore.cacheDir(upgraded);
            dir.mkdirs();
            Files.write(new File(dir, "device-rules.json").toPath(), same.rules);
            Files.write(new File(dir, "device-rules.json.sig").toPath(), same.signature);
            eq("bundled", DeviceRulesStore.load(upgraded, key.getPublic()).source, "cache not newer than bundled is ignored");
        }
        finally {
            bundledJson = original;
        }
    }

    // ---- driver store ----

    private static byte[] tar(Map<String, byte[]> files, String symlinkName) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (TarArchiveOutputStream out = new TarArchiveOutputStream(bytes)) {
            out.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU);
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                TarArchiveEntry entry = new TarArchiveEntry(file.getKey());
                entry.setSize(file.getValue().length);
                out.putArchiveEntry(entry);
                out.write(file.getValue());
                out.closeArchiveEntry();
            }
            if (symlinkName != null) {
                TarArchiveEntry link = new TarArchiveEntry(symlinkName, org.apache.commons.compress.archivers.tar.TarConstants.LF_SYMLINK);
                link.setLinkName("/etc/passwd");
                out.putArchiveEntry(link);
                out.closeArchiveEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static byte[] zip(Map<String, byte[]> files) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                out.putNextEntry(new ZipEntry(file.getKey()));
                out.write(file.getValue());
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static Map<String, byte[]> driverFiles(String libraryName, byte[] library) {
        LinkedHashMapBytes files = new LinkedHashMapBytes();
        files.put("meta.json", ("{\"libraryName\":\"" + libraryName + "\"}").getBytes(StandardCharsets.UTF_8));
        files.put(libraryName, library);
        return files;
    }

    private static final class LinkedHashMapBytes extends java.util.LinkedHashMap<String, byte[]> {}

    private static DeviceRules.Driver driverEntry(String id, String asset, String url, byte[] archive, byte[] library, String libraryName) throws Exception {
        String json = mutate(r -> {
            JSONObject driver = new JSONObject();
            driver.put("type", "adrenotools");
            driver.put("asset", asset == null ? JSONObject.NULL : asset);
            driver.put("url", url == null ? JSONObject.NULL : url);
            driver.put("sha256", sha256(archive));
            driver.put("libraryName", libraryName);
            driver.put("librarySha256", sha256(library));
            r.getJSONObject("drivers").put(id, driver);
        });
        return DeviceRules.parse(json).getDriver(id);
    }

    private static void writeAsset(Context ctx, String asset, byte[] data) throws IOException {
        File file = new File(workRootAssets(ctx), asset);
        file.getParentFile().mkdirs();
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(data);
        }
    }

    private static File workRootAssets(Context ctx) {
        return new File(ctx.getFilesDir().getParentFile(), "assets");
    }

    private static void testDriverStore() throws Exception {
        byte[] library = "pretend vulkan driver".getBytes(StandardCharsets.UTF_8);

        // Bundled asset: extracted and verified.
        Context ctx = newContext();
        byte[] archive = tar(driverFiles("libtest.so", library), null);
        writeAsset(ctx, "graphics_driver/adrenotools-test.tzst", archive);
        DeviceRules.Driver bundled = driverEntry("test", "graphics_driver/adrenotools-test.tzst", null, archive, library, "libtest.so");
        check(DeviceDriverStore.ensureAvailable(ctx, bundled), "bundled driver becomes available");
        check(new File(DeviceDriverStore.driverDir(ctx, "test"), "libtest.so").isFile(), "library extracted to files/contents/adrenotools/<id>");
        check(new File(DeviceDriverStore.driverDir(ctx, "test"), "meta.json").isFile(), "meta.json extracted");
        check(!new File(DeviceDriverStore.driverDir(ctx, "test") + ".installing").exists(), "no staging directory left");
        check(DeviceDriverStore.ensureAvailable(ctx, bundled), "second call is a cheap yes");
        check(DeviceDriverStore.ensureAvailable(ctx, parseBundled().getDriver("system")), "system always available");

        // A stale or damaged extraction is replaced from the APK.
        Files.write(new File(DeviceDriverStore.driverDir(ctx, "test"), "libtest.so").toPath(), "damaged".getBytes());
        check(DeviceDriverStore.ensureAvailable(ctx, bundled), "damaged extraction repaired");
        eq(sha256(library), sha256(Files.readAllBytes(new File(DeviceDriverStore.driverDir(ctx, "test"), "libtest.so").toPath())), "repaired library hash");

        // The APK's archive does not match the rules: refused, nothing installed.
        Context mismatch = newContext();
        writeAsset(mismatch, "graphics_driver/adrenotools-test.tzst", tar(driverFiles("libtest.so", "other".getBytes()), null));
        check(!DeviceDriverStore.ensureAvailable(mismatch, bundled), "library hash mismatch refused");
        check(!DeviceDriverStore.driverDir(mismatch, "test").exists(), "nothing installed after a mismatch");

        // Not in the APK and not downloaded: unavailable.
        check(!DeviceDriverStore.ensureAvailable(newContext(), bundled), "missing asset is unavailable");

        // Tar entries that escape the directory are skipped; symlinks are refused.
        Context escape = newContext();
        LinkedHashMapBytes withEscape = new LinkedHashMapBytes();
        withEscape.put("../escaped.txt", "x".getBytes());
        withEscape.putAll(driverFiles("libtest.so", library));
        byte[] escapeArchive = tar(withEscape, null);
        writeAsset(escape, "graphics_driver/adrenotools-test.tzst", escapeArchive);
        DeviceRules.Driver escapeDriver = driverEntry("test", "graphics_driver/adrenotools-test.tzst", null, escapeArchive, library, "libtest.so");
        check(DeviceDriverStore.ensureAvailable(escape, escapeDriver), "driver with a skipped traversal entry installs");
        check(!new File(DeviceDriverStore.driverDir(escape, "test").getParentFile(), "escaped.txt").exists(), "traversal entry not written");

        Context links = newContext();
        byte[] linkArchive = tar(driverFiles("libtest.so", library), "evil-link");
        writeAsset(links, "graphics_driver/adrenotools-test.tzst", linkArchive);
        DeviceRules.Driver linkDriver = driverEntry("test", "graphics_driver/adrenotools-test.tzst", null, linkArchive, library, "libtest.so");
        check(!DeviceDriverStore.ensureAvailable(links, linkDriver), "archive with a symlink refused");
        check(!DeviceDriverStore.driverDir(links, "test").exists(), "nothing installed from a symlink archive");

        // Download: zip driver, verified and installed by prefetch; ensureAvailable then says yes.
        Context net = newContext();
        byte[] zipBytes = zip(driverFiles("libnet.so", library));
        final int[] downloads = {0};
        DeviceDriverStore.downloader = (url, target, max) -> {
            downloads[0]++;
            Files.write(target.toPath(), zipBytes);
        };
        DeviceRules.Driver remoteZip = driverEntry("netdrv", null, "https://example.com/drivers/netdrv.zip", zipBytes, library, "libnet.so");
        eq("zip", remoteZip.archiveKind(), "url zip kind");
        check(!DeviceDriverStore.ensureAvailable(net, remoteZip), "not available before the download");
        eq(0, downloads[0], "ensureAvailable never downloads");
        DeviceRules.Profile prefetchProfile = profileWith(remoteZip);
        DeviceDriverStore.prefetchNow(net, prefetchProfile);
        eq(1, downloads[0], "prefetch downloads once");
        check(DeviceDriverStore.ensureAvailable(net, remoteZip), "downloaded driver is available");
        check(new File(DeviceDriverStore.driverDir(net, "netdrv"), "libnet.so").isFile(), "zip library installed");
        DeviceDriverStore.prefetchNow(net, prefetchProfile);
        eq(1, downloads[0], "installed driver is not downloaded again");

        // Corrupt download is refused and leaves nothing behind.
        Context corrupt = newContext();
        DeviceDriverStore.downloader = (url, target, max) -> Files.write(target.toPath(), "corrupt".getBytes());
        DeviceDriverStore.prefetchNow(corrupt, prefetchProfile);
        check(!DeviceDriverStore.ensureAvailable(corrupt, remoteZip), "corrupt download not installed");
        check(!DeviceDriverStore.driverDir(corrupt, "netdrv").exists(), "no driver directory from a corrupt download");
        check(!DeviceDriverStore.downloadedArchive(corrupt, remoteZip).exists(), "corrupt download deleted");

        // Failed download is survivable.
        DeviceDriverStore.downloader = (url, target, max) -> { throw new IOException("offline"); };
        DeviceDriverStore.prefetchNow(corrupt, prefetchProfile);
        check(!DeviceDriverStore.ensureAvailable(corrupt, remoteZip), "offline download leaves it unavailable");

        // A zip with path traversal is rejected even with a matching hash.
        LinkedHashMapBytes evilZipFiles = new LinkedHashMapBytes();
        evilZipFiles.put("../../evil.so", library);
        evilZipFiles.putAll(driverFiles("libnet.so", library));
        byte[] evilZip = zip(evilZipFiles);
        DeviceRules.Driver evilDriver = driverEntry("evil", null, "https://example.com/evil.zip", evilZip, library, "libnet.so");
        Context evilCtx = newContext();
        DeviceDriverStore.downloader = (url, target, max) -> Files.write(target.toPath(), evilZip);
        DeviceDriverStore.prefetchNow(evilCtx, profileWith(evilDriver));
        check(!DeviceDriverStore.ensureAvailable(evilCtx, evilDriver), "zip with path traversal not installed");
        check(!DeviceDriverStore.driverDir(evilCtx, "evil").exists(), "no directory from a traversal zip");

        // A tzst download works too.
        Context tzstNet = newContext();
        byte[] tzstBytes = tar(driverFiles("libnet.so", library), null);
        DeviceDriverStore.downloader = (url, target, max) -> Files.write(target.toPath(), tzstBytes);
        DeviceRules.Driver remoteTzst = driverEntry("tzstdrv", null, "https://example.com/tzstdrv.tzst", tzstBytes, library, "libnet.so");
        DeviceDriverStore.prefetchNow(tzstNet, profileWith(remoteTzst));
        check(DeviceDriverStore.ensureAvailable(tzstNet, remoteTzst), "downloaded tzst driver installs");
    }

    private static DeviceRules.Profile profileWith(DeviceRules.Driver driver) throws Exception {
        String json = mutate(r -> {
            JSONObject profile = new JSONObject();
            profile.put("status", "community");
            profile.put("drivers", new JSONArray().put(driver.id).put("system"));
            r.getJSONObject("profiles").put("probe-profile", profile);
            JSONObject entry = new JSONObject();
            entry.put("type", "adrenotools");
            entry.put("asset", driver.asset == null ? JSONObject.NULL : driver.asset);
            entry.put("url", driver.url == null ? JSONObject.NULL : driver.url);
            entry.put("sha256", driver.sha256);
            entry.put("libraryName", driver.libraryName);
            entry.put("librarySha256", driver.librarySha256);
            r.getJSONObject("drivers").put(driver.id, entry);
        });
        return DeviceRules.parse(json).getProfile("probe-profile");
    }

    // ---- selector ----

    private static final class FakeProber implements DeviceProfileSelector.Prober {
        final HashMap<String, Boolean> outcomes = new HashMap<>();
        final ArrayList<String> calls = new ArrayList<>();
        Context context;

        @Override
        public GpuProbe.ProbeResult probe(Context ctx, String driverId) {
            calls.add(driverId);
            // The crash marker is on disk while the probe runs.
            check(ctx.getSharedPreferences(DeviceRulesStore.PREFERENCES, 0).getString("probe_inflight", null) != null,
                "in-flight marker set during the probe of " + driverId);
            GpuProbe.ProbeResult result = new GpuProbe.ProbeResult();
            Boolean ok = outcomes.get(driverId);
            result.ok = ok == null || ok;
            result.stage = result.ok ? "device" : "instance";
            result.error = result.ok ? "" : "vkCreateInstance failed";
            result.deviceName = "fake gpu";
            return result;
        }
    }

    private static DeviceProfileSelector.Availability available(final String... missing) {
        final HashSet<String> gone = new HashSet<>(java.util.Arrays.asList(missing));
        return (ctx, driver) -> !gone.contains(driver.id);
    }

    private static GraphicsPlan select(Context ctx, DeviceIdentity id, FakeProber prober, DeviceProfileSelector.Availability availability) throws Exception {
        return DeviceProfileSelector.select(ctx, id, new DeviceRulesStore.Loaded(parseBundled(), "bundled"), prober, availability);
    }

    private static void testSelector() throws Exception {
        Build.FINGERPRINT = "samsung/test/fingerprint";

        // S23: first driver probes ok.
        Context ctx = newContext();
        FakeProber prober = new FakeProber();
        GraphicsPlan plan = select(ctx, identity("Adreno (TM) 740", -1), prober, available());
        eq(TURNIP, plan.driverId, "S23 driver");
        check(plan.useAdrenotools, "S23 uses adrenotools");
        eq("adreno-7xx", plan.ruleId, "S23 plan rule");
        eq("adreno-7xx", plan.profileName, "S23 plan profile");
        eq("tested", plan.profileStatus, "S23 plan status");
        eq("bundled", plan.rulesSource, "plan rules source");
        eq(bundledVersion, plan.rulesVersion, "plan rules version");
        eq(1, prober.calls.size(), "one probe");
        check(plan.log.size() > 3, "plan keeps its decision log");
        eq(TURNIP + "=ok; system=not-tried", DeviceProfileSelector.driverProbes(plan), "driver probes summary");

        // Cache reuse: the second run does not probe again.
        GraphicsPlan again = select(ctx, identity("Adreno (TM) 740", -1), prober, available());
        eq(1, prober.calls.size(), "cached probe reused");
        eq(TURNIP, again.driverId, "cached plan driver");
        check(again.log.toString().contains("cached"), "log says the result was cached");
        check(ctx.getSharedPreferences(DeviceRulesStore.PREFERENCES, 0).getString("probe_inflight", null) == null, "in-flight marker cleared");

        // A different build fingerprint probes again.
        Build.FINGERPRINT = "samsung/test/other-fingerprint";
        select(ctx, identity("Adreno (TM) 740", -1), prober, available());
        eq(2, prober.calls.size(), "new fingerprint probes again");
        Build.FINGERPRINT = "samsung/test/fingerprint";

        DeviceProfileSelector.clearProbeCache(ctx);
        select(ctx, identity("Adreno (TM) 740", -1), prober, available());
        eq(3, prober.calls.size(), "cleared cache probes again");

        // 8xx fallback order: first fails, second wins.
        Context fallback = newContext();
        FakeProber failFirst = new FakeProber();
        failFirst.outcomes.put(V32, false);
        GraphicsPlan a830 = select(fallback, identity("Adreno (TM) 830", -1), failFirst, available());
        eq(java.util.Arrays.asList(V32, GEN8), failFirst.calls, "probe order");
        eq(GEN8, a830.driverId, "second driver wins after the first fails");
        eq("noconform,sysmem", a830.env.get("TU_DEBUG"), "8xx env in plan");
        eq("-d3d11", a830.wowApiArg, "8xx wowApiArg in plan");
        check(a830.log.toString().contains("probe " + V32 + "=failed"), "failure logged");
        eq(V32 + "=failed; " + GEN8 + "=ok; " + TURNIP + "=not-tried; system=not-tried", DeviceProfileSelector.driverProbes(a830), "probe summary with a failure");

        // Cached failure is not retried.
        failFirst.calls.clear();
        select(fallback, identity("Adreno (TM) 830", -1), failFirst, available());
        eq(0, failFirst.calls.size(), "no probes at all on the second run");

        // A url driver that is not downloaded yet is skipped without a probe.
        Context notYet = newContext();
        FakeProber notYetProber = new FakeProber();
        GraphicsPlan beforeDownload = select(notYet, identity("Adreno (TM) 830", -1), notYetProber, available(V32));
        eq(java.util.Arrays.asList(GEN8), notYetProber.calls, "only the installed Gen 8 driver is probed");
        eq(GEN8, beforeDownload.driverId, "Gen 8 driver until V32 is downloaded");
        check(beforeDownload.log.toString().contains("probe " + V32 + "=unavailable"), "missing download logged");

        // Unavailable drivers are skipped without a probe.
        Context skip = newContext();
        FakeProber skipProber = new FakeProber();
        GraphicsPlan skipped = select(skip, identity("Adreno (TM) 830", -1), skipProber, available(V32, GEN8, TURNIP));
        eq(0, skipProber.calls.size(), "unavailable driver skipped, nothing probed");
        eq("system", skipped.driverId, "falls back to system");
        check(!skipped.useAdrenotools, "system is not adrenotools");
        check(skipped.log.toString().contains("probe " + GEN8 + "=unavailable"), "skip logged");

        // All adrenotools drivers fail: system.
        Context allFail = newContext();
        FakeProber failing = new FakeProber();
        failing.outcomes.put(V32, false);
        failing.outcomes.put(GEN8, false);
        failing.outcomes.put(TURNIP, false);
        GraphicsPlan fallbackSystem = select(allFail, identity("Adreno (TM) 830", -1), failing, available());
        eq("system", fallbackSystem.driverId, "all failed uses system");
        check(fallbackSystem.log.toString().contains("probe " + GEN8 + "=failed"), "failure kept in the log");

        // OnePlus 15 (chip id from the device report in issue #13): the system driver, nothing probed.
        Context op = newContext();
        FakeProber opProber = new FakeProber();
        GraphicsPlan oneplus = select(op, identity("Adreno (TM) 840", 0x44050A31L), opProber, available());
        eq("system", oneplus.driverId, "OnePlus 15 gets the system driver");
        check(!oneplus.useAdrenotools, "OnePlus 15 is not adrenotools");
        eq(0, opProber.calls.size(), "OnePlus 15 probes nothing");
        eq("-d3d11", oneplus.wowApiArg, "OnePlus 15 runs WoW with D3D11");
        eq("adreno-84x", oneplus.ruleId, "OnePlus rule");
        eq("community", oneplus.profileStatus, "OnePlus status");

        // Crash guard: a probe that kills the process leaves the marker; the next run marks it failed.
        Context crash = newContext();
        DeviceProfileSelector.Prober dying = (c, driverId) -> { throw new Error("native crash"); };
        try {
            DeviceProfileSelector.select(crash, identity("Adreno (TM) 740", -1), new DeviceRulesStore.Loaded(parseBundled(), "bundled"), dying, available());
            check(false, "Error from the prober should propagate");
        }
        catch (Error e) {
            check(true, "");
        }
        check(crash.getSharedPreferences(DeviceRulesStore.PREFERENCES, 0).getString("probe_inflight", null) != null, "marker survives a dying probe");
        FakeProber afterCrash = new FakeProber();
        GraphicsPlan recovered = select(crash, identity("Adreno (TM) 740", -1), afterCrash, available());
        eq(0, afterCrash.calls.size(), "crashed driver is not probed again");
        eq("system", recovered.driverId, "crashed driver replaced by system");
        check(recovered.log.toString().contains("crashed during"), "crash recorded in the log");
        check(recovered.log.toString().contains("probe " + TURNIP + "=failed"), "crashed probe counts as failed");
        check(crash.getSharedPreferences(DeviceRulesStore.PREFERENCES, 0).getString("probe_inflight", null) == null, "marker cleared after recovery");

        // Mali: system only, no probing, compat layer.
        Context mali = newContext();
        FakeProber maliProber = new FakeProber();
        GraphicsPlan pixel = select(mali, identity("Mali-G715", -1), maliProber, available());
        eq("system", pixel.driverId, "Pixel driver");
        check(!pixel.useAdrenotools, "Pixel not adrenotools");
        check(pixel.vulkanCompatLayer, "Pixel compat layer");
        eq(0, maliProber.calls.size(), "system driver is not probed");
        check(pixel.env.isEmpty(), "Pixel env empty");

        // Persisted last plan.
        Context persist = newContext();
        FakeProber persistProber = new FakeProber();
        GraphicsPlan stored = DeviceProfileSelector.select(persist, persistProber);
        check(stored != null, "select(Context, Prober) returns a plan");
        // No GL renderer is stubbed yet: GPUInformation.renderer is empty -> catch-all -> legacy drivers.
        eq("catch-all", stored.ruleId, "empty renderer matches the catch-all");
        GraphicsPlan last = DeviceProfileSelector.lastPlan(persist);
        check(last != null, "last plan persisted");
        eq(stored.driverId, last.driverId, "last plan driver");
        eq(stored.ruleId, last.ruleId, "last plan rule");
        check(new File(persist.getFilesDir(), "wehavephones/devices/last-plan.json").isFile(), "last-plan.json location");
        check(DeviceProfileSelector.lastPlan(newContext()) == null, "no last plan before the first session");

        // Broken rules never break a session: legacy behaviour.
        File broken = new File(workRootAssets(persist), "wehavephones/device-rules.json");
        Files.write(broken.toPath(), "{}".getBytes());
        GPUInformation.renderer = "Adreno (TM) 740";
        GraphicsPlan legacyAdreno = DeviceProfileSelector.select(persist, persistProber);
        eq("turnip" + DefaultVersion.ADRENOTOOLS_TURNIP, legacyAdreno.driverId, "unreadable rules keep the legacy Turnip");
        check(legacyAdreno.useAdrenotools && !legacyAdreno.vulkanCompatLayer, "legacy Adreno flags");
        GPUInformation.renderer = "Mali-G715";
        GraphicsPlan legacyMali = DeviceProfileSelector.select(persist, persistProber);
        eq("system", legacyMali.driverId, "unreadable rules keep the legacy system driver");
        check(!legacyMali.useAdrenotools && legacyMali.vulkanCompatLayer, "legacy Mali flags");
        GPUInformation.renderer = "";
    }

    // ---- driver picker ----

    private static void testDriverChoice() throws Exception {
        Build.FINGERPRINT = "samsung/test/fingerprint";
        DeviceRules rules = DeviceRules.parse(mutate(r ->
            r.getJSONObject("profiles").getJSONObject("adreno-7xx").put("choices", new JSONArray().put(GEN8).put(V32))));
        DeviceRulesStore.Loaded loaded = new DeviceRulesStore.Loaded(rules, "bundled");
        DeviceIdentity s23 = identity("Adreno (TM) 740", -1);

        // Automatic: no pick, nothing changes.
        Context auto = newContext();
        FakeProber autoProber = new FakeProber();
        GraphicsPlan automatic = DeviceProfileSelector.select(auto, s23, loaded, autoProber, available());
        eq(TURNIP, automatic.driverId, "automatic keeps the profile order");
        eq("", automatic.driverChoice, "no pick recorded");
        eq("", DeviceProfileSelector.driverChoice(auto), "no pick saved");

        // A picked choice that loads wins; the profile drivers are not probed.
        Context pick = newContext();
        DeviceProfileSelector.setDriverChoice(pick, rules.getDriver(GEN8));
        eq(GEN8, DeviceProfileSelector.driverChoice(pick), "pick saved");
        FakeProber pickProber = new FakeProber();
        GraphicsPlan picked = DeviceProfileSelector.select(pick, s23, loaded, pickProber, available());
        eq(GEN8, picked.driverId, "picked driver used");
        eq(GEN8, picked.driverChoice, "pick recorded in the plan");
        eq(java.util.Arrays.asList(GEN8), pickProber.calls, "only the pick is probed");
        eq(GEN8 + "=ok; " + TURNIP + "=not-tried; system=not-tried", DeviceProfileSelector.driverProbes(picked), "probe summary with a pick");

        // A pick that fails to load falls back to the automatic order and keeps the record.
        Context failing = newContext();
        DeviceProfileSelector.setDriverChoice(failing, rules.getDriver(GEN8));
        FakeProber failProber = new FakeProber();
        failProber.outcomes.put(GEN8, false);
        GraphicsPlan fellBack = DeviceProfileSelector.select(failing, s23, loaded, failProber, available());
        eq(TURNIP, fellBack.driverId, "failed pick falls back to Automatic");
        eq(GEN8, fellBack.driverChoice, "failed pick still recorded");
        eq(java.util.Arrays.asList(GEN8, TURNIP), failProber.calls, "pick probed, then the automatic order");
        check(fellBack.log.toString().contains("failed to load; using Automatic"), "fallback logged");
        DeviceProfileSelector.select(failing, s23, loaded, failProber, available());
        eq(2, failProber.calls.size(), "cached failure is not retried by itself");
        DeviceProfileSelector.setDriverChoice(failing, rules.getDriver(GEN8));
        DeviceProfileSelector.select(failing, s23, loaded, failProber, available());
        eq(3, failProber.calls.size(), "picking the driver again probes it again");

        // A pick that is not downloaded yet is skipped without a probe.
        Context missing = newContext();
        DeviceProfileSelector.setDriverChoice(missing, rules.getDriver(V32));
        FakeProber missingProber = new FakeProber();
        GraphicsPlan notDownloaded = DeviceProfileSelector.select(missing, s23, loaded, missingProber, available(V32));
        eq(TURNIP, notDownloaded.driverId, "missing pick falls back");
        eq(java.util.Arrays.asList(TURNIP), missingProber.calls, "missing pick not probed");
        check(notDownloaded.log.toString().contains("is not downloaded; using Automatic"), "missing pick logged");

        // Picking the system driver: no probes, no adrenotools.
        Context system = newContext();
        DeviceProfileSelector.setDriverChoice(system, rules.getDriver("system"));
        FakeProber systemProber = new FakeProber();
        GraphicsPlan systemPlan = DeviceProfileSelector.select(system, s23, loaded, systemProber, available());
        eq("system", systemPlan.driverId, "system pick");
        check(!systemPlan.useAdrenotools, "system pick is not adrenotools");
        eq(0, systemProber.calls.size(), "system pick probes nothing");

        // Picking a driver the profile already lists probes it once.
        Context listed = newContext();
        DeviceProfileSelector.setDriverChoice(listed, rules.getDriver(TURNIP));
        FakeProber listedProber = new FakeProber();
        GraphicsPlan listedPlan = DeviceProfileSelector.select(listed, s23, loaded, listedProber, available());
        eq(TURNIP, listedPlan.driverId, "listed pick");
        eq(java.util.Arrays.asList(TURNIP), listedProber.calls, "listed pick probed once");
        eq(TURNIP + "=ok; system=not-tried", DeviceProfileSelector.driverProbes(listedPlan), "listed pick summary has no duplicate");

        // A pick the matched profile does not offer (rules changed, or another GPU) is ignored.
        Context stale = newContext();
        DeviceProfileSelector.setDriverChoice(stale, rules.getDriver(GEN8));
        FakeProber staleProber = new FakeProber();
        GraphicsPlan ignored = select(stale, s23, staleProber, available());
        eq(TURNIP, ignored.driverId, "pick not offered by the bundled 7xx profile");
        eq("", ignored.driverChoice, "unoffered pick not recorded");
        check(ignored.log.toString().contains("is not offered"), "unoffered pick logged");

        // The driver Automatic uses, for the picker: the first listed driver not known to fail.
        DeviceRules.Profile s23Profile = rules.match(s23).profile;
        eq(TURNIP, DeviceProfileSelector.automaticDriver(newContext(), s23Profile).id, "Automatic before any probe");
        eq(TURNIP, DeviceProfileSelector.automaticDriver(auto, s23Profile).id, "Automatic after its driver loaded");
        Context brokenFirst = newContext();
        FakeProber brokenProber = new FakeProber();
        brokenProber.outcomes.put(TURNIP, false);
        DeviceProfileSelector.select(brokenFirst, s23, loaded, brokenProber, available());
        eq("system", DeviceProfileSelector.automaticDriver(brokenFirst, s23Profile).id, "Automatic skips a failed driver");
        check(DeviceProfileSelector.automaticDriver(auto, null) == null, "no profile, no Automatic driver");

        // Back to Automatic.
        DeviceProfileSelector.setDriverChoice(pick, null);
        eq("", DeviceProfileSelector.driverChoice(pick), "pick cleared");
        FakeProber clearedProber = new FakeProber();
        eq(TURNIP, DeviceProfileSelector.select(pick, s23, loaded, clearedProber, available()).driverId, "Automatic again");
    }

    // ---- equivalence with the pre-rules behaviour ----

    // The code in XServerDisplayActivity before device rules, reduced to its decisions.
    private static String legacyTuDebug(String current) {
        String tuDebug = current == null ? "" : current;
        if (tuDebug.isEmpty()) tuDebug = "noconform";
        if (!tuDebug.contains("sysmem")) tuDebug = tuDebug + ",sysmem";
        return tuDebug;
    }

    private static void testEquivalence() throws Exception {
        String[] renderers = {
            "Adreno (TM) 740", "Adreno (TM) 730", "Adreno (TM) 650", "Adreno (TM) 619", "Adreno (TM) 750",
            "Mali-G715", "Mali-G78", "Immortalis-G720", "Samsung Xclipse 940", "PowerVR Rogue GE8320",
            "llvmpipe (LLVM 15.0.7, 128 bits)", "",
        };
        for (String renderer : renderers) {
            GPUInformation.renderer = renderer;
            boolean legacyAdreno = GPUInformation.mayBeAdreno(null);
            Context ctx = newContext();
            GraphicsPlan plan = select(ctx, identity(renderer, -1), new FakeProber(), available());
            eq(legacyAdreno, plan.useAdrenotools, "adrenotools decision matches mayBeAdreno for \"" + renderer + "\"");
            eq(!legacyAdreno, plan.vulkanCompatLayer, "compat layer matches !mayBeAdreno for \"" + renderer + "\"");
            if (legacyAdreno) {
                eq("turnip" + DefaultVersion.ADRENOTOOLS_TURNIP, plan.driverId, "legacy driver id for \"" + renderer + "\"");
                eq("noconform,sysmem", legacyTuDebug(plan.env.get("TU_DEBUG")), "TU_DEBUG for \"" + renderer + "\"");
            }
            else {
                eq("system", plan.driverId, "system driver for \"" + renderer + "\"");
            }
            // Adreno 7xx moved from D3D12 to D3D11 in rules v8 and every other GPU in rules v9.
            eq("-d3d11", plan.wowApiArg, "default wowApiArg for \"" + renderer + "\"");
        }
        GPUInformation.renderer = "";

        // The two shipped test phones, spelled out.
        GraphicsPlan s23 = select(newContext(), identity("Adreno (TM) 740", -1), new FakeProber(), available());
        eq("turnip26.3.0-devel-1b51639c20", s23.driverId, "S23 driver is the pinned Turnip");
        check(s23.useAdrenotools && !s23.vulkanCompatLayer && s23.env.isEmpty(), "S23: Turnip, no compat layer, no extra env");
        eq("-d3d11", s23.wowApiArg, "S23 runs WoW with D3D11");
        GraphicsPlan pixel = select(newContext(), identity("Mali-G715", -1), new FakeProber(), available());
        check("system".equals(pixel.driverId) && !pixel.useAdrenotools && pixel.vulkanCompatLayer && pixel.env.isEmpty(),
            "Pixel 8a: system driver, compat layer, no extra env");
        eq("-d3d11", pixel.wowApiArg, "Pixel 8a runs WoW with D3D11");
        // A user env value still wins: the integration applies profile env only for unset keys, so with
        // an empty profile env the legacy TU_DEBUG logic is untouched.
        eq("foo,sysmem", legacyTuDebug("foo"), "legacy TU_DEBUG logic");
    }

    private static void testGraphicsPlanJson() {
        GraphicsPlan plan = new GraphicsPlan();
        plan.driverId = "x";
        plan.useAdrenotools = true;
        plan.env.put("TU_DEBUG", "noconform,sysmem");
        plan.env.put("A", "b");
        plan.vulkanCompatLayer = true;
        plan.wowApiArg = "-d3d11";
        plan.ruleId = "r";
        plan.profileName = "p";
        plan.profileStatus = "tested";
        plan.rulesSource = "remote";
        plan.rulesVersion = 7;
        plan.log.add("one");
        plan.log.add("two \"quoted\"");
        GraphicsPlan back = GraphicsPlan.fromJson(plan.toJson());
        check(back != null, "plan json parses");
        eq("x", back.driverId, "json driverId");
        check(back.useAdrenotools && back.vulkanCompatLayer, "json booleans");
        eq(plan.env, back.env, "json env");
        eq("-d3d11", back.wowApiArg, "json wowApiArg");
        eq(7, back.rulesVersion, "json rulesVersion");
        eq(plan.log, back.log, "json log");
        eq("remote", back.rulesSource, "json rulesSource");
        eq("", back.driverChoice, "json empty driverChoice");
        plan.driverChoice = "picked";
        eq("picked", GraphicsPlan.fromJson(plan.toJson()).driverChoice, "json driverChoice");

        GraphicsPlan empty = GraphicsPlan.fromJson(new GraphicsPlan().toJson());
        check(empty != null && empty.wowApiArg == null, "null wowApiArg round trip");
        eq("system", empty.driverId, "default driver id");
        check(GraphicsPlan.fromJson("not json") == null, "garbage is not a plan");
        check(GraphicsPlan.fromJson(null) == null, "null is not a plan");
    }
}

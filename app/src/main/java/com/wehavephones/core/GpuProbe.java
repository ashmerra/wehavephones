package com.wehavephones.core;

import android.content.Context;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Reads the Qualcomm KGSL chip id and test-loads a Vulkan driver (the phone's own or an extracted
 * adrenotools driver) through instance, device enumeration and device creation. Never throws.
 */
public final class GpuProbe {
    private static final String TAG = "WeHavePhonesDevices";
    private static final String SYSTEM_DRIVER = "system";

    private static final boolean NATIVE_LOADED;
    private static final String NATIVE_ERROR;

    static {
        boolean loaded = false;
        String error = "";
        try {
            System.loadLibrary("wehavephones");
            loaded = true;
        }
        catch (Throwable e) {
            error = "native library unavailable: " + e;
        }
        NATIVE_LOADED = loaded;
        NATIVE_ERROR = error;
    }

    private GpuProbe() {}

    public static final class KgslInfo {
        public boolean available;
        public long chipId = -1;
        public int gpuId;
        public String gpuModel = "";
        public String error = "";
    }

    public static final class ProbeResult {
        public boolean ok;
        public String stage = "";
        public int vkResult;
        public String deviceName = "";
        public int vendorId;
        public int deviceId;
        public String apiVersion = "";
        public String driverVersion = "";
        public int vkDriverId;
        public String driverName = "";
        public String driverInfo = "";
        public String error = "";

        public String toJson() {
            try {
                JSONObject json = new JSONObject();
                json.put("ok", ok);
                json.put("stage", stage);
                json.put("vkResult", vkResult);
                json.put("deviceName", deviceName);
                json.put("vendorId", vendorId);
                json.put("deviceId", deviceId);
                json.put("apiVersion", apiVersion);
                json.put("driverVersion", driverVersion);
                json.put("vkDriverId", vkDriverId);
                json.put("driverName", driverName);
                json.put("driverInfo", driverInfo);
                json.put("error", error);
                return json.toString();
            }
            catch (JSONException e) {
                return "{}";
            }
        }

        public static ProbeResult fromJson(String s) {
            ProbeResult result = new ProbeResult();
            try {
                JSONObject json = new JSONObject(s);
                result.ok = json.optBoolean("ok", false);
                result.stage = json.optString("stage", "");
                result.vkResult = json.optInt("vkResult", 0);
                result.deviceName = json.optString("deviceName", "");
                result.vendorId = json.optInt("vendorId", 0);
                result.deviceId = json.optInt("deviceId", 0);
                result.apiVersion = json.optString("apiVersion", "");
                result.driverVersion = json.optString("driverVersion", "");
                result.vkDriverId = json.optInt("vkDriverId", 0);
                result.driverName = json.optString("driverName", "");
                result.driverInfo = json.optString("driverInfo", "");
                result.error = json.optString("error", "");
            }
            catch (Exception e) {
                result = new ProbeResult();
                result.stage = "parse";
                result.error = "invalid probe result json: " + e.getMessage();
            }
            return result;
        }
    }

    private static native String[] nativeReadKgsl();

    private static native String[] nativeProbe(String driverDir, String libraryName, String hookDir,
                                               String tmpDir, boolean useSystem);

    public static KgslInfo readKgsl() {
        KgslInfo info = new KgslInfo();
        if (!NATIVE_LOADED) {
            info.error = NATIVE_ERROR;
            return info;
        }
        try {
            String[] f = nativeReadKgsl();
            if (f == null || f.length < 5) {
                info.error = "KGSL query failed";
                return info;
            }
            info.available = "1".equals(f[0]);
            if (info.available) {
                info.chipId = Long.parseLong(f[1]);
                info.gpuId = (int) Long.parseLong(f[2]);
            }
            info.gpuModel = f[3];
            info.error = f[4];
        }
        catch (Throwable e) {
            info.available = false;
            info.chipId = -1;
            info.error = "KGSL query failed: " + e;
        }
        return info;
    }

    public static ProbeResult probe(Context ctx, String driverId) {
        ProbeResult result = new ProbeResult();
        try {
            boolean system = driverId == null || driverId.isEmpty() || SYSTEM_DRIVER.equals(driverId);
            String driverDir = "";
            String libraryName = "";
            String hookDir = "";
            String tmpDir = "";

            if (!system) {
                File dir = driverId.contains("/") || driverId.contains("..") ? null
                        : new File(new File(ctx.getFilesDir(), "contents/adrenotools"), driverId);
                libraryName = dir != null ? readLibraryName(new File(dir, "meta.json")) : "";
                if (libraryName.isEmpty() || !new File(dir, libraryName).isFile())
                    return failed(result, driverId, "load", "driver not installed");
                File tmp = new File(dir, "temp");
                tmp.mkdirs();
                driverDir = dir.getAbsolutePath() + "/";
                tmpDir = tmp.getAbsolutePath();
                hookDir = ctx.getApplicationInfo().nativeLibraryDir;
            }

            if (!NATIVE_LOADED) return failed(result, driverId, "load", NATIVE_ERROR);

            String[] f = nativeProbe(driverDir, libraryName, hookDir, tmpDir, system);
            if (f == null || f.length < 12) return failed(result, driverId, "load", "native probe failed");

            result.ok = "1".equals(f[0]);
            result.stage = f[1];
            result.vkResult = Integer.parseInt(f[2]);
            result.deviceName = f[3];
            result.vendorId = (int) Long.parseLong(f[4]);
            result.deviceId = (int) Long.parseLong(f[5]);
            result.apiVersion = f[6];
            result.driverVersion = f[7];
            result.vkDriverId = Integer.parseInt(f[8]);
            result.driverName = f[9];
            result.driverInfo = f[10];
            result.error = f[11];
            Log.i(TAG, "probe " + (driverId == null ? SYSTEM_DRIVER : driverId) + ": " + summary(result));
            return result;
        }
        catch (Throwable e) {
            return failed(result, driverId, "load", "probe failed: " + e);
        }
    }

    private static ProbeResult failed(ProbeResult result, String driverId, String stage, String error) {
        result.ok = false;
        result.stage = stage;
        result.error = error;
        Log.w(TAG, "probe " + driverId + ": " + summary(result));
        return result;
    }

    private static String summary(ProbeResult r) {
        if (r.ok) {
            return "ok device=" + r.deviceName + " api=" + r.apiVersion + " driver=" + r.driverName
                    + " " + r.driverVersion;
        }
        return "failed stage=" + r.stage + " vk=" + r.vkResult + " " + r.error;
    }

    private static String readLibraryName(File meta) {
        if (!meta.isFile()) return "";
        try (InputStream in = new FileInputStream(meta)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            String name = new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8))
                    .optString("libraryName", "");
            return name.contains("/") ? "" : name;
        }
        catch (IOException | JSONException e) {
            return "";
        }
    }
}

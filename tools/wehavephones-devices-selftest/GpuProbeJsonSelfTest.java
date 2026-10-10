package com.wehavephones.core;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Host check for GpuProbe: ProbeResult JSON round trip, tolerance of bad JSON, and the
 * never-throws behaviour when the native library is absent or the driver is not extracted.
 */
public class GpuProbeJsonSelfTest {
    private static int failures;

    private static void check(boolean condition, String what) {
        if (!condition) {
            failures++;
            System.err.println("FAIL: " + what);
        }
    }

    public static void main(String[] args) throws Exception {
        GpuProbe.ProbeResult r = new GpuProbe.ProbeResult();
        r.ok = true;
        r.stage = "ok";
        r.vkResult = -3;
        r.deviceName = "Turnip Adreno (TM) 840 \"quoted\"";
        r.vendorId = 0x5143;
        r.deviceId = 0x44050A21;
        r.apiVersion = "1.4.318";
        r.driverVersion = "26.3.0";
        r.vkDriverId = 18;
        r.driverName = "turnip Mesa driver";
        r.driverInfo = "Mesa 26.3.0-devel\nline2";
        r.error = "none";
        GpuProbe.ProbeResult b = GpuProbe.ProbeResult.fromJson(r.toJson());
        check(b.ok && "ok".equals(b.stage) && b.vkResult == -3, "ok/stage/vkResult round trip");
        check(r.deviceName.equals(b.deviceName), "deviceName round trip");
        check(b.vendorId == 0x5143 && b.deviceId == 0x44050A21, "vendor/device id round trip");
        check("1.4.318".equals(b.apiVersion) && "26.3.0".equals(b.driverVersion), "versions round trip");
        check(b.vkDriverId == 18 && r.driverName.equals(b.driverName), "driver id/name round trip");
        check(r.driverInfo.equals(b.driverInfo) && "none".equals(b.error), "driver info/error round trip");
        check(b.toJson().equals(r.toJson()), "toJson stable after round trip");

        GpuProbe.ProbeResult empty = GpuProbe.ProbeResult.fromJson("{}");
        check(!empty.ok && empty.stage.isEmpty() && empty.deviceName.isEmpty(), "empty object defaults");
        GpuProbe.ProbeResult bad = GpuProbe.ProbeResult.fromJson("not json");
        check(!bad.ok && "parse".equals(bad.stage) && !bad.error.isEmpty(), "invalid json handled");
        check(!GpuProbe.ProbeResult.fromJson(null).ok, "null json handled");

        // Host JVM has no libwehavephones: native paths must degrade, not throw.
        GpuProbe.KgslInfo kgsl = GpuProbe.readKgsl();
        check(!kgsl.available && kgsl.chipId == -1 && !kgsl.error.isEmpty(), "readKgsl without native");

        File files = Files.createTempDirectory("gpuprobe-selftest").toFile();
        Context ctx = new Context(files);
        GpuProbe.ProbeResult missing = GpuProbe.probe(ctx, "turnip-missing");
        check(!missing.ok && "load".equals(missing.stage) && "driver not installed".equals(missing.error),
                "missing driver -> load / driver not installed");
        check("load".equals(GpuProbe.probe(ctx, "../etc").stage), "path traversal id rejected");

        File dir = new File(files, "contents/adrenotools/turnip-test");
        check(dir.mkdirs(), "create driver dir");
        write(new File(dir, "meta.json"), "{\"libraryName\":\"vulkan.test.so\"}");
        check("driver not installed".equals(GpuProbe.probe(ctx, "turnip-test").error), "meta without library");
        write(new File(dir, "vulkan.test.so"), "x");
        GpuProbe.ProbeResult noNative = GpuProbe.probe(ctx, "turnip-test");
        check(!noNative.ok && "load".equals(noNative.stage) && noNative.error.contains("native library"),
                "installed driver without native library -> load error");
        check(new File(dir, "temp").isDirectory(), "tmp dir created");

        GpuProbe.ProbeResult system = GpuProbe.probe(ctx, "system");
        check(!system.ok && "load".equals(system.stage) && !system.error.isEmpty(), "system without native");

        if (failures > 0) {
            System.err.println(failures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("GpuProbeJsonSelfTest OK");
    }

    private static void write(File f, String text) throws Exception {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }
}

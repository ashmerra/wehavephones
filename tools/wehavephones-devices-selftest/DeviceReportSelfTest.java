package com.wehavephones.mobile.devices;

import com.wehavephones.core.GpuProbe;

import java.net.URLDecoder;
import java.util.LinkedHashMap;

/** Host test for the device report block and the prefilled issue link. */
public final class DeviceReportSelfTest {
    private static int failures;

    public static void main(String[] args) throws Exception {
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        fields.put("app", "0.0.1 (1) com.wehavephones");
        fields.put("rules", "remote v3");
        fields.put("rule", "adreno-84x");
        fields.put("profile", "adreno-84x (community)");
        fields.put("driver", "turnip-gen8-example (adrenotools)");
        fields.put("driver_probes", "turnip-gen8-example=ok; system=not-tried");
        DeviceReport.putLines(fields, "gl_renderer: Adreno (TM) 840\ngpu_vendor: adreno\nadreno_model: 840\nchip_id: 0x44050A21\n"
            + "kgsl_model:\nsoc: QTI SM8850\ndevice: OnePlus CPH2747\nandroid: 16 (SDK 36)\nfingerprint: OnePlus/CPH2747/x\n");
        GpuProbe.ProbeResult probe = new GpuProbe.ProbeResult();
        probe.ok = true;
        probe.deviceName = "Turnip Adreno (TM) 840";
        probe.apiVersion = "1.4.318";
        probe.driverVersion = "26.3.0";
        fields.put("vk_device", DeviceReport.vkDevice(probe));

        String text = DeviceReport.text(fields);
        check(text.startsWith("== WeHavePhones device report ==\napp: 0.0.1 (1) com.wehavephones\nrules: remote v3\n"), "header and first lines");
        check(text.endsWith("\nvk_device: Turnip Adreno (TM) 840; api 1.4.318; driver 26.3.0\n== end =="), "vk_device and footer");
        check(!text.contains("kgsl_model"), "empty values are left out");
        check(text.contains("chip_id: 0x44050A21\n"), "chip id kept");
        check("".equals(DeviceReport.vkDevice(null)), "no probe, no vk_device");

        String url = DeviceReport.issueUrl(fields, "0.0.1", "OnePlus CPH2747", "Adreno (TM) 840", "16");
        check(url.startsWith("https://github.com/ashmerra/wehavephones/issues/new?template=bug_report.yml&version=0.0.1&phone=OnePlus%20CPH2747&chipset=Adreno%20%28TM%29%20840&android=16&device-report="), "url prefix");
        check(url.endsWith("&source=In%20the%20app"), "source");
        check(!url.contains(" ") && !url.contains("\n"), "url is encoded");
        String report = URLDecoder.decode(url.substring(url.indexOf("device-report=") + 14, url.indexOf("&source=")), "UTF-8");
        check(report.equals(text), "report round trips through the url");

        fields.put("fingerprint", repeat("f", 8000));
        url = DeviceReport.issueUrl(fields, "0.0.1", "OnePlus CPH2747", "Adreno (TM) 840", "16");
        check(url.length() <= DeviceReport.MAX_URL_LENGTH, "long fingerprint is dropped to fit");
        check(url.contains("adreno_model") && url.contains("profile"), "core lines survive");
        check(!url.contains("fff"), "fingerprint dropped first");

        fields.put("fingerprint", repeat("f", 7000));
        fields.put("vk_device", repeat("v", 7000));
        fields.put("driver_probes", repeat("p", 7000));
        url = DeviceReport.issueUrl(fields, "0.0.1", "OnePlus CPH2747", "Adreno (TM) 840", "16");
        check(url.length() <= DeviceReport.MAX_URL_LENGTH, "all three long lines dropped to fit");
        check(!url.contains("vvv") && !url.contains("ppp") && !url.contains("fff"), "dropped lines are gone");
        check(url.contains("rule%3A%20adreno-84x"), "rule line survives");

        fields.put("soc", repeat("s", 8000));
        url = DeviceReport.issueUrl(fields, "0.0.1", "OnePlus CPH2747", "Adreno (TM) 840", "16");
        check(url.length() <= DeviceReport.MAX_URL_LENGTH && url.contains("&source=In%20the%20app"), "falls back to a link without the report");

        if (failures > 0) {
            System.out.println("DeviceReportSelfTest: " + failures + " failure(s)");
            System.exit(1);
        }
        System.out.println("DeviceReportSelfTest: all checks passed");
    }

    private static String repeat(String s, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(s);
        return b.toString();
    }

    private static void check(boolean ok, String name) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + name);
        }
    }
}

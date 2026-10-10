package com.wehavephones.mobile.devices;

import com.wehavephones.core.GpuProbe;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The plain-text device report block that Diagnostics copies and the "Report a problem" button
 * prefills into a GitHub issue. Only model, build and GPU data; no account names, emails or serials.
 */
public final class DeviceReport {
    public static final String HEADER = "== WeHavePhones device report ==";
    public static final String FOOTER = "== end ==";
    public static final String ISSUE_URL = "https://github.com/ashmerra/wehavephones/issues/new?template=bug_report.yml";
    public static final int MAX_URL_LENGTH = 7000;

    // Least useful first; the URL builder drops these in order when the link gets too long.
    private static final String[] DROP_ORDER = {"fingerprint", "vk_device", "driver_probes"};

    private DeviceReport() {}

    /** "Turnip Adreno (TM) 840; api 1.4.318; driver 26.3.0", or "" without a probe result. */
    public static String vkDevice(GpuProbe.ProbeResult probe) {
        if (probe == null || !probe.ok) return "";
        StringBuilder text = new StringBuilder(probe.deviceName);
        if (!probe.apiVersion.isEmpty()) text.append(text.length() > 0 ? "; " : "").append("api ").append(probe.apiVersion);
        if (!probe.driverVersion.isEmpty()) text.append(text.length() > 0 ? "; " : "").append("driver ").append(probe.driverVersion);
        return text.toString().replace('\n', ' ').trim();
    }

    /** Adds the "key: value" lines of {@code text} (for example DeviceIdentity.toReportText()) to {@code fields}. */
    public static void putLines(Map<String, String> fields, String text) {
        for (String line : text.split("\n")) {
            int colon = line.indexOf(": ");
            if (colon <= 0) continue;
            fields.put(line.substring(0, colon), line.substring(colon + 2).trim());
        }
    }

    /** The report block; empty values are left out. */
    public static String text(Map<String, String> fields) {
        StringBuilder text = new StringBuilder(HEADER).append('\n');
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            String value = entry.getValue() == null ? "" : entry.getValue().replace('\r', ' ').replace('\n', ' ').trim();
            if (!value.isEmpty()) text.append(entry.getKey()).append(": ").append(value).append('\n');
        }
        return text.append(FOOTER).toString();
    }

    /**
     * The prefilled issue-form link, kept under {@link #MAX_URL_LENGTH}: fingerprint, vk_device and
     * driver_probes are dropped first, then the device-report field itself.
     */
    public static String issueUrl(Map<String, String> fields, String version, String phone, String chipset, String android) {
        LinkedHashMap<String, String> kept = new LinkedHashMap<>(fields);
        String url = build(text(kept), version, phone, chipset, android);
        for (String key : DROP_ORDER) {
            if (url.length() <= MAX_URL_LENGTH) break;
            kept.remove(key);
            url = build(text(kept), version, phone, chipset, android);
        }
        if (url.length() > MAX_URL_LENGTH) url = build("", version, phone, chipset, android);
        return url;
    }

    private static String build(String report, String version, String phone, String chipset, String android) {
        StringBuilder url = new StringBuilder(ISSUE_URL);
        param(url, "version", version);
        param(url, "phone", phone);
        param(url, "chipset", chipset);
        param(url, "android", android);
        param(url, "device-report", report);
        param(url, "source", "In the app");
        return url.toString();
    }

    private static void param(StringBuilder url, String name, String value) {
        if (value == null || value.isEmpty()) return;
        url.append('&').append(name).append('=').append(encode(value));
    }

    static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        }
        catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}

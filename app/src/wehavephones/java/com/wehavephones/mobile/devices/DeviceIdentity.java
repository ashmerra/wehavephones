package com.wehavephones.mobile.devices;

import android.content.Context;
import android.os.Build;

import com.wehavephones.core.GPUInformation;
import com.wehavephones.core.GpuProbe;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the device rules match on: GL renderer, GPU vendor and Adreno model parsed from it, the
 * KGSL chip id, and Build fields. Only model and build data; nothing that identifies a person.
 */
public final class DeviceIdentity {
    public static final String VENDOR_ADRENO = "adreno";
    public static final String VENDOR_MALI = "mali";
    public static final String VENDOR_XCLIPSE = "xclipse";
    public static final String VENDOR_POWERVR = "powervr";
    public static final String VENDOR_OTHER = "other";
    // Empty renderer: legacy treats this as possibly Adreno; only the catch-all rule matches it.
    public static final String VENDOR_UNKNOWN = "unknown";

    private static final Pattern ADRENO_MODEL = Pattern.compile("adreno[^0-9]*([0-9]{3})");
    private static final Pattern MALI = Pattern.compile("(^|[^a-z])mali([^a-z]|$)");

    public String glRenderer = "";
    public String gpuVendor = VENDOR_UNKNOWN;
    /** 0 when the renderer has no Adreno model. */
    public int adrenoModel;
    /** -1 when the KGSL chip id is unavailable. */
    public long chipId = -1;
    public String kgslModel = "";
    public String socModel = "";
    public String socManufacturer = "";
    public String manufacturer = "";
    public String brand = "";
    public String model = "";
    public String device = "";
    public String release = "";
    public int sdkInt;
    public String fingerprint = "";

    public static DeviceIdentity detect(Context context) {
        DeviceIdentity id = new DeviceIdentity();
        String renderer = "";
        try {
            renderer = GPUInformation.getRenderer(context);
        }
        catch (RuntimeException e) {
            // Renderer stays empty; the catch-all rule covers it.
        }
        id.glRenderer = renderer != null ? renderer : "";
        id.gpuVendor = vendorFromRenderer(id.glRenderer);
        id.adrenoModel = adrenoModelFromRenderer(id.glRenderer);

        GpuProbe.KgslInfo kgsl = GpuProbe.readKgsl();
        if (kgsl != null) {
            if (kgsl.available) id.chipId = kgsl.chipId;
            id.kgslModel = kgsl.gpuModel != null ? kgsl.gpuModel : "";
        }

        id.manufacturer = nonNull(Build.MANUFACTURER);
        id.brand = nonNull(Build.BRAND);
        id.model = nonNull(Build.MODEL);
        id.device = nonNull(Build.DEVICE);
        id.release = nonNull(Build.VERSION.RELEASE);
        id.sdkInt = Build.VERSION.SDK_INT;
        id.fingerprint = nonNull(Build.FINGERPRINT);
        if (Build.VERSION.SDK_INT >= 31) {
            id.socModel = nonNull(Build.SOC_MODEL);
            id.socManufacturer = nonNull(Build.SOC_MANUFACTURER);
        }
        return id;
    }

    public static String vendorFromRenderer(String renderer) {
        if (renderer == null || renderer.trim().isEmpty()) return VENDOR_UNKNOWN;
        String text = renderer.toLowerCase(Locale.ENGLISH);
        if (text.contains("adreno")) return VENDOR_ADRENO;
        if (MALI.matcher(text).find() || text.contains("immortalis")) return VENDOR_MALI;
        if (text.contains("xclipse")) return VENDOR_XCLIPSE;
        if (text.contains("powervr")) return VENDOR_POWERVR;
        return VENDOR_OTHER;
    }

    /** "Adreno (TM) 740" gives 740; 0 when there is no three digit model. */
    public static int adrenoModelFromRenderer(String renderer) {
        if (renderer == null) return 0;
        Matcher matcher = ADRENO_MODEL.matcher(renderer.toLowerCase(Locale.ENGLISH));
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    public static String chipIdHex(long chipId) {
        if (chipId < 0) return "";
        return String.format(Locale.US, "0x%08X", chipId);
    }

    /** The identity lines of the device report block (see the key list in the report format). */
    public String toReportText() {
        StringBuilder text = new StringBuilder();
        line(text, "gl_renderer", glRenderer);
        line(text, "gpu_vendor", gpuVendor);
        line(text, "adreno_model", adrenoModel > 0 ? String.valueOf(adrenoModel) : "");
        line(text, "chip_id", chipIdHex(chipId));
        line(text, "kgsl_model", kgslModel);
        line(text, "soc", join(socManufacturer, socModel));
        line(text, "device", join(manufacturer, model));
        line(text, "android", release + " (SDK " + sdkInt + ")");
        line(text, "fingerprint", fingerprint);
        return text.toString();
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("glRenderer", glRenderer);
            json.put("gpuVendor", gpuVendor);
            json.put("adrenoModel", adrenoModel);
            json.put("chipId", chipIdHex(chipId));
            json.put("kgslModel", kgslModel);
            json.put("socModel", socModel);
            json.put("socManufacturer", socManufacturer);
            json.put("manufacturer", manufacturer);
            json.put("brand", brand);
            json.put("model", model);
            json.put("device", device);
            json.put("release", release);
            json.put("sdkInt", sdkInt);
            json.put("fingerprint", fingerprint);
        }
        catch (JSONException ignored) {
        }
        return json;
    }

    private static void line(StringBuilder text, String key, String value) {
        text.append(key).append(": ").append(oneLine(value)).append('\n');
    }

    private static String join(String a, String b) {
        String left = a != null ? a.trim() : "";
        String right = b != null ? b.trim() : "";
        if (left.isEmpty()) return right;
        if (right.isEmpty()) return left;
        return left + " " + right;
    }

    private static String oneLine(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static String nonNull(String value) {
        return value != null ? value : "";
    }
}

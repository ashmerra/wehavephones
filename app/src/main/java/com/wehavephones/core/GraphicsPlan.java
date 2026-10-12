package com.wehavephones.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;

/**
 * The graphics setup chosen for one session: which Vulkan driver the wrapper loads, extra
 * environment, and whether the BCn compat layer is installed. Plain data so the session code
 * and the diagnostics report can share it.
 */
public final class GraphicsPlan {
    public String driverId = "system";
    public boolean useAdrenotools;
    public final LinkedHashMap<String, String> env = new LinkedHashMap<>();
    public boolean vulkanCompatLayer;
    public String wowApiArg;
    /** The driver picked on the Play screen, or "" for Automatic; driverId says what actually ran. */
    public String driverChoice = "";
    public String ruleId = "", profileName = "", profileStatus = "", rulesSource = "";
    public int rulesVersion;
    public final ArrayList<String> log = new ArrayList<>();

    public String toJson() {
        try {
            JSONObject json = new JSONObject();
            json.put("driverId", driverId);
            json.put("useAdrenotools", useAdrenotools);
            JSONObject envJson = new JSONObject();
            for (java.util.Map.Entry<String, String> entry : env.entrySet()) envJson.put(entry.getKey(), entry.getValue());
            json.put("env", envJson);
            json.put("vulkanCompatLayer", vulkanCompatLayer);
            json.put("wowApiArg", wowApiArg != null ? wowApiArg : JSONObject.NULL);
            json.put("driverChoice", driverChoice);
            json.put("ruleId", ruleId);
            json.put("profileName", profileName);
            json.put("profileStatus", profileStatus);
            json.put("rulesSource", rulesSource);
            json.put("rulesVersion", rulesVersion);
            json.put("log", new JSONArray(log));
            return json.toString();
        }
        catch (JSONException e) {
            return "{}";
        }
    }

    /** Returns null when the text is not a plan. */
    public static GraphicsPlan fromJson(String text) {
        if (text == null || text.isEmpty()) return null;
        try {
            JSONObject json = new JSONObject(text);
            GraphicsPlan plan = new GraphicsPlan();
            plan.driverId = json.optString("driverId", "system");
            plan.useAdrenotools = json.optBoolean("useAdrenotools", false);
            JSONObject envJson = json.optJSONObject("env");
            if (envJson != null) {
                Iterator<String> keys = envJson.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    plan.env.put(key, envJson.optString(key, ""));
                }
            }
            plan.vulkanCompatLayer = json.optBoolean("vulkanCompatLayer", false);
            plan.wowApiArg = json.isNull("wowApiArg") ? null : json.optString("wowApiArg", null);
            plan.driverChoice = json.optString("driverChoice", "");
            plan.ruleId = json.optString("ruleId", "");
            plan.profileName = json.optString("profileName", "");
            plan.profileStatus = json.optString("profileStatus", "");
            plan.rulesSource = json.optString("rulesSource", "");
            plan.rulesVersion = json.optInt("rulesVersion", 0);
            JSONArray lines = json.optJSONArray("log");
            if (lines != null) {
                for (int i = 0; i < lines.length(); i++) plan.log.add(lines.optString(i, ""));
            }
            return plan;
        }
        catch (JSONException e) {
            return null;
        }
    }
}

package com.wehavephones.mobile.devices;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The parsed and strictly validated device rules file: drivers, profiles, and an ordered rule
 * list ending in a catch-all. Unknown keys are ignored, except in a rule's match: a rule that
 * asks for something this build cannot evaluate never matches.
 */
public final class DeviceRules {
    public static final int SUPPORTED_SCHEMA = 1;
    public static final String SYSTEM_DRIVER = "system";

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern ASSET = Pattern.compile("graphics_driver/[A-Za-z0-9._-]+\\.(tzst|zip)");
    private static final Pattern LIBRARY = Pattern.compile("[A-Za-z0-9._-]+\\.so");
    private static final Pattern ENV_KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern API_ARG = Pattern.compile("-[A-Za-z0-9]{1,16}");
    private static final Pattern MODEL = Pattern.compile("([0-9]{3})(?:-([0-9]{3}))?");
    private static final Pattern CHIP = Pattern.compile("0[xX]((?:[0-9A-Fa-f]{2}){1,8})");
    // Rules must not redirect the loader or the Vulkan ICD choice.
    private static final String[] BLOCKED_ENV_PREFIXES = {"LD_", "ADRENOTOOLS_", "VK_"};
    private static final String[] VENDORS = {
        DeviceIdentity.VENDOR_ADRENO, DeviceIdentity.VENDOR_MALI, DeviceIdentity.VENDOR_XCLIPSE,
        DeviceIdentity.VENDOR_POWERVR, DeviceIdentity.VENDOR_OTHER, DeviceIdentity.VENDOR_UNKNOWN
    };

    public static final class Driver {
        public final String id;
        public final String type;
        public final String description;
        /** APK asset path, or null. */
        public final String asset;
        /** https download for the archive, or null. */
        public final String url;
        public final String sha256;
        public final String libraryName;
        public final String librarySha256;
        public final String source;

        Driver(String id, String type, String description, String asset, String url, String sha256,
               String libraryName, String librarySha256, String source) {
            this.id = id;
            this.type = type;
            this.description = description;
            this.asset = asset;
            this.url = url;
            this.sha256 = sha256;
            this.libraryName = libraryName;
            this.librarySha256 = librarySha256;
            this.source = source;
        }

        public boolean isSystem() {
            return "system".equals(type);
        }

        /** "zip" or "tzst", from the asset or download name. */
        public String archiveKind() {
            String name = asset != null ? asset : url != null ? url : "";
            int query = name.indexOf('?');
            if (query >= 0) name = name.substring(0, query);
            return name.toLowerCase(Locale.ROOT).endsWith(".zip") ? "zip" : "tzst";
        }
    }

    public static final class Profile {
        public final String name;
        public final String status;
        public final List<String> drivers;
        /** The same drivers resolved against the rules' driver table. */
        public final List<Driver> driverEntries;
        /** Extra drivers the picker offers; Automatic never tries them. */
        public final List<Driver> choiceEntries;
        public final LinkedHashMap<String, String> env;
        public final boolean vulkanCompatLayer;
        /** Null keeps the current default WoW graphics API argument. */
        public final String wowApiArg;
        public final String notes;

        Profile(String name, String status, List<String> drivers, List<Driver> driverEntries, List<Driver> choiceEntries,
                LinkedHashMap<String, String> env, boolean vulkanCompatLayer, String wowApiArg, String notes) {
            this.name = name;
            this.status = status;
            this.drivers = Collections.unmodifiableList(drivers);
            this.driverEntries = Collections.unmodifiableList(driverEntries);
            this.choiceEntries = Collections.unmodifiableList(choiceEntries);
            this.env = env;
            this.vulkanCompatLayer = vulkanCompatLayer;
            this.wowApiArg = wowApiArg;
            this.notes = notes;
        }

        /** What the driver picker lists after Automatic: the profile's drivers, then its choices. */
        public List<Driver> pickerDrivers() {
            ArrayList<Driver> list = new ArrayList<>(driverEntries);
            list.addAll(choiceEntries);
            return Collections.unmodifiableList(list);
        }

        /** The picker entry with this id, or null when the profile does not offer it. */
        public Driver pickerDriver(String id) {
            for (Driver driver : pickerDrivers()) {
                if (driver.id.equals(id)) return driver;
            }
            return null;
        }
    }

    /** The match criteria of one rule; every present key must match. */
    public static final class Criteria {
        final List<String> vendors = new ArrayList<>();
        final List<Integer> adrenoSeries = new ArrayList<>();
        final List<int[]> adrenoModels = new ArrayList<>();
        final List<long[]> chipIds = new ArrayList<>();
        final List<String> socModels = new ArrayList<>();
        final List<String> manufacturers = new ArrayList<>();
        Integer minSdk, maxSdk;
        boolean unevaluable;

        public boolean isEmpty() {
            return vendors.isEmpty() && adrenoSeries.isEmpty() && adrenoModels.isEmpty() && chipIds.isEmpty()
                && socModels.isEmpty() && manufacturers.isEmpty() && minSdk == null && maxSdk == null
                && !unevaluable;
        }

        public boolean matches(DeviceIdentity id) {
            if (unevaluable) return false;
            if (!vendors.isEmpty() && !vendors.contains(id.gpuVendor)) return false;
            if (!adrenoSeries.isEmpty() && (id.adrenoModel <= 0 || !adrenoSeries.contains(id.adrenoModel / 100))) return false;
            if (!adrenoModels.isEmpty()) {
                boolean found = false;
                if (id.adrenoModel > 0) {
                    for (int[] range : adrenoModels) {
                        if (id.adrenoModel >= range[0] && id.adrenoModel <= range[1]) found = true;
                    }
                }
                if (!found) return false;
            }
            if (!chipIds.isEmpty()) {
                boolean found = false;
                if (id.chipId >= 0) {
                    for (long[] pattern : chipIds) {
                        if (chipMatches(pattern[0], pattern[1], id.chipId)) found = true;
                    }
                }
                if (!found) return false;
            }
            if (!socModels.isEmpty() && !containsIgnoreCase(socModels, id.socModel)) return false;
            if (!manufacturers.isEmpty() && !containsIgnoreCase(manufacturers, id.manufacturer)) return false;
            if (minSdk != null && id.sdkInt < minSdk) return false;
            if (maxSdk != null && id.sdkInt > maxSdk) return false;
            return true;
        }

        private static boolean containsIgnoreCase(List<String> values, String value) {
            if (value == null || value.isEmpty()) return false;
            for (String candidate : values) {
                if (candidate.equalsIgnoreCase(value)) return true;
            }
            return false;
        }
    }

    public static final class Rule {
        public final String id;
        public final Criteria criteria;
        public final String profile;
        public final String source;

        Rule(String id, Criteria criteria, String profile, String source) {
            this.id = id;
            this.criteria = criteria;
            this.profile = profile;
            this.source = source;
        }
    }

    /** Result of matching an identity: the first rule that matched and its profile. */
    public static final class Match {
        public final Rule rule;
        public final Profile profile;

        Match(Rule rule, Profile profile) {
            this.rule = rule;
            this.profile = profile;
        }
    }

    private final int schema;
    private final int rulesVersion;
    private final int minAppVersionCode;
    private final String updatedAt;
    private final LinkedHashMap<String, Driver> drivers;
    private final LinkedHashMap<String, Profile> profiles;
    private final List<Rule> rules;

    private DeviceRules(int schema, int rulesVersion, int minAppVersionCode, String updatedAt,
                        LinkedHashMap<String, Driver> drivers, LinkedHashMap<String, Profile> profiles,
                        List<Rule> rules) {
        this.schema = schema;
        this.rulesVersion = rulesVersion;
        this.minAppVersionCode = minAppVersionCode;
        this.updatedAt = updatedAt;
        this.drivers = drivers;
        this.profiles = profiles;
        this.rules = Collections.unmodifiableList(rules);
    }

    public int getSchema() { return schema; }
    public int getRulesVersion() { return rulesVersion; }
    public int getMinAppVersionCode() { return minAppVersionCode; }
    public String getUpdatedAt() { return updatedAt; }
    public Map<String, Driver> getDrivers() { return Collections.unmodifiableMap(drivers); }
    public Map<String, Profile> getProfiles() { return Collections.unmodifiableMap(profiles); }
    public List<Rule> getRules() { return rules; }
    public Driver getDriver(String id) { return drivers.get(id); }
    public Profile getProfile(String name) { return profiles.get(name); }

    /** First rule whose criteria all match; the validated catch-all guarantees a result. */
    public Match match(DeviceIdentity identity) {
        for (Rule rule : rules) {
            if (rule.criteria.matches(identity)) return new Match(rule, profiles.get(rule.profile));
        }
        Rule last = rules.get(rules.size() - 1);
        return new Match(last, profiles.get(last.profile));
    }

    /** True when an FF byte in the pattern (wildcard) lets the chip id match. */
    static boolean chipMatches(long pattern, long wildcardMask, long chipId) {
        return (chipId & ~wildcardMask) == (pattern & ~wildcardMask);
    }

    public static DeviceRules parse(String text) throws JSONException {
        if (text == null) throw new JSONException("rules are null");
        JSONObject root = new JSONObject(text);

        int schema = requireInt(root, "schema", "rules");
        if (schema < 1) throw new JSONException("schema must be at least 1");
        if (schema > SUPPORTED_SCHEMA) throw new JSONException("unsupported schema " + schema);
        int rulesVersion = requireInt(root, "rulesVersion", "rules");
        if (rulesVersion < 1) throw new JSONException("rulesVersion must be at least 1");
        int minApp = optInt(root, "minAppVersionCode", 0, "rules");
        if (minApp < 0) throw new JSONException("minAppVersionCode must not be negative");
        String updatedAt = optString(root, "updatedAt", "", "rules");

        LinkedHashMap<String, Driver> drivers = new LinkedHashMap<>();
        JSONObject driversJson = requireObject(root, "drivers", "rules");
        Iterator<String> driverIds = driversJson.keys();
        while (driverIds.hasNext()) {
            String id = driverIds.next();
            drivers.put(id, parseDriver(id, requireObject(driversJson, id, "drivers")));
        }
        Driver system = drivers.get(SYSTEM_DRIVER);
        if (system == null || !system.isSystem()) throw new JSONException("drivers.system of type system is required");

        LinkedHashMap<String, Profile> profiles = new LinkedHashMap<>();
        JSONObject profilesJson = requireObject(root, "profiles", "rules");
        Iterator<String> profileNames = profilesJson.keys();
        while (profileNames.hasNext()) {
            String name = profileNames.next();
            profiles.put(name, parseProfile(name, requireObject(profilesJson, name, "profiles"), drivers));
        }
        if (profiles.isEmpty()) throw new JSONException("no profiles");

        ArrayList<Rule> rules = new ArrayList<>();
        Object rulesValue = root.opt("rules");
        if (!(rulesValue instanceof JSONArray)) throw new JSONException("rules must be an array");
        JSONArray rulesJson = (JSONArray)rulesValue;
        java.util.HashSet<String> ruleIds = new java.util.HashSet<>();
        for (int i = 0; i < rulesJson.length(); i++) {
            Object value = rulesJson.get(i);
            if (!(value instanceof JSONObject)) throw new JSONException("rules[" + i + "] must be an object");
            Rule rule = parseRule((JSONObject)value, i, profiles);
            if (!ruleIds.add(rule.id)) throw new JSONException("duplicate rule id " + rule.id);
            rules.add(rule);
        }
        if (rules.isEmpty()) throw new JSONException("no rules");
        for (int i = 0; i < rules.size() - 1; i++) {
            if (rules.get(i).criteria.isEmpty()) {
                throw new JSONException("catch-all rule " + rules.get(i).id + " must be last");
            }
        }
        if (!rules.get(rules.size() - 1).criteria.isEmpty()) throw new JSONException("the last rule must be a catch-all with an empty match");

        return new DeviceRules(schema, rulesVersion, minApp, updatedAt, drivers, profiles, rules);
    }

    private static Driver parseDriver(String id, JSONObject json) throws JSONException {
        if (!ID.matcher(id).matches()) throw new JSONException("invalid driver id " + id);
        String where = "drivers." + id;
        String type = requireString(json, "type", where);
        String description = optString(json, "description", "", where);
        if ("system".equals(type)) {
            if (!SYSTEM_DRIVER.equals(id)) throw new JSONException(where + ": only 'system' may be of type system");
            return new Driver(id, type, description, null, null, null, null, null, optString(json, "source", "", where));
        }
        if (!"adrenotools".equals(type)) throw new JSONException(where + ": unknown type " + type);
        if (SYSTEM_DRIVER.equals(id)) throw new JSONException(where + ": 'system' must be of type system");

        String asset = optNullableString(json, "asset", where);
        String url = optNullableString(json, "url", where);
        if (asset == null && url == null) throw new JSONException(where + ": needs an asset or a url");
        if (asset != null && !ASSET.matcher(asset).matches()) throw new JSONException(where + ": invalid asset path");
        if (url != null && !url.startsWith("https://")) throw new JSONException(where + ": url must be https");
        if (url != null && !url.matches("https://[^\\s]+")) throw new JSONException(where + ": invalid url");
        if (url != null && asset == null && !urlPath(url).matches(".*\\.(tzst|zip)")) {
            throw new JSONException(where + ": url must name a .tzst or .zip archive");
        }
        String sha256 = requireString(json, "sha256", where).toLowerCase(Locale.ROOT);
        if (!SHA256.matcher(sha256).matches()) throw new JSONException(where + ": invalid sha256");
        String libraryName = requireString(json, "libraryName", where);
        if (!LIBRARY.matcher(libraryName).matches()) throw new JSONException(where + ": invalid libraryName");
        String librarySha256 = requireString(json, "librarySha256", where).toLowerCase(Locale.ROOT);
        if (!SHA256.matcher(librarySha256).matches()) throw new JSONException(where + ": invalid librarySha256");
        return new Driver(id, type, description, asset, url, sha256, libraryName, librarySha256,
            optString(json, "source", "", where));
    }

    private static String urlPath(String url) {
        int end = url.length();
        int query = url.indexOf('?');
        if (query >= 0) end = Math.min(end, query);
        int fragment = url.indexOf('#');
        if (fragment >= 0) end = Math.min(end, fragment);
        return url.substring(0, end).toLowerCase(Locale.ROOT);
    }

    private static Profile parseProfile(String name, JSONObject json, Map<String, Driver> drivers) throws JSONException {
        if (!ID.matcher(name).matches()) throw new JSONException("invalid profile name " + name);
        String where = "profiles." + name;
        String status = requireString(json, "status", where);
        if (!status.equals("tested") && !status.equals("community") && !status.equals("untested")) {
            throw new JSONException(where + ": unknown status " + status);
        }
        Object driversValue = json.opt("drivers");
        if (!(driversValue instanceof JSONArray) || ((JSONArray)driversValue).length() == 0) {
            throw new JSONException(where + ": drivers must be a non-empty array");
        }
        ArrayList<String> driverList = new ArrayList<>();
        ArrayList<Driver> driverEntries = new ArrayList<>();
        JSONArray driversJson = (JSONArray)driversValue;
        for (int i = 0; i < driversJson.length(); i++) {
            Object value = driversJson.get(i);
            if (!(value instanceof String)) throw new JSONException(where + ": drivers must be strings");
            String id = (String)value;
            if (!drivers.containsKey(id)) throw new JSONException(where + ": unknown driver " + id);
            if (driverList.contains(id)) throw new JSONException(where + ": duplicate driver " + id);
            driverList.add(id);
            driverEntries.add(drivers.get(id));
        }
        ArrayList<Driver> choiceEntries = new ArrayList<>();
        if (json.has("choices") && !json.isNull("choices")) {
            Object choicesValue = json.get("choices");
            if (!(choicesValue instanceof JSONArray)) throw new JSONException(where + ": choices must be an array");
            JSONArray choicesJson = (JSONArray)choicesValue;
            for (int i = 0; i < choicesJson.length(); i++) {
                Object value = choicesJson.get(i);
                if (!(value instanceof String)) throw new JSONException(where + ": choices must be strings");
                String id = (String)value;
                if (!drivers.containsKey(id)) throw new JSONException(where + ": unknown choice " + id);
                if (driverList.contains(id) || choiceEntries.contains(drivers.get(id))) {
                    throw new JSONException(where + ": duplicate choice " + id);
                }
                choiceEntries.add(drivers.get(id));
            }
        }
        LinkedHashMap<String, String> env = new LinkedHashMap<>();
        if (json.has("env") && !json.isNull("env")) {
            JSONObject envJson = requireObject(json, "env", where);
            Iterator<String> keys = envJson.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object value = envJson.get(key);
                if (!ENV_KEY.matcher(key).matches()) throw new JSONException(where + ": invalid env name " + key);
                for (String prefix : BLOCKED_ENV_PREFIXES) {
                    if (key.startsWith(prefix)) throw new JSONException(where + ": env " + key + " is not allowed");
                }
                if (!(value instanceof String)) throw new JSONException(where + ": env " + key + " must be a string");
                String text = (String)value;
                if (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0 || text.indexOf('\0') >= 0 || text.indexOf(' ') >= 0) {
                    throw new JSONException(where + ": env " + key + " has whitespace");
                }
                env.put(key, text);
            }
        }
        boolean compat = false;
        if (json.has("vulkanCompatLayer")) {
            Object value = json.get("vulkanCompatLayer");
            if (!(value instanceof Boolean)) throw new JSONException(where + ": vulkanCompatLayer must be a boolean");
            compat = (Boolean)value;
        }
        String apiArg = optNullableString(json, "wowApiArg", where);
        if (apiArg != null && !API_ARG.matcher(apiArg).matches()) throw new JSONException(where + ": invalid wowApiArg");
        return new Profile(name, status, driverList, driverEntries, choiceEntries, env, compat, apiArg,
            optString(json, "notes", "", where));
    }

    private static Rule parseRule(JSONObject json, int index, Map<String, Profile> profiles) throws JSONException {
        String where = "rules[" + index + "]";
        String id = requireString(json, "id", where);
        if (!ID.matcher(id).matches()) throw new JSONException(where + ": invalid rule id " + id);
        String profile = requireString(json, "profile", where);
        if (!profiles.containsKey(profile)) throw new JSONException(where + " (" + id + "): unknown profile " + profile);
        Criteria criteria = parseCriteria(requireObject(json, "match", where), id);
        return new Rule(id, criteria, profile, optString(json, "source", "", where));
    }

    private static Criteria parseCriteria(JSONObject json, String ruleId) throws JSONException {
        String where = "rule " + ruleId + " match";
        Criteria criteria = new Criteria();
        Iterator<String> keys = json.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            switch (key) {
                case "gpuVendor":
                    for (String vendor : stringList(json, key, where)) {
                        if (!java.util.Arrays.asList(VENDORS).contains(vendor)) throw new JSONException(where + ": unknown gpuVendor " + vendor);
                        criteria.vendors.add(vendor);
                    }
                    break;
                case "adrenoSeries": {
                    JSONArray values = requireArray(json, key, where);
                    for (int i = 0; i < values.length(); i++) {
                        Object value = values.get(i);
                        if (!(value instanceof Integer) || (Integer)value < 1 || (Integer)value > 99) {
                            throw new JSONException(where + ": adrenoSeries must be small positive integers");
                        }
                        criteria.adrenoSeries.add((Integer)value);
                    }
                    break;
                }
                case "adrenoModel":
                    for (String model : stringList(json, key, where)) {
                        Matcher matcher = MODEL.matcher(model);
                        if (!matcher.matches()) throw new JSONException(where + ": invalid adrenoModel " + model);
                        int low = Integer.parseInt(matcher.group(1));
                        int high = matcher.group(2) != null ? Integer.parseInt(matcher.group(2)) : low;
                        if (high < low) throw new JSONException(where + ": reversed adrenoModel range " + model);
                        criteria.adrenoModels.add(new int[] {low, high});
                    }
                    break;
                case "chipId":
                    for (String chip : stringList(json, key, where)) {
                        Matcher matcher = CHIP.matcher(chip);
                        if (!matcher.matches()) throw new JSONException(where + ": invalid chipId " + chip);
                        criteria.chipIds.add(parseChip(matcher.group(1)));
                    }
                    break;
                case "socModel":
                    criteria.socModels.addAll(stringList(json, key, where));
                    break;
                case "manufacturer":
                    criteria.manufacturers.addAll(stringList(json, key, where));
                    break;
                case "minSdk":
                    criteria.minSdk = requireInt(json, key, where);
                    break;
                case "maxSdk":
                    criteria.maxSdk = requireInt(json, key, where);
                    break;
                default:
                    // A newer file may use a criterion this build cannot test; do not guess.
                    criteria.unevaluable = true;
            }
        }
        return criteria;
    }

    /** Pattern value and wildcard mask (bits of FF bytes) for a hex chip id pattern. */
    private static long[] parseChip(String hex) {
        long value = 0;
        long mask = 0;
        for (int i = 0; i < hex.length(); i += 2) {
            int part = Integer.parseInt(hex.substring(i, i + 2), 16);
            value = (value << 8) | part;
            mask = (mask << 8) | (part == 0xFF ? 0xFFL : 0L);
        }
        return new long[] {value, mask};
    }

    private static List<String> stringList(JSONObject json, String key, String where) throws JSONException {
        JSONArray values = requireArray(json, key, where);
        ArrayList<String> list = new ArrayList<>();
        for (int i = 0; i < values.length(); i++) {
            Object value = values.get(i);
            if (!(value instanceof String) || ((String)value).isEmpty()) throw new JSONException(where + ": " + key + " must be non-empty strings");
            list.add((String)value);
        }
        return list;
    }

    private static JSONArray requireArray(JSONObject json, String key, String where) throws JSONException {
        Object value = json.opt(key);
        if (!(value instanceof JSONArray) || ((JSONArray)value).length() == 0) {
            throw new JSONException(where + ": " + key + " must be a non-empty array");
        }
        return (JSONArray)value;
    }

    private static JSONObject requireObject(JSONObject json, String key, String where) throws JSONException {
        Object value = json.opt(key);
        if (!(value instanceof JSONObject)) throw new JSONException(where + ": " + key + " must be an object");
        return (JSONObject)value;
    }

    private static String requireString(JSONObject json, String key, String where) throws JSONException {
        Object value = json.opt(key);
        if (!(value instanceof String) || ((String)value).isEmpty()) throw new JSONException(where + ": " + key + " must be a non-empty string");
        return (String)value;
    }

    private static String optString(JSONObject json, String key, String fallback, String where) throws JSONException {
        if (!json.has(key) || json.isNull(key)) return fallback;
        Object value = json.get(key);
        if (!(value instanceof String)) throw new JSONException(where + ": " + key + " must be a string");
        return (String)value;
    }

    private static String optNullableString(JSONObject json, String key, String where) throws JSONException {
        String value = optString(json, key, null, where);
        return value != null && value.isEmpty() ? null : value;
    }

    private static int requireInt(JSONObject json, String key, String where) throws JSONException {
        Object value = json.opt(key);
        if (!(value instanceof Integer)) throw new JSONException(where + ": " + key + " must be an integer");
        return (Integer)value;
    }

    private static int optInt(JSONObject json, String key, int fallback, String where) throws JSONException {
        return json.has(key) && !json.isNull(key) ? requireInt(json, key, where) : fallback;
    }
}

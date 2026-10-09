package com.wehavephones.mobile;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The install records the Battle.net Agent writes into the shared
 * {@code World of Warcraft} folder (wowdev TACT):
 * <ul>
 *   <li>{@code .build.info} at the root: pipe-separated table, one row per
 *       product sharing the storage ({@code Product}, {@code Version},
 *       {@code Branch}, {@code Active}, {@code Build Key}, …). Older installs may
 *       also carry one inside the product folder.</li>
 *   <li>{@code .flavor.info} in each product folder: the product code
 *       ({@code wow}, {@code wowt}, {@code wow_classic_beta}, …).</li>
 * </ul>
 * Column names are lower-cased with the {@code !TYPE:size} suffix removed.
 * Pure Java (host self-tested).
 */
public final class BattleNetBuildInfo {
    public static final String BUILD_INFO = ".build.info";
    public static final String FLAVOR_INFO = ".flavor.info";

    public final ArrayList<Map<String, String>> rows = new ArrayList<>();

    public static BattleNetBuildInfo read(File file) {
        String text = readText(file);
        return text != null ? parse(text) : new BattleNetBuildInfo();
    }

    public static BattleNetBuildInfo parse(String text) {
        BattleNetBuildInfo info = new BattleNetBuildInfo();
        String[] header = null;
        for (String line : text.split("\\r?\\n")) {
            if (line.trim().isEmpty() || line.startsWith("#")) continue;
            String[] cells = line.split("\\|", -1);
            if (header == null) {
                header = new String[cells.length];
                for (int i = 0; i < cells.length; i++) {
                    int type = cells[i].indexOf('!');
                    header[i] = (type >= 0 ? cells[i].substring(0, type) : cells[i]).trim().toLowerCase(Locale.US);
                }
                continue;
            }
            LinkedHashMap<String, String> row = new LinkedHashMap<>();
            for (int i = 0; i < header.length && i < cells.length; i++) row.put(header[i], cells[i].trim());
            info.rows.add(row);
        }
        return info;
    }

    /** Row of a product (the active one when there are several); null if absent. */
    public Map<String, String> rowFor(String productCode) {
        Map<String, String> fallback = null;
        for (Map<String, String> row : rows) {
            if (!value(row, "product").equalsIgnoreCase(productCode)) continue;
            if ("1".equals(value(row, "active"))) return row;
            if (fallback == null) fallback = row;
        }
        return fallback;
    }

    /** Product codes listed in the table, in order, without duplicates. */
    public List<String> productCodes() {
        ArrayList<String> codes = new ArrayList<>();
        for (Map<String, String> row : rows) {
            String code = value(row, "product").toLowerCase(Locale.US);
            if (!code.isEmpty() && !codes.contains(code)) codes.add(code);
        }
        return codes;
    }

    /** The only row of a single-product table (legacy per-folder file); null otherwise. */
    public Map<String, String> singleRow() {
        return rows.size() == 1 ? rows.get(0) : null;
    }

    public static String value(Map<String, String> row, String column) {
        if (row == null) return "";
        String value = row.get(column);
        return value != null ? value : "";
    }

    /** Product code from a {@code .flavor.info} file; "" when missing or unreadable. */
    public static String readFlavorCode(File file) {
        String text = readText(file);
        if (text == null) return "";
        BattleNetBuildInfo info = parse(text);
        if (info.rows.isEmpty()) return "";
        Map<String, String> row = info.rows.get(0);
        String code = value(row, "product flavor");
        if (code.isEmpty() && !row.isEmpty()) code = row.values().iterator().next();
        return code.trim().toLowerCase(Locale.US);
    }

    private static String readText(File file) {
        if (file == null || !file.isFile()) return null;
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).replace("﻿", "");
        }
        catch (IOException e) {
            return null;
        }
    }
}

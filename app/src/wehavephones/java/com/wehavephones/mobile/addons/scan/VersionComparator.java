package com.wehavephones.mobile.addons.scan;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Tolerant version comparison. Never compares version strings lexically:
 * "1.10" is newer than "1.9", "v12.1.11" equals "12.1.11".
 *
 * Pure Java (host-testable).
 */
public final class VersionComparator {
    private VersionComparator() {}

    public static boolean isUnknown(String version) {
        if (version == null) return true;
        String trimmed = version.trim();
        if (trimmed.isEmpty()) return true;
        return trimmed.indexOf('@') >= 0;
    }

    /** Returns a positive value when {@code left} is newer than {@code right}. */
    public static int compare(String left, String right) {
        if (isUnknown(left) && isUnknown(right)) return 0;
        if (isUnknown(left)) return -1;
        if (isUnknown(right)) return 1;

        List<String> first = tokenize(stripPrefix(left));
        List<String> second = tokenize(stripPrefix(right));
        int count = Math.max(first.size(), second.size());
        for (int i = 0; i < count; i++) {
            String a = i < first.size() ? first.get(i) : "0";
            String b = i < second.size() ? second.get(i) : "0";
            int result = compareToken(a, b);
            if (result != 0) return result;
        }
        return 0;
    }

    public static boolean isNewer(String candidate, String installed) {
        if (isUnknown(candidate)) return false;
        if (isUnknown(installed)) return true;
        return compare(candidate, installed) > 0;
    }

    public static boolean isSame(String first, String second) {
        if (isUnknown(first) || isUnknown(second)) return false;
        return compare(first, second) == 0;
    }

    private static int compareToken(String a, String b) {
        boolean aNumeric = isNumeric(a);
        boolean bNumeric = isNumeric(b);
        if (aNumeric && bNumeric) {
            BigInteger left = new BigInteger(a);
            BigInteger right = new BigInteger(b);
            return left.compareTo(right);
        }
        if (aNumeric != bNumeric) {
            // "3a" is newer than "3": a numeric token with a trailing letter
            // suffix sorts above the bare number.
            if (a.length() > b.length() && a.startsWith(b) && isAlphabetic(a.substring(b.length()))) return 1;
            if (b.length() > a.length() && b.startsWith(a) && isAlphabetic(b.substring(a.length()))) return -1;
            return aNumeric ? 1 : -1;
        }
        return a.toLowerCase(Locale.US).compareTo(b.toLowerCase(Locale.US));
    }

    private static boolean isAlphabetic(String value) {
        if (value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isLetter(value.charAt(i))) return false;
        }
        return true;
    }

    private static String stripPrefix(String version) {
        String trimmed = version.trim();
        if ((trimmed.startsWith("v") || trimmed.startsWith("V")) && trimmed.length() > 1
                && Character.isDigit(trimmed.charAt(1))) {
            return trimmed.substring(1);
        }
        return trimmed;
    }

    private static boolean isNumeric(String value) {
        if (value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) return false;
        }
        return true;
    }

    private static List<String> tokenize(String version) {
        ArrayList<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < version.length(); i++) {
            char character = version.charAt(i);
            if (Character.isLetterOrDigit(character)) {
                current.append(character);
            }
            else if (current.length() > 0) {
                tokens.add(current.toString());
                current.setLength(0);
            }
        }
        if (current.length() > 0) tokens.add(current.toString());
        return tokens;
    }
}

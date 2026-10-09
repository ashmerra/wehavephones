package com.wehavephones.mobile.addons.provider;

import com.wehavephones.mobile.WowFlavor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * BigWigs-packager style GitHub release asset selection: only .zip packages,
 * scored by the game flavor in the file name and the optional -nolib
 * preference. Pure Java.
 */
public final class GitHubAssetSelector {
    private GitHubAssetSelector() {}

    public static final class Asset {
        public final String name;
        public final String browserUrl;
        public final String apiUrl;
        public final long createdAt;
        public final long size;

        public Asset(String name, String browserUrl, String apiUrl, long createdAt, long size) {
            this.name = name != null ? name : "";
            this.browserUrl = browserUrl;
            this.apiUrl = apiUrl;
            this.createdAt = createdAt;
            this.size = size;
        }
    }

    public static Asset select(List<Asset> assets, WowFlavor flavor, boolean preferNolib) {
        Asset best = null;
        int bestScore = -1;
        for (Asset asset : assets) {
            int score = score(asset.name, flavor, preferNolib);
            if (score < 0) continue;
            if (score > bestScore
                    || (score == bestScore && best != null && betterTieBreak(asset, best))) {
                best = asset;
                bestScore = score;
            }
        }
        return best;
    }

    private static boolean betterTieBreak(Asset candidate, Asset incumbent) {
        if (candidate.createdAt != incumbent.createdAt) return candidate.createdAt > incumbent.createdAt;
        return candidate.name.compareToIgnoreCase(incumbent.name) < 0;
    }

    /**
     * Returns a score for an asset name, or -1 when the asset is not an
     * installable package for the requested flavor.
     */
    public static int score(String name, WowFlavor flavor, boolean preferNolib) {
        if (name == null) return -1;
        String lower = name.toLowerCase(Locale.US);
        if (!lower.endsWith(".zip")) return -1;
        if (lower.contains("release.json") || lower.contains("source")) return -1;

        boolean hasNolib = lower.contains("-nolib") || lower.contains("_nolib");
        if (hasNolib && !preferNolib) return -1;

        int flavorScore = flavorScore(lower, flavor != null ? flavor.baseFlavor() : WowFlavor.UNKNOWN);
        if (flavorScore < 0) return -1;
        int score = flavorScore;
        if (preferNolib && hasNolib) score += 15;
        return score;
    }

    private static int flavorScore(String lower, WowFlavor flavor) {
        String[] hints;
        String[] ownHints;
        switch (flavor) {
            case RETAIL:
                ownHints = new String[]{"-mainline", "-retail", "-standard"};
                hints = new String[]{"-classic", "-vanilla", "-tbc", "-bcc", "-wrath", "-wotlkc",
                        "-cata", "-mists", "-titan", "-camelot", "-forever", "-mop"};
                break;
            case CLASSIC:
                ownHints = new String[]{"-mists", "-mop"};
                hints = new String[]{"-mainline", "-retail", "-standard", "-vanilla", "-classic",
                        "-tbc", "-bcc", "-wrath", "-wotlkc", "-cata", "-titan", "-camelot", "-forever"};
                break;
            case CLASSIC_ERA:
                ownHints = new String[]{"-vanilla", "-era"};
                hints = new String[]{"-mainline", "-retail", "-standard", "-tbc", "-bcc",
                        "-wrath", "-wotlkc", "-cata", "-mists", "-mop", "-titan", "-camelot", "-forever"};
                break;
            case ANNIVERSARY:
                ownHints = new String[]{"-tbc", "-bcc"};
                hints = new String[]{"-mainline", "-retail", "-standard", "-vanilla", "-classic", "-wrath",
                        "-wotlkc", "-cata", "-mists", "-mop", "-titan", "-camelot", "-forever"};
                break;
            case TITAN:
                ownHints = new String[]{"-titan", "-wrath", "-wotlkc"};
                hints = new String[]{"-mainline", "-retail", "-standard", "-vanilla", "-classic", "-tbc",
                        "-bcc", "-cata", "-mists", "-mop", "-camelot", "-forever"};
                break;
            case FOREVER:
                ownHints = new String[]{"-forever", "-camelot"};
                hints = new String[]{"-mainline", "-retail", "-standard", "-vanilla", "-classic", "-tbc",
                        "-bcc", "-wrath", "-wotlkc", "-cata", "-mists", "-mop", "-titan"};
                break;
            case UNKNOWN:
            default:
                return 50;
        }
        boolean explicitOther = false;
        for (String hint : hints) {
            if (lower.contains(hint)) {
                explicitOther = true;
                break;
            }
        }
        for (String hint : ownHints) {
            if (lower.contains(hint)) return 100;
        }
        if (explicitOther) return -1;
        return 50;
    }

    public static List<String> names(List<Asset> assets) {
        ArrayList<String> names = new ArrayList<>();
        for (Asset asset : assets) names.add(asset.name);
        return names;
    }
}

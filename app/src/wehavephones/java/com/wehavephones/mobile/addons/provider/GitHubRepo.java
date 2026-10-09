package com.wehavephones.mobile.addons.provider;

import java.util.Locale;

/** Parses "owner/repo" (or a github.com URL) external ids. Pure Java. */
public final class GitHubRepo {
    public final String owner;
    public final String repo;

    public GitHubRepo(String owner, String repo) {
        this.owner = owner != null ? owner : "";
        this.repo = repo != null ? repo : "";
    }

    public static GitHubRepo parse(String externalId) {
        if (externalId == null) return null;
        String value = externalId.trim();
        if (value.startsWith("github:")) value = value.substring("github:".length());
        int scheme = value.indexOf("://");
        if (scheme >= 0) {
            int slash = value.indexOf('/', scheme + 3);
            if (slash < 0) return null;
            value = value.substring(slash + 1);
        }
        if (value.startsWith("github.com/")) value = value.substring("github.com/".length());
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        int separator = value.indexOf('/');
        if (separator <= 0 || separator == value.length() - 1) return null;
        String owner = value.substring(0, separator);
        String repo = value.substring(separator + 1);
        int extra = repo.indexOf('/');
        if (extra >= 0) repo = repo.substring(0, extra);
        if (repo.toLowerCase(Locale.US).endsWith(".git")) repo = repo.substring(0, repo.length() - 4);
        if (!isValidSegment(owner) || !isValidSegment(repo)) return null;
        return new GitHubRepo(owner, repo);
    }

    public String key() {
        return owner + "/" + repo;
    }

    public String webUrl() {
        return "https://github.com/" + key();
    }

    public String describe() {
        return key();
    }

    private static boolean isValidSegment(String value) {
        if (value.isEmpty() || value.length() > 100) return false;
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (!Character.isLetterOrDigit(character) && character != '-' && character != '_' && character != '.') {
                return false;
            }
        }
        return true;
    }
}

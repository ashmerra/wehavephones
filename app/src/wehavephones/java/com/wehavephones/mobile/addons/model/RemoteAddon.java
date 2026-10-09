package com.wehavephones.mobile.addons.model;

public final class RemoteAddon {
    public final ProviderType provider;
    public final String externalId;
    public final String name;
    public final String author;
    public final String summary;
    public final String website;
    public final long downloads;
    public final RemoteFile latestFile;

    public RemoteAddon(ProviderType provider, String externalId, String name, String author,
                       String summary, String website, long downloads, RemoteFile latestFile) {
        this.provider = provider;
        this.externalId = externalId != null ? externalId : "";
        this.name = name != null ? name : "";
        this.author = author != null ? author : "";
        this.summary = summary != null ? summary : "";
        this.website = website != null ? website : "";
        this.downloads = downloads;
        this.latestFile = latestFile;
    }

    public String latestVersion() {
        return latestFile != null ? latestFile.version : "";
    }

    /** Provider-qualified identity key, e.g. "curseforge:65387". */
    public String key() {
        return provider.id + ":" + externalId;
    }
}

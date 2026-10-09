package com.wehavephones.mobile.addons.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class RemoteFile {
    public final ProviderType provider;
    public final String externalId;
    public final String releaseId;
    public final String version;
    public final String channel;
    public final String name;
    public final String downloadUrl;
    public final long releaseDate;
    public final long size;
    public final String sha1;
    public final List<String> folders;
    public final List<String> gameVersions;
    public final String changelog;
    public final List<String> dependencyIds;

    private RemoteFile(Builder builder) {
        this.provider = builder.provider;
        this.externalId = builder.externalId != null ? builder.externalId : "";
        this.releaseId = builder.releaseId != null ? builder.releaseId : "";
        this.version = builder.version != null ? builder.version : "";
        this.channel = builder.channel != null ? builder.channel : Channel.STABLE.id;
        this.name = builder.name != null ? builder.name : "";
        this.downloadUrl = builder.downloadUrl;
        this.releaseDate = builder.releaseDate;
        this.size = builder.size;
        this.sha1 = builder.sha1;
        this.folders = Collections.unmodifiableList(new ArrayList<>(builder.folders));
        this.gameVersions = Collections.unmodifiableList(new ArrayList<>(builder.gameVersions));
        this.changelog = builder.changelog != null ? builder.changelog : "";
        this.dependencyIds = Collections.unmodifiableList(new ArrayList<>(builder.dependencyIds));
    }

    public static Builder builder(ProviderType provider, String externalId) {
        return new Builder(provider, externalId);
    }

    public static final class Builder {
        private final ProviderType provider;
        private final String externalId;
        private String releaseId = "";
        private String version = "";
        private String channel = Channel.STABLE.id;
        private String name = "";
        private String downloadUrl;
        private long releaseDate;
        private long size = -1;
        private String sha1;
        private final ArrayList<String> folders = new ArrayList<>();
        private final ArrayList<String> gameVersions = new ArrayList<>();
        private String changelog = "";
        private final ArrayList<String> dependencyIds = new ArrayList<>();

        Builder(ProviderType provider, String externalId) {
            this.provider = provider;
            this.externalId = externalId;
        }

        public Builder releaseId(String value) { this.releaseId = value; return this; }
        public Builder version(String value) { this.version = value; return this; }
        public Builder channel(String value) { this.channel = value; return this; }
        public Builder name(String value) { this.name = value; return this; }
        public Builder downloadUrl(String value) { this.downloadUrl = value; return this; }
        public Builder releaseDate(long value) { this.releaseDate = value; return this; }
        public Builder size(long value) { this.size = value; return this; }
        public Builder sha1(String value) { this.sha1 = value; return this; }
        public Builder folder(String value) { if (value != null && !value.isEmpty()) folders.add(value); return this; }
        public Builder gameVersion(String value) { if (value != null && !value.isEmpty()) gameVersions.add(value); return this; }
        public Builder changelog(String value) { this.changelog = value; return this; }
        public Builder dependencyId(String value) { if (value != null && !value.isEmpty()) dependencyIds.add(value); return this; }

        public RemoteFile build() {
            return new RemoteFile(this);
        }
    }
}

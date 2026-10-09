package com.wehavephones.mobile;

import java.io.File;

/** One installed WoW game (a {@code World of Warcraft\_<product>_} directory). */
public final class WowProduct {
    public final String directoryName;
    public final File directory;
    private WowFlavor flavor = WowFlavor.UNKNOWN;
    private String productCode = "";
    private String version = "";
    private String branch = "";
    private String buildKey = "";
    private String executable = "";

    WowProduct(String directoryName, File directory) {
        this.directoryName = directoryName;
        this.directory = directory;
    }

    public WowFlavor getFlavor() {
        return flavor;
    }

    void setFlavor(WowFlavor flavor) {
        this.flavor = flavor != null ? flavor : WowFlavor.UNKNOWN;
    }

    /** Short catalog name, e.g. "MoP Classic" or "Forever Beta". */
    public String getDisplayName() {
        return flavor.isKnown() ? flavor.displayName : WowFlavor.prettyDirectory(directoryName);
    }

    /** Big title, e.g. "World of Warcraft: Forever". */
    public String getTitle() {
        return WowFlavor.gameTitle(flavor, directoryName);
    }

    /** Expansion and channel, e.g. "Mists of Pandaria · PTR"; may be empty. */
    public String getEdition() {
        return WowFlavor.edition(flavor, version);
    }

    /** Battle.net product code from .flavor.info (e.g. wow_classic_beta); may be empty. */
    public String getProductCode() {
        return productCode;
    }

    void setProductCode(String productCode) {
        this.productCode = productCode != null ? productCode : "";
    }

    public String getVersion() {
        return version;
    }

    void setVersion(String version) {
        this.version = version != null ? version : "";
    }

    public String getBranch() {
        return branch;
    }

    void setBranch(String branch) {
        this.branch = branch != null ? branch : "";
    }

    public String getBuildKey() {
        return buildKey;
    }

    void setBuildKey(String buildKey) {
        this.buildKey = buildKey != null ? buildKey : "";
    }

    public String getExecutable() {
        return executable;
    }

    void setExecutable(String executable) {
        this.executable = executable != null ? executable : "";
    }

    public boolean hasBuildInfo() {
        return !version.isEmpty() || !branch.isEmpty() || !buildKey.isEmpty();
    }

    public String describe() {
        StringBuilder builder = new StringBuilder(getDisplayName());
        if (!version.isEmpty()) builder.append(" ").append(version);
        return builder.toString();
    }
}

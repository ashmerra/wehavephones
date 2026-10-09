package com.wehavephones.mobile;

import android.content.Context;

import com.wehavephones.container.Container;
import com.wehavephones.core.FileUtils;
import com.wehavephones.xenvironment.ImageFs;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public final class WeHavePhonesStatus {
    public final boolean imageFsValid;
    public final int imageFsVersion;
    public final Container container;
    public final boolean containerReady;
    public final boolean bnetInstalled;
    public final String wineVersion;
    public final ArrayList<WowProduct> products;
    /** Installed games plus games Battle.net knows about but has not installed. */
    public final List<BattleNetGames.Game> games;
    public final WeHavePhonesRuntimeProfiles profiles;
    public final WeHavePhonesRuntimeProfiles.Profile profile;
    public final boolean profileApplied;

    private WeHavePhonesStatus(boolean imageFsValid, int imageFsVersion, Container container,
            boolean bnetInstalled, List<BattleNetGames.Game> games,
            WeHavePhonesRuntimeProfiles profiles, WeHavePhonesRuntimeProfiles.Profile profile,
            boolean profileApplied) {
        this.imageFsValid = imageFsValid;
        this.imageFsVersion = imageFsVersion;
        this.container = container;
        this.containerReady = container != null;
        this.bnetInstalled = bnetInstalled;
        this.wineVersion = container != null ? container.getWineVersion() : "";
        this.games = games;
        this.products = new ArrayList<>();
        for (BattleNetGames.Game game : games) {
            if (game.product != null) products.add(game.product);
        }
        this.profiles = profiles;
        this.profile = profile;
        this.profileApplied = profileApplied;
    }

    public static WeHavePhonesStatus load(Context context) {
        ImageFs imageFs = ImageFs.find(context);
        boolean imageFsValid = imageFs.isValid();
        int imageFsVersion = 0;
        if (imageFsValid) {
            try {
                imageFsVersion = imageFs.getVersion();
            }
            catch (Exception e) {
                imageFsValid = false;
            }
        }

        Container container = WeHavePhonesContainers.resolve(context);
        boolean bnetInstalled = container != null && WeHavePhonesPaths.isBnetInstalled(container);
        List<BattleNetGames.Game> games = container != null
                ? WowInstallationScanner.battleNetGames(container) : new ArrayList<BattleNetGames.Game>();

        WeHavePhonesRuntimeProfiles profiles = WeHavePhonesRuntimeProfiles.loadOrNull(context);
        WeHavePhonesRuntimeProfiles.Profile profile = profiles != null ? profiles.getSelectedProfile(context) : null;
        boolean applied = container != null && profile != null && matches(container, profile);

        return new WeHavePhonesStatus(imageFsValid, imageFsVersion, container, bnetInstalled, games, profiles, profile, applied);
    }

    public static boolean matches(Container container, WeHavePhonesRuntimeProfiles.Profile profile) {
        if (container == null || profile == null) return false;
        if (!profile.wineVersion.isEmpty() && !profile.wineVersion.equals(container.getWineVersion())) return false;
        String envVars = container.getEnvVars();
        for (java.util.Map.Entry<String, String> entry : profile.env.entrySet()) {
            if (!envVars.contains(entry.getKey() + "=" + entry.getValue())) return false;
        }
        if (!profile.dxwrapper.isEmpty() && !profile.dxwrapper.equals(container.getDXWrapper())) return false;
        return true;
    }

    public static boolean bnetBatMatches(Context context, Container container, WeHavePhonesRuntimeProfiles profiles, WeHavePhonesRuntimeProfiles.Profile profile) {
        if (container == null || profiles == null || profile == null) return false;
        File file = WeHavePhonesPaths.bnetBat(container);
        if (!file.isFile()) return false;
        String expected = profiles.buildBnetBat(profile);
        String actual = FileUtils.readString(file);
        return actual != null && actual.equals(expected);
    }
}

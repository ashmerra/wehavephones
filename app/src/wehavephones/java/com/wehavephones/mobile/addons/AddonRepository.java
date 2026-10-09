package com.wehavephones.mobile.addons;

import com.wehavephones.mobile.addons.model.AddonRecord;
import com.wehavephones.mobile.addons.provider.AddonProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Process-wide registry kept from Phase 2. The Phase 3 UI works through
 * {@link AddonManager}; this bridge allows any other screen to observe the
 * installed addon list without holding an Activity.
 */
public final class AddonRepository {
    private static AddonRepository instance;

    private final LinkedHashMap<String, AddonProvider> providers = new LinkedHashMap<>();
    private final ArrayList<InstalledAddon> installedAddons = new ArrayList<>();

    private AddonRepository() {}

    public static synchronized AddonRepository get() {
        if (instance == null) instance = new AddonRepository();
        return instance;
    }

    public synchronized void registerProvider(AddonProvider provider) {
        if (provider != null) providers.put(provider.type().id, provider);
    }

    public synchronized List<AddonProvider> getProviders() {
        return Collections.unmodifiableList(new ArrayList<>(providers.values()));
    }

    public synchronized AddonProvider getProvider(String id) {
        return providers.get(id);
    }

    public synchronized void setInstalledAddons(List<InstalledAddon> addons) {
        installedAddons.clear();
        if (addons != null) installedAddons.addAll(addons);
    }

    public synchronized void setInstalledRecords(List<AddonRecord> records) {
        ArrayList<InstalledAddon> converted = new ArrayList<>();
        if (records != null) {
            for (AddonRecord record : records) converted.add(InstalledAddon.fromRecord(record));
        }
        setInstalledAddons(converted);
    }

    public synchronized List<InstalledAddon> getInstalledAddons() {
        return Collections.unmodifiableList(new ArrayList<>(installedAddons));
    }

    public synchronized boolean isProviderScanImplemented() {
        return !providers.isEmpty();
    }
}

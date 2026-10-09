package com.wehavephones.mobile.addons.provider;

import android.content.Context;

import com.wehavephones.mobile.addons.model.ProviderType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Process-wide provider registry built from the encrypted key store. Rebuild
 * after the user changes a key.
 */
public final class ProviderRegistry {
    private final Context context;
    private final LinkedHashMap<ProviderType, AddonProvider> providers = new LinkedHashMap<>();

    public ProviderRegistry(Context context) {
        this.context = context.getApplicationContext();
        rebuild();
    }

    public synchronized void rebuild() {
        providers.clear();
        register(new CurseForgeProvider(AddonSecrets.get(context, ProviderType.CURSEFORGE)));
        register(new GitHubProvider(AddonSecrets.get(context, ProviderType.GITHUB)));
        register(new TukuiProvider());
        register(new WagoProvider(AddonSecrets.get(context, ProviderType.WAGO)));
    }

    private void register(AddonProvider provider) {
        if (provider.type().offered) providers.put(provider.type(), provider);
    }

    public synchronized AddonProvider get(ProviderType type) {
        return providers.get(type);
    }

    public synchronized List<AddonProvider> all() {
        return Collections.unmodifiableList(new ArrayList<>(providers.values()));
    }

    public synchronized List<AddonProvider> configured() {
        ArrayList<AddonProvider> result = new ArrayList<>();
        for (AddonProvider provider : providers.values()) {
            if (provider.isConfigured()) result.add(provider);
        }
        return result;
    }

    public synchronized boolean hasKey(ProviderType type) {
        return AddonSecrets.has(context, type);
    }

    public synchronized void setKey(ProviderType type, String value) throws Exception {
        AddonSecrets.set(context, type, value);
        rebuild();
    }
}

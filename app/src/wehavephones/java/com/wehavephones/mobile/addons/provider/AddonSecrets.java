package com.wehavephones.mobile.addons.provider;

import android.content.Context;

import com.wehavephones.mobile.SecretStore;
import com.wehavephones.mobile.addons.model.ProviderType;

/**
 * Provider API keys/tokens. Delegates to the Phase 2 Android Keystore-backed
 * SecretStore so no provider credential is ever written in plaintext.
 */
public final class AddonSecrets {
    public static final String KEY_CURSEFORGE = "addon_curseforge_api_key";
    public static final String KEY_WAGO = "addon_wago_token";
    public static final String KEY_GITHUB = "addon_github_pat";

    private AddonSecrets() {}

    public static String secretName(ProviderType type) {
        switch (type) {
            case CURSEFORGE: return KEY_CURSEFORGE;
            case WAGO: return KEY_WAGO;
            case GITHUB: return KEY_GITHUB;
            default: return null;
        }
    }

    public static String get(Context context, ProviderType type) {
        String name = secretName(type);
        if (name == null) return "";
        String value = SecretStore.getSecret(context, name);
        return value != null ? value : "";
    }

    public static void set(Context context, ProviderType type, String value) throws Exception {
        String name = secretName(type);
        if (name == null) return;
        if (value == null || value.trim().isEmpty()) {
            SecretStore.clearSecret(context, name);
        }
        else {
            SecretStore.putSecret(context, name, value.trim());
        }
    }

    public static boolean has(Context context, ProviderType type) {
        return !get(context, type).isEmpty();
    }
}

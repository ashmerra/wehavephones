package com.wehavephones.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import com.wehavephones.core.FileUtils;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Keystore-encrypted named secrets (the add-on manager's provider API keys).
 * WeHavePhones keeps no Battle.net or WoW account credentials: Battle.net
 * signs in with the login it saved itself. {@link #purgeAccountCredentials}
 * removes what older builds stored.
 */
public final class SecretStore {
    private static final String PREFERENCES = "wehavephones_secrets";
    /** Account fields older builds stored; deleted on start. */
    private static final String LEGACY_USERNAME = "credential_username";
    private static final String LEGACY_PASSWORD = "credential_password";
    private static final String LEGACY_IMPORT_FILE = "credential-import";
    private static final String KEYSTORE = "AndroidKeyStore";
    // Kept so secrets saved by older builds still decrypt.
    private static final String KEY_ALIAS = "wehavephones_credentials_v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;

    private SecretStore() {}

    /** Deletes the Battle.net account and the plaintext import file older builds kept. */
    public static void purgeAccountCredentials(Context context) {
        SharedPreferences preferences = preferences(context);
        if (preferences.contains(LEGACY_USERNAME) || preferences.contains(LEGACY_PASSWORD)) {
            preferences.edit().remove(LEGACY_USERNAME).remove(LEGACY_PASSWORD).apply();
        }
        File importFile = new File(WeHavePhonesPaths.wehavephonesDir(context), LEGACY_IMPORT_FILE);
        if (importFile.isFile()) {
            byte[] bytes = FileUtils.read(importFile);
            if (bytes != null) {
                Arrays.fill(bytes, (byte)0);
                FileUtils.write(importFile, bytes);
            }
            importFile.delete();
        }
    }

    /**
     * Named secret (the add-on manager's provider API keys); the value is
     * never written in plaintext.
     */
    public static void putSecret(Context context, String name, String value) throws Exception {
        if (name == null || name.isEmpty()) return;
        preferences(context).edit()
                .putString("secret_" + name, encrypt(value != null ? value : ""))
                .apply();
    }

    public static String getSecret(Context context, String name) {
        if (name == null || name.isEmpty()) return null;
        String stored = preferences(context).getString("secret_" + name, null);
        if (stored == null) return null;
        try {
            return decrypt(stored);
        }
        catch (Exception e) {
            return null;
        }
    }

    public static void clearSecret(Context context, String name) {
        if (name == null || name.isEmpty()) return;
        preferences(context).edit().remove("secret_" + name).apply();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    private static SecretKey secretKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return ((KeyStore.SecretKeyEntry)keyStore.getEntry(KEY_ALIAS, null)).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build());
        return generator.generateKey();
    }

    private static String encrypt(String value) throws Exception {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey());
        byte[] ciphertext = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(ciphertext, Base64.NO_WRAP);
    }

    private static String decrypt(String stored) throws Exception {
        if (stored == null || stored.isEmpty()) return "";
        int separator = stored.indexOf(':');
        if (separator <= 0) return null;
        byte[] iv = Base64.decode(stored.substring(0, separator), Base64.NO_WRAP);
        byte[] ciphertext = Base64.decode(stored.substring(separator + 1), Base64.NO_WRAP);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
        return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    }
}

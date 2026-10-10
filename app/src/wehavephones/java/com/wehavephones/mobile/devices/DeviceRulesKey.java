package com.wehavephones.mobile.devices;

/**
 * Public half of the key that signs device-rules.json (ECDSA P-256, base64 X.509
 * SubjectPublicKeyInfo). Create the key pair with scripts/generate-device-rules-key.sh.
 */
final class DeviceRulesKey {
    static final String PUBLIC_KEY_BASE64 =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE+jMLOtUOzHnffR3tdzTvn5F7HRhRNHlxnruE+oySxUh6eHKrn0jIL3Ig"
        + "c38kbYMcekQ/qEda82ZTaHiZZCp6Qg==";

    private DeviceRulesKey() {}
}

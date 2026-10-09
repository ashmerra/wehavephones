package com.wehavephones.mobile.addons.provider;

import com.wehavephones.mobile.addons.net.Http;

import org.json.JSONObject;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

abstract class BaseProvider implements AddonProvider {
    protected final String apiKey;

    protected BaseProvider(String apiKey) {
        this.apiKey = apiKey != null ? apiKey.trim() : "";
    }

    @Override
    public boolean requiresKey() {
        return type().requiresKey;
    }

    @Override
    public boolean isConfigured() {
        return !requiresKey() || !apiKey.isEmpty();
    }

    protected void requireKey() throws ProviderException {
        if (!isConfigured()) {
            throw new ProviderException(ProviderException.Kind.AUTH,
                    type().displayName + " requires an API key");
        }
    }

    protected Http.Response request(String url, Map<String, String> headers, Set<String> secretHeaders,
                                    Set<String> secretHosts, int maxBytes, int attempts)
            throws ProviderException {
        Map<String, String> all = new LinkedHashMap<>(headers);
        all.put("Accept", "application/json");
        try {
            Http.Response response = Http.getWithRetry(url, all, secretHeaders, secretHosts, maxBytes, attempts);
            if (response.isSuccess()) return response;
            throw errorFor(response.status, url);
        }
        catch (IOException e) {
            if (Thread.currentThread().isInterrupted()) {
                throw new ProviderException(ProviderException.Kind.CANCELLED, "Cancelled", e);
            }
            throw new ProviderException(ProviderException.Kind.NETWORK, e.getMessage(), e);
        }
    }

    protected JSONObject requestJson(String url, Map<String, String> headers, Set<String> secretHeaders,
                                     Set<String> secretHosts, int attempts) throws ProviderException {
        Http.Response response = request(url, headers, secretHeaders, secretHosts, Http.MAX_JSON_BYTES, attempts);
        try {
            return new JSONObject(response.text());
        }
        catch (Exception e) {
            throw new ProviderException(ProviderException.Kind.PARSE, "Malformed JSON from " + url, e);
        }
    }

    protected static ProviderException errorFor(int status, String url) {
        if (status == 401 || status == 403) {
            return new ProviderException(ProviderException.Kind.AUTH, "Authentication failed (" + status + ")");
        }
        if (status == 404) {
            return new ProviderException(ProviderException.Kind.NOT_FOUND, "Not found: " + url);
        }
        if (status == 410) {
            return new ProviderException(ProviderException.Kind.REMOVED, "Removed upstream: " + url);
        }
        if (status == 429) {
            return new ProviderException(ProviderException.Kind.RATE_LIMIT, "Rate limited (429)");
        }
        return new ProviderException(ProviderException.Kind.NETWORK, "HTTP " + status + " from " + url);
    }

    protected static Map<String, String> headers(String... keyValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) map.put(keyValues[i], keyValues[i + 1]);
        return map;
    }
}

package com.wehavephones.mobile.addons.provider;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class ResolvedDownload {
    public final String url;
    public final Map<String, String> headers;
    public final Set<String> secretHeaders;
    public final Set<String> secretHosts;
    public final long expectedSize;
    public final String sha1;

    public ResolvedDownload(String url, Map<String, String> headers, Set<String> secretHeaders,
                            Set<String> secretHosts, long expectedSize, String sha1) {
        this.url = url;
        this.headers = Collections.unmodifiableMap(
                headers != null ? new LinkedHashMap<>(headers) : new LinkedHashMap<String, String>());
        this.secretHeaders = secretHeaders;
        this.secretHosts = secretHosts;
        this.expectedSize = expectedSize;
        this.sha1 = sha1;
    }
}

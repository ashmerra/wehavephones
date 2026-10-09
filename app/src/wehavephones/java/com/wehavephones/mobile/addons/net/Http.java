package com.wehavephones.mobile.addons.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * HttpURLConnection wrapper: manual redirects, gzip, timeouts, size caps and
 * secret-header host pinning. No third-party HTTP library.
 */
public final class Http {
    public static final int CONNECT_TIMEOUT_MS = 15000;
    public static final int READ_TIMEOUT_MS = 30000;
    public static final int MAX_REDIRECTS = 5;
    public static final int MAX_JSON_BYTES = 4 * 1024 * 1024;
    public static final int MAX_TEXT_BYTES = 1024 * 1024;
    public static final String USER_AGENT = "WeHavePhonesMobile/1.0 (Android; WoW addon manager)";

    private Http() {}

    public static final class Response {
        public final int status;
        public final byte[] bytes;
        public final String finalUrl;
        public final Map<String, String> headers;

        Response(int status, byte[] bytes, String finalUrl, Map<String, String> headers) {
            this.status = status;
            this.bytes = bytes;
            this.finalUrl = finalUrl;
            this.headers = headers;
        }

        public String text() {
            return bytes != null ? new String(bytes, StandardCharsets.UTF_8) : "";
        }

        public boolean isSuccess() {
            return status >= 200 && status < 300;
        }
    }

    public static Response get(String url, Map<String, String> headers, Set<String> secretHeaders,
                               Set<String> secretHosts, int maxBytes) throws IOException {
        return request(url, "GET", headers, null, secretHeaders, secretHosts, maxBytes);
    }

    public static Response getWithRetry(String url, Map<String, String> headers, Set<String> secretHeaders,
                                        Set<String> secretHosts, int maxBytes, int maxAttempts) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= Math.max(1, maxAttempts); attempt++) {
            try {
                Response response = get(url, headers, secretHeaders, secretHosts, maxBytes);
                if (response.status != 429 && response.status < 500) return response;
                if (attempt < maxAttempts) {
                    sleepBackoff(attempt, retryAfterMillis(response.headers));
                }
                else {
                    return response;
                }
            }
            catch (IOException e) {
                last = e;
                if (attempt < maxAttempts) sleepBackoff(attempt, -1);
            }
        }
        if (last != null) throw last;
        throw new IOException("request failed");
    }

    public static Response postJson(String url, Map<String, String> headers, String jsonBody,
                                    Set<String> secretHeaders, Set<String> secretHosts, int maxBytes)
            throws IOException {
        byte[] body = jsonBody != null ? jsonBody.getBytes(StandardCharsets.UTF_8) : new byte[0];
        Map<String, String> all = new LinkedHashMap<>();
        if (headers != null) all.putAll(headers);
        all.put("Content-Type", "application/json; charset=utf-8");
        return request(url, "POST", all, body, secretHeaders, secretHosts, maxBytes);
    }

    public static boolean hostMatches(String host, Set<String> allowedHosts) {
        if (host == null || allowedHosts == null) return false;
        String lower = host.toLowerCase(Locale.US);
        for (String allowed : allowedHosts) {
            if (allowed == null) continue;
            String candidate = allowed.toLowerCase(Locale.US);
            if (candidate.startsWith("*.")) {
                String suffix = candidate.substring(1);
                if (lower.endsWith(suffix)) return true;
            }
            else if (lower.equals(candidate)) {
                return true;
            }
        }
        return false;
    }

    public static String stripQueryParameter(String url, String parameter) {
        if (url == null || parameter == null) return url;
        int query = url.indexOf('?');
        if (query < 0) return url;
        String base = url.substring(0, query);
        String queryText = url.substring(query + 1);
        StringBuilder rebuilt = new StringBuilder();
        for (String pair : queryText.split("&")) {
            String name = pair;
            int equals = pair.indexOf('=');
            if (equals >= 0) name = pair.substring(0, equals);
            if (name.equalsIgnoreCase(parameter)) continue;
            if (rebuilt.length() > 0) rebuilt.append('&');
            rebuilt.append(pair);
        }
        return rebuilt.length() == 0 ? base : base + "?" + rebuilt;
    }

    static long retryAfterMillis(Map<String, String> headers) {
        if (headers == null) return -1;
        String value = headers.get("retry-after");
        if (value == null || value.trim().isEmpty()) return -1;
        String trimmed = value.trim();
        try {
            long seconds = Long.parseLong(trimmed);
            return Math.max(0, seconds) * 1000L;
        }
        catch (NumberFormatException ignored) {}
        SimpleDateFormat format = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
        java.util.Date date = format.parse(trimmed, new ParsePosition(0));
        if (date != null) return Math.max(0, date.getTime() - System.currentTimeMillis());
        return -1;
    }

    private static void sleepBackoff(int attempt, long retryAfterMs) {
        long delay = retryAfterMs >= 0 ? retryAfterMs : (long)(500 * Math.pow(2, attempt - 1));
        delay = Math.min(delay, 30000) + (long)(Math.random() * 250);
        try {
            Thread.sleep(delay);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Response request(String url, String method, Map<String, String> headers, byte[] body,
                                    Set<String> secretHeaders, Set<String> secretHosts, int maxBytes)
            throws IOException {
        String current = url;
        Set<String> secrets = secretHeaders != null ? secretHeaders : new HashSet<String>();
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            URL parsed = new URL(current);
            if (!"https".equalsIgnoreCase(parsed.getProtocol())) {
                throw new IOException("Refusing non-HTTPS URL: " + current);
            }
            RateLimiter.acquire(parsed.getHost());
            HttpURLConnection connection = (HttpURLConnection)parsed.openConnection();
            try {
                connection.setRequestMethod(method);
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(READ_TIMEOUT_MS);
                connection.setRequestProperty("User-Agent", USER_AGENT);
                connection.setRequestProperty("Accept-Encoding", "gzip");
                if (headers != null) {
                    for (Map.Entry<String, String> header : headers.entrySet()) {
                        connection.setRequestProperty(header.getKey(), header.getValue());
                    }
                }
                if (body != null && body.length > 0) {
                    connection.setDoOutput(true);
                    connection.setFixedLengthStreamingMode(body.length);
                    try (OutputStream output = connection.getOutputStream()) {
                        output.write(body);
                    }
                }
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || location.isEmpty()) {
                        throw new IOException("Redirect without Location: " + current);
                    }
                    URL next = new URL(parsed, location);
                    Map<String, String> nextHeaders = headers != null
                            ? new LinkedHashMap<>(headers) : new LinkedHashMap<String, String>();
                    if (!Http.hostMatches(next.getHost(), secretHosts)) {
                        String redacted = next.toString();
                        if (redacted.indexOf("token=") >= 0) next = new URL(stripQueryParameter(redacted, "token"));
                        nextHeaders = withoutSecrets(nextHeaders, secrets);
                    }
                    headers = nextHeaders;
                    current = next.toString();
                    method = "GET";
                    body = null;
                    continue;
                }
                Map<String, String> responseHeaders = new LinkedHashMap<>();
                for (Map.Entry<String, List<String>> entry : connection.getHeaderFields().entrySet()) {
                    if (entry.getKey() == null || entry.getValue() == null || entry.getValue().isEmpty()) continue;
                    responseHeaders.put(entry.getKey().toLowerCase(Locale.US), entry.getValue().get(0));
                }
                byte[] bytes = readBody(connection, responseHeaders, maxBytes);
                return new Response(status, bytes, current, responseHeaders);
            }
            finally {
                connection.disconnect();
            }
        }
        throw new IOException("Too many redirects for " + url);
    }

    private static Map<String, String> withoutSecrets(Map<String, String> headers, Set<String> secrets) {
        Map<String, String> filtered = new LinkedHashMap<>();
        for (Map.Entry<String, String> header : headers.entrySet()) {
            boolean secret = false;
            for (String name : secrets) {
                if (name.equalsIgnoreCase(header.getKey())) secret = true;
            }
            if (!secret) filtered.put(header.getKey(), header.getValue());
        }
        return filtered;
    }

    private static byte[] readBody(HttpURLConnection connection, Map<String, String> headers, int maxBytes)
            throws IOException {
        InputStream input = connection.getResponseCode() >= 400
                ? connection.getErrorStream() : connection.getInputStream();
        if (input == null) return new byte[0];
        String encoding = headers.get("content-encoding");
        if (encoding != null && encoding.toLowerCase(Locale.US).contains("gzip")) {
            input = new GZIPInputStream(input);
        }
        try (InputStream stream = input) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                if (maxBytes > 0 && output.size() > maxBytes) {
                    throw new IOException("Response larger than the configured cap");
                }
            }
            return output.toByteArray();
        }
    }
}

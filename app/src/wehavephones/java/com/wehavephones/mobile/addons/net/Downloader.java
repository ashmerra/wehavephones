package com.wehavephones.mobile.addons.net;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Streaming file download with a hard size cap, SHA-1/SHA-256 hashing and
 * progress reporting. API keys are pinned to allow-listed hosts.
 */
public final class Downloader {
    public static final long MAX_DOWNLOAD_BYTES = 512L * 1024L * 1024L;

    private Downloader() {}

    public interface Listener {
        void onProgress(long read, long total);
        boolean isCancelled();
    }

    public static final class Result {
        public final long bytes;
        public final String sha1;
        public final String sha256;
        public final String finalUrl;

        Result(long bytes, String sha1, String sha256, String finalUrl) {
            this.bytes = bytes;
            this.sha1 = sha1;
            this.sha256 = sha256;
            this.finalUrl = finalUrl;
        }
    }

    public static Result download(String url, Map<String, String> headers, Set<String> secretHeaders,
                                  Set<String> secretHosts, File target, long maxBytes, Listener listener)
            throws IOException {
        long cap = maxBytes > 0 ? maxBytes : MAX_DOWNLOAD_BYTES;
        String current = url;
        Map<String, String> effectiveHeaders = headers != null
                ? new LinkedHashMap<>(headers) : new LinkedHashMap<String, String>();

        for (int redirect = 0; redirect <= Http.MAX_REDIRECTS; redirect++) {
            URL parsed = new URL(current);
            if (!"https".equalsIgnoreCase(parsed.getProtocol())) {
                throw new IOException("Refusing non-HTTPS download URL");
            }
            RateLimiter.acquire(parsed.getHost());
            HttpURLConnection connection = (HttpURLConnection)parsed.openConnection();
            try {
                connection.setRequestMethod("GET");
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(Http.CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(Http.READ_TIMEOUT_MS);
                connection.setRequestProperty("User-Agent", Http.USER_AGENT);
                connection.setRequestProperty("Accept-Encoding", "identity");
                for (Map.Entry<String, String> header : effectiveHeaders.entrySet()) {
                    connection.setRequestProperty(header.getKey(), header.getValue());
                }
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("Redirect without Location");
                    URL next = new URL(parsed, location);
                    if (!Http.hostMatches(next.getHost(), secretHosts)) {
                        String redacted = next.toString();
                        if (redacted.indexOf("token=") >= 0) {
                            next = new URL(Http.stripQueryParameter(redacted, "token"));
                        }
                        effectiveHeaders = withoutSecrets(effectiveHeaders, secretHeaders);
                    }
                    current = next.toString();
                    continue;
                }
                if (status != 200 && status != 206) {
                    throw new IOException("Download failed with HTTP " + status);
                }
                long total = connection.getContentLengthLong();
                if (total > cap) throw new IOException("Download larger than the size limit");
                copy(connection.getInputStream(), connection, target, cap, total, listener);
                String sha1 = hash(target, "SHA-1");
                String sha256 = hash(target, "SHA-256");
                return new Result(target.length(), sha1, sha256, current);
            }
            finally {
                connection.disconnect();
            }
        }
        throw new IOException("Too many redirects while downloading");
    }

    private static void copy(InputStream rawInput, HttpURLConnection connection, File target,
                             long cap, long total, Listener listener) throws IOException {
        InputStream input = rawInput;
        String encoding = connection.getContentEncoding();
        if (encoding != null && encoding.toLowerCase(Locale.US).contains("gzip")) {
            input = new GZIPInputStream(input);
        }
        long read = 0;
        byte[] buffer = new byte[64 * 1024];
        try (InputStream stream = input; OutputStream output = new FileOutputStream(target)) {
            int count;
            while ((count = stream.read(buffer)) != -1) {
                read += count;
                if (read > cap) throw new IOException("Download larger than the size limit");
                output.write(buffer, 0, count);
                if (listener != null) {
                    listener.onProgress(read, total);
                    if (listener.isCancelled()) throw new IOException("Download cancelled");
                }
            }
        }
        catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            throw e;
        }
    }

    private static Map<String, String> withoutSecrets(Map<String, String> headers, Set<String> secrets) {
        Map<String, String> filtered = new LinkedHashMap<>();
        for (Map.Entry<String, String> header : headers.entrySet()) {
            boolean secret = false;
            if (secrets != null) {
                for (String name : secrets) {
                    if (name.equalsIgnoreCase(header.getKey())) secret = true;
                }
            }
            if (!secret) filtered.put(header.getKey(), header.getValue());
        }
        return filtered;
    }

    public static String hash(File file, String algorithm) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            try (InputStream input = new java.io.FileInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            }
            return toHex(digest.digest());
        }
        catch (NoSuchAlgorithmException e) {
            throw new IOException("Missing digest: " + algorithm, e);
        }
    }

    public static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(Character.forDigit((value >> 4) & 0xF, 16));
            builder.append(Character.forDigit(value & 0xF, 16));
        }
        return builder.toString();
    }
}

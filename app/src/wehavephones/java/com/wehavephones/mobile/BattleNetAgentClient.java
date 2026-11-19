package com.wehavephones.mobile;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Talks to Blizzard's Agent through {@code agentpipe.exe}, which runs in the
 * container and copies bytes to the Agent's port. The Agent refuses callers
 * outside Wine (see {@link BattleNetAgentApi}). Every request is its own
 * connection: the pipe line {@code WHP <secret>}, then one HTTP request.
 */
public final class BattleNetAgentClient {
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final int MAX_RESPONSE = 8 * 1024 * 1024;

    private final int pipePort;
    private final String secret;
    private final File agentDat;
    private String token = "";

    public BattleNetAgentClient(int pipePort, String secret, File agentDat) {
        this.pipePort = pipePort;
        this.secret = secret;
        this.agentDat = agentDat;
    }

    /** The Agent's current port from Agent.dat, or -1 while it has not written one. */
    public int agentPort() {
        return BattleNetAgentApi.parsePort(readSmall(agentDat));
    }

    /** {@code GET /agent}; keeps the token for later calls. Null while the Agent does not answer. */
    public JSONObject hello() {
        BattleNetAgentApi.Response response = send("GET", "/agent", null, false);
        if (response == null || !response.ok()) return null;
        JSONObject json = response.json();
        String fresh = BattleNetAgentApi.parseToken(json);
        if (fresh.isEmpty()) return null;
        token = fresh;
        return json;
    }

    public boolean hasToken() {
        return !token.isEmpty();
    }

    public BattleNetAgentApi.Response get(String path) {
        return call("GET", path, null);
    }

    public BattleNetAgentApi.Response post(String path, JSONObject body) {
        return call("POST", path, body == null ? "" : body.toString());
    }

    public BattleNetAgentApi.Response delete(String path) {
        return call("DELETE", path, null);
    }

    /** One call; a 401 or 403 fetches a new token and tries once more. */
    private BattleNetAgentApi.Response call(String method, String path, String body) {
        if (token.isEmpty() && hello() == null) return null;
        BattleNetAgentApi.Response response = send(method, path, body, true);
        if (response != null && (response.status == 401 || response.status == 403) && hello() != null) {
            response = send(method, path, body, true);
        }
        return response;
    }

    private BattleNetAgentApi.Response send(String method, String path, String body, boolean authorized) {
        int port = agentPort();
        if (port <= 0) return null;
        byte[] request = BattleNetAgentApi.httpRequest(method, path, port, authorized ? token : null, body);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", pipePort), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            OutputStream out = socket.getOutputStream();
            out.write(("WHP " + secret + "\n").getBytes(StandardCharsets.US_ASCII));
            out.write(request);
            out.flush();
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) {
                data.write(buffer, 0, n);
                if (data.size() > MAX_RESPONSE) break;
            }
            BattleNetAgentApi.Response response = BattleNetAgentApi.parseResponse(data.toByteArray());
            return response.status == 0 ? null : response;
        }
        catch (IOException e) {
            return null;
        }
    }

    private static String readSmall(File file) {
        if (file == null || !file.isFile() || file.length() > 64) return null;
        try (InputStream in = new java.io.FileInputStream(file)) {
            byte[] data = new byte[(int)file.length()];
            int read = 0;
            while (read < data.length) {
                int n = in.read(data, read, data.length - read);
                if (n < 0) break;
                read += n;
            }
            return new String(data, 0, read, StandardCharsets.US_ASCII);
        }
        catch (IOException e) {
            return null;
        }
    }
}

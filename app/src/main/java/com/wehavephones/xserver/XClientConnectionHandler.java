package com.wehavephones.xserver;

import android.util.Log;

import com.wehavephones.xconnector.Client;
import com.wehavephones.xconnector.ConnectionHandler;
import com.wehavephones.xconnector.XSocketOutput;

import java.io.IOException;

public class XClientConnectionHandler implements ConnectionHandler {
    private final XServer xServer;

    public XClientConnectionHandler(XServer xServer) {
        this.xServer = xServer;
    }

    @Override
    public void handleNewConnection(Client client) {
        client.createIOStreams();
        client.setTag(new XClient(xServer, client.getInputStream(), client.getOutputStream()));
        try {
            client.getOutputStream().enableAsyncWrites(client.clientSocket.fd);
        }
        catch (IOException e) {
            // Make the original readable as EOF so the connector frees this
            // client's resources instead of crashing the entire session.
            XSocketOutput.shutdownSocket(client.clientSocket.fd);
            Log.e("XClientConnectionHandler", "Failed to start X client output", e);
        }
    }

    @Override
    public void handleConnectionShutdown(Client client) {
        client.getOutputStream().close();
        ((XClient)client.getTag()).freeResources();
    }
}

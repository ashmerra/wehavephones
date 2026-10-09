package com.wehavephones.xenvironment.components;

import com.wehavephones.alsaserver.ALSAClient;
import com.wehavephones.alsaserver.ALSAClientConnectionHandler;
import com.wehavephones.alsaserver.ALSARequestHandler;
import com.wehavephones.xconnector.UnixSocketConfig;
import com.wehavephones.xconnector.XConnectorEpoll;
import com.wehavephones.xenvironment.EnvironmentComponent;

public class ALSAServerComponent extends EnvironmentComponent {
    private XConnectorEpoll connector;
    private final UnixSocketConfig socketConfig;

    public ALSAServerComponent(UnixSocketConfig socketConfig) {
        this.socketConfig = socketConfig;
    }

    @Override
    public void start() {
        if (connector != null) return;
        connector = new XConnectorEpoll(socketConfig, new ALSAClientConnectionHandler(), new ALSARequestHandler());
        connector.setMultithreadedClients(true);
        connector.start();
    }

    @Override
    public void stop() {
        if (connector != null) {
            connector.stop();
            connector = null;
        }
    }
}

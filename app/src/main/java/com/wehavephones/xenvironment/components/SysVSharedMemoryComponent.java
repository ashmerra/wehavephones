package com.wehavephones.xenvironment.components;

import com.wehavephones.sysvshm.SysVSHMConnectionHandler;
import com.wehavephones.sysvshm.SysVSHMRequestHandler;
import com.wehavephones.sysvshm.SysVSharedMemory;
import com.wehavephones.xconnector.UnixSocketConfig;
import com.wehavephones.xconnector.XConnectorEpoll;
import com.wehavephones.xenvironment.EnvironmentComponent;
import com.wehavephones.xserver.SHMSegmentManager;
import com.wehavephones.xserver.XServer;

public class SysVSharedMemoryComponent extends EnvironmentComponent {
    private XConnectorEpoll connector;
    public final UnixSocketConfig socketConfig;
    private SysVSharedMemory sysVSharedMemory;
    private final XServer xServer;

    public SysVSharedMemoryComponent(XServer xServer, UnixSocketConfig socketConfig) {
        this.xServer = xServer;
        this.socketConfig = socketConfig;
    }

    @Override
    public void start() {
        if (connector != null) return;
        sysVSharedMemory = new SysVSharedMemory();
        connector = new XConnectorEpoll(socketConfig, new SysVSHMConnectionHandler(sysVSharedMemory), new SysVSHMRequestHandler());
        connector.start();

        xServer.setSHMSegmentManager(new SHMSegmentManager(sysVSharedMemory));
    }

    @Override
    public void stop() {
        if (connector != null) {
            connector.stop();
            connector = null;
        }

        sysVSharedMemory.deleteAll();
    }
}

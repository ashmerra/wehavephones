package com.wehavephones.xconnector;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

public final class ClientSocket {
    public final ByteArrayOutputStream sent = new ByteArrayOutputStream();
    public void write(ByteBuffer data) {
        while (data.hasRemaining()) sent.write(data.get());
    }
    public void sendAncillaryMsg(ByteBuffer data, int fd) { write(data); }
}

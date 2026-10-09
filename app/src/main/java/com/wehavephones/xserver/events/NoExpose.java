package com.wehavephones.xserver.events;

import com.wehavephones.xconnector.XOutputStream;
import com.wehavephones.xconnector.XStreamLock;
import com.wehavephones.xserver.ClientOpcodes;

import java.io.IOException;

/** Successful CopyArea completion requested by GC.graphics-exposures. */
public final class NoExpose extends Event {
    private final int drawable;

    public NoExpose(int drawable) {
        super(14);
        this.drawable = drawable;
    }

    @Override
    public void send(short sequenceNumber, XOutputStream output) throws IOException {
        try (XStreamLock lock = output.lock()) {
            output.writeByte(code);
            output.writeByte((byte)0);
            output.writeShort(sequenceNumber);
            output.writeInt(drawable);
            output.writeShort((short)0);
            output.writeByte(ClientOpcodes.COPY_AREA);
            output.writePad(21);
        }
    }
}

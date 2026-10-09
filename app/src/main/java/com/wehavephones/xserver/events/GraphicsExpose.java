package com.wehavephones.xserver.events;

import com.wehavephones.xconnector.XOutputStream;
import com.wehavephones.xconnector.XStreamLock;
import com.wehavephones.xserver.ClientOpcodes;

import java.io.IOException;

/** CopyArea pixels unavailable in the source drawable. */
public final class GraphicsExpose extends Event {
    private final int drawable;
    private final int[] rectangle;
    private final int remaining;

    public GraphicsExpose(int drawable, int[] rectangle, int remaining) {
        super(13);
        this.drawable = drawable;
        this.rectangle = rectangle;
        this.remaining = remaining;
    }

    @Override
    public void send(short sequenceNumber, XOutputStream output) throws IOException {
        try (XStreamLock lock = output.lock()) {
            output.writeByte(code);
            output.writeByte((byte)0);
            output.writeShort(sequenceNumber);
            output.writeInt(drawable);
            for (int value : rectangle) output.writeShort((short)value);
            output.writeShort((short)0);
            output.writeShort((short)remaining);
            output.writeByte(ClientOpcodes.COPY_AREA);
            output.writePad(11);
        }
    }
}

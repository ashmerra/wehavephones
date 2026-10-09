package com.wehavephones.xserver.events;

import com.wehavephones.xconnector.XOutputStream;
import com.wehavephones.xconnector.XStreamLock;

import java.io.IOException;

public class RawEvent extends Event {
    private final byte[] data;

    public RawEvent(byte[] data) {
        super(data[0]);
        this.data = data;
    }

    @Override
    public void send(short sequenceNumber, XOutputStream outputStream) throws IOException {
        try (XStreamLock lock = outputStream.lock()) {
            // SendEvent must mark the event synthetic and use the recipient's
            // request sequence, not the sequence supplied by the sender.
            outputStream.writeByte((byte)(data[0] | 0x80));
            if ((data[0] & 0x7f) == 11) {
                // KeymapNotify has key bitmap bytes in place of a sequence.
                outputStream.write(data, 1, 31);
            }
            else {
                outputStream.writeByte(data[1]);
                outputStream.writeShort(sequenceNumber);
                outputStream.write(data, 4, 28);
            }
        }
    }
}

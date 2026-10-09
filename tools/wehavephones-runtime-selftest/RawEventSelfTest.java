package com.wehavephones.xserver;

import com.wehavephones.xconnector.ClientSocket;
import com.wehavephones.xconnector.XOutputStream;
import com.wehavephones.xserver.events.RawEvent;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public final class RawEventSelfTest {
    public static void main(String[] args) throws Exception {
        for (ByteOrder order : new ByteOrder[]{ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN}) {
            byte[] input = new byte[32];
            for (int i = 0; i < input.length; i++) input[i] = (byte)i;
            input[0] = 2;
            byte[] original = input.clone();
            RawEvent event = new RawEvent(input);
            ClientSocket socket = new ClientSocket();
            XOutputStream output = new XOutputStream(socket, 8);
            output.setByteOrder(order);
            event.send((short)0x2345, output);
            event.send((short)0xfedc, output);
            byte[] sent = socket.sent.toByteArray();
            if (sent.length != 64) throw new AssertionError("32-byte events");
            ByteBuffer wire = ByteBuffer.wrap(sent).order(order);
            for (int offset : new int[]{0, 32}) {
                if ((sent[offset] & 0xff) != 0x82 || sent[offset + 1] != input[1])
                    throw new AssertionError("Synthetic flag and detail");
                for (int i = 4; i < 32; i++) if (sent[offset + i] != input[i])
                    throw new AssertionError("Event payload");
            }
            if (wire.getShort(2) != (short)0x2345 || wire.getShort(34) != (short)0xfedc)
                throw new AssertionError("Recipient sequence, including wraparound");
            if (!Arrays.equals(input, original)) throw new AssertionError("Sender bytes mutated");

            socket.sent.reset();
            input[0] = 11;
            new RawEvent(input).send((short)0x9876, output);
            sent = socket.sent.toByteArray();
            if ((sent[0] & 0xff) != 0x8b) throw new AssertionError("Synthetic KeymapNotify");
            for (int i = 1; i < 32; i++) if (sent[i] != input[i])
                throw new AssertionError("Keymap bitmap must not be overwritten");
        }
        System.out.println("RawEventSelfTest PASS");
    }
}

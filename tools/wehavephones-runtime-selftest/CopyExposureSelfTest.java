package com.wehavephones.xserver;

import com.wehavephones.xconnector.ClientSocket;
import com.wehavephones.xconnector.XOutputStream;
import com.wehavephones.xserver.events.GraphicsExpose;
import com.wehavephones.xserver.events.NoExpose;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class CopyExposureSelfTest {
    public static void main(String[] args) throws Exception {
        for (ByteOrder order : new ByteOrder[]{ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN}) {
            ClientSocket socket = new ClientSocket();
            XOutputStream output = new XOutputStream(socket, 8);
            output.setByteOrder(order);
            new NoExpose(0x12345678).send((short)0x2345, output);
            new GraphicsExpose(0x12345678, new int[]{10, 20, 30, 40}, 2).send((short)0x2346, output);
            byte[] bytes = socket.sent.toByteArray();
            if (bytes.length != 64) throw new AssertionError("Events must each occupy 32 bytes");
            ByteBuffer b = ByteBuffer.wrap(bytes).order(order);
            if (b.get(0) != 14 || b.getShort(2) != 0x2345 || b.getInt(4) != 0x12345678 ||
                    b.getShort(8) != 0 || b.get(10) != ClientOpcodes.COPY_AREA) throw new AssertionError("NoExpose wire fields");
            for (int i = 11; i < 32; i++) if (b.get(i) != 0) throw new AssertionError("NoExpose padding");
            if (b.get(32) != 13 || b.getShort(34) != 0x2346 || b.getInt(36) != 0x12345678 ||
                    b.getShort(40) != 10 || b.getShort(42) != 20 || b.getShort(44) != 30 ||
                    b.getShort(46) != 40 || b.getShort(48) != 0 || b.getShort(50) != 2 ||
                    b.get(52) != ClientOpcodes.COPY_AREA) throw new AssertionError("GraphicsExpose wire fields");
            for (int i = 53; i < 64; i++) if (b.get(i) != 0) throw new AssertionError("GraphicsExpose padding");
        }
        System.out.println("CopyExposureSelfTest PASS");
    }
}

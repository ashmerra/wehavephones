package com.wehavephones.xserver;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class WineWindowStyleSelfTest {
    private static void expect(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        // Captured styles from the black overlay and the page beneath it.
        ByteBuffer overlay = ByteBuffer.wrap(new byte[]{(byte)0xa0, 0, 8, 0});
        overlay.position(2);
        expect(WineWindowStyle.isLayered(overlay, 32), "layered overlay must blend");
        expect(overlay.position() == 2, "property reads must preserve position");
        expect(overlay.order() == ByteOrder.BIG_ENDIAN, "property reads must preserve byte order");
        expect(!WineWindowStyle.isLayered(ByteBuffer.wrap(new byte[]{0, 1, 0, 0}), 32),
                "ordinary content must stay opaque even if pixels have zero alpha");
        expect(!WineWindowStyle.isLayered(null, 32), "missing property");
        expect(!WineWindowStyle.isLayered(ByteBuffer.allocate(3), 32), "truncated property");
        expect(!WineWindowStyle.isLayered(overlay, 8), "wrong format");
        expect(!WineWindowStyle.isLayered(ByteBuffer.allocate(4), 32), "removed layered flag");
        expect(WineWindowStyle.isLayered(overlay.asReadOnlyBuffer(), 32), "read-only property");
        System.out.println("WineWindowStyleSelfTest passed");
    }
}

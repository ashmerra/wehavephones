package com.wehavephones.xserver;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Decodes Wine's _WINE_HWND_EXSTYLE property without changing its buffer. */
public final class WineWindowStyle {
    private static final int WS_EX_LAYERED = 0x00080000;

    private WineWindowStyle() {}

    public static boolean isLayered(ByteBuffer data, int formatBits) {
        return formatBits == 32 && data != null && data.limit() >= 4
                && (data.duplicate().order(ByteOrder.LITTLE_ENDIAN).getInt(0)
                & WS_EX_LAYERED) != 0;
    }
}

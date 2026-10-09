package com.wehavephones.sysvshm;

import com.wehavephones.alsaserver.ALSAClientSelfTest;
import java.nio.ByteBuffer;

public final class SysVSharedMemory {
    public static void unmapSHMSegment(ByteBuffer buffer, long size) {
        if (size != buffer.capacity()) throw new AssertionError("incorrect mapping size");
        ALSAClientSelfTest.onEvent("unmap");
    }
}

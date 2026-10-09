package com.wehavephones.xserver;

public interface XLock extends AutoCloseable {
    @Override
    void close();
}

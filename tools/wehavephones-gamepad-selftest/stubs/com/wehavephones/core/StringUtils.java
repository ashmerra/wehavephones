package com.wehavephones.core;
public class StringUtils {
    public static String fromANSIString(byte[] bytes) { return new String(bytes).trim(); }
    public static String formatBytes(long size) { return Long.toString(size); }
}

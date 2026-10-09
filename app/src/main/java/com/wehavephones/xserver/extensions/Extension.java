package com.wehavephones.xserver.extensions;

import com.wehavephones.xconnector.XInputStream;
import com.wehavephones.xconnector.XOutputStream;
import com.wehavephones.xserver.XClient;
import com.wehavephones.xserver.errors.XRequestError;

import java.io.IOException;

public interface Extension {
    String getName();

    byte getMajorOpcode();

    byte getFirstErrorId();

    byte getFirstEventId();

    void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError;
}

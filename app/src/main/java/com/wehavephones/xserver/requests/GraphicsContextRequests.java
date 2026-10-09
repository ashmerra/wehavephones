package com.wehavephones.xserver.requests;

import com.wehavephones.xconnector.XInputStream;
import com.wehavephones.xconnector.XOutputStream;
import com.wehavephones.xserver.Drawable;
import com.wehavephones.xserver.GraphicsContext;
import com.wehavephones.xserver.Bitmask;
import com.wehavephones.xserver.XClient;
import com.wehavephones.xserver.errors.BadDrawable;
import com.wehavephones.xserver.errors.BadGraphicsContext;
import com.wehavephones.xserver.errors.BadIdChoice;
import com.wehavephones.xserver.errors.BadLength;
import com.wehavephones.xserver.errors.BadValue;
import com.wehavephones.xserver.errors.XRequestError;

public abstract class GraphicsContextRequests {
    public static void setClipRectangles(XClient client, XInputStream inputStream, XOutputStream outputStream) throws XRequestError {
        int ordering = client.getRequestData() & 0xff;
        if (ordering > 3) throw new BadValue(ordering);
        int gcId = inputStream.readInt();
        short x = inputStream.readShort();
        short y = inputStream.readShort();
        GraphicsContext gc = client.xServer.graphicsContextManager.getGraphicsContext(gcId);
        if (gc == null) throw new BadGraphicsContext(gcId);
        int length = client.getRemainingRequestLength();
        if (length % 8 != 0) throw new BadLength();
        short[] rectangles = new short[length / 2];
        for (int i = 0; i < rectangles.length; i++) rectangles[i] = inputStream.readShort();
        gc.clip.setRectangles(x, y, rectangles);
    }

    public static void createGC(XClient client, XInputStream inputStream, XOutputStream outputStream) throws XRequestError {
        int gcId = inputStream.readInt();
        int drawableId = inputStream.readInt();
        Bitmask valueMask = new Bitmask(inputStream.readInt());

        if (!client.isValidResourceId(gcId)) throw new BadIdChoice(gcId);

        Drawable drawable = client.xServer.drawableManager.getDrawable(drawableId);
        if (drawable == null) throw new BadDrawable(drawableId);
        GraphicsContext graphicsContext = client.xServer.graphicsContextManager.createGraphicsContext(gcId, drawable);
        if (graphicsContext == null) throw new BadIdChoice(gcId);

        client.registerAsOwnerOfResource(graphicsContext);
        if (!valueMask.isEmpty()) client.xServer.graphicsContextManager.updateGraphicsContext(graphicsContext, valueMask, inputStream);
    }

    public static void copyGC(XClient client, XInputStream inputStream, XOutputStream outputStream) throws XRequestError {
        int srcGCId = inputStream.readInt();
        int dstGCId = inputStream.readInt();
        Bitmask valueMask = new Bitmask(inputStream.readInt());
        GraphicsContext srcGC = client.xServer.graphicsContextManager.getGraphicsContext(srcGCId);
        GraphicsContext dstGC = client.xServer.graphicsContextManager.getGraphicsContext(dstGCId);
        if (srcGC == null) throw new BadGraphicsContext(srcGCId);
        if (dstGC == null) throw new BadGraphicsContext(dstGCId);
        if (!valueMask.isEmpty()) client.xServer.graphicsContextManager.copyGraphicsContext(srcGC, dstGC, valueMask);
    }

    public static void changeGC(XClient client, XInputStream inputStream, XOutputStream outputStream) throws XRequestError {
        int gcId = inputStream.readInt();
        Bitmask valueMask = new Bitmask(inputStream.readInt());
        GraphicsContext graphicsContext = client.xServer.graphicsContextManager.getGraphicsContext(gcId);
        if (graphicsContext == null) throw new BadGraphicsContext(gcId);

        if (!valueMask.isEmpty()) client.xServer.graphicsContextManager.updateGraphicsContext(graphicsContext, valueMask, inputStream);
    }

    public static void freeGC(XClient client, XInputStream inputStream, XOutputStream outputStream) throws XRequestError {
        client.xServer.graphicsContextManager.freeGraphicsContext(inputStream.readInt());
    }
}

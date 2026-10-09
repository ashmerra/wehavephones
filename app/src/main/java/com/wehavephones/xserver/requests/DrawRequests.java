package com.wehavephones.xserver.requests;

import static com.wehavephones.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.wehavephones.xconnector.XInputStream;
import com.wehavephones.xconnector.XOutputStream;
import com.wehavephones.xconnector.XStreamLock;
import com.wehavephones.xserver.Drawable;
import com.wehavephones.xserver.GraphicsContext;
import com.wehavephones.xserver.XClient;
import com.wehavephones.xserver.errors.BadDrawable;
import com.wehavephones.xserver.errors.BadGraphicsContext;
import com.wehavephones.xserver.errors.BadMatch;
import com.wehavephones.xserver.errors.XRequestError;
import com.wehavephones.xserver.events.NoExpose;
import com.wehavephones.xserver.events.GraphicsExpose;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;

public abstract class DrawRequests {
    public enum Format {BITMAP, XY_PIXMAP, Z_PIXMAP}
    private enum CoordinateMode {ORIGIN, PREVIOUS}

    public static void putImage(XClient client, XInputStream inputStream, XOutputStream outputStream) throws XRequestError {
        Format format = Format.values()[client.getRequestData()];
        int drawableId = inputStream.readInt();
        int gcId = inputStream.readInt();
        short width = inputStream.readShort();
        short height = inputStream.readShort();
        short dstX = inputStream.readShort();
        short dstY = inputStream.readShort();
        byte leftPad = inputStream.readByte();
        byte depth = inputStream.readByte();
        inputStream.skip(2);
        int length = client.getRemainingRequestLength();
        ByteBuffer data = inputStream.readByteBuffer(length);

        Drawable drawable =  client.xServer.drawableManager.getDrawable(drawableId);
        if (drawable == null) throw new BadDrawable(drawableId);

        GraphicsContext graphicsContext = client.xServer.graphicsContextManager.getGraphicsContext(gcId);
        if (graphicsContext == null) throw new BadGraphicsContext(gcId);

        if (!(graphicsContext.getFunction() == GraphicsContext.Function.COPY || format == Format.Z_PIXMAP)) {
            throw new UnsupportedOperationException("GC Function other than COPY is not supported.");
        }

        switch (format) {
            case BITMAP:
                if (leftPad != 0) throw new UnsupportedOperationException("PutImage.leftPad cannot be != 0.");
                if (depth == 1) {
                    drawable.drawImage((short)0, (short)0, dstX, dstY, width, height, (byte)1, data, width, height, graphicsContext);
                }
                else throw new BadMatch();
                break;
            case XY_PIXMAP:
                if (drawable.visual.depth != depth) throw new BadMatch();
                break;
            case Z_PIXMAP:
                if (leftPad == 0) {
                    drawable.drawImage((short)0, (short)0, dstX, dstY, width, height, depth, data, width, height, graphicsContext);
                }
                else throw new BadMatch();
                break;
        }
    }

    public static void getImage(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        Format format = Format.values()[client.getRequestData()];
        int drawableId = inputStream.readInt();
        short x = inputStream.readShort();
        short y = inputStream.readShort();
        short width = inputStream.readShort();
        short height = inputStream.readShort();
        inputStream.skip(4);

        if (format != Format.Z_PIXMAP) throw new UnsupportedOperationException("Only Z_PIXMAP is supported.");

        Drawable drawable =  client.xServer.drawableManager.getDrawable(drawableId);
        if (drawable == null) throw new BadDrawable(drawableId);
        int visualId = client.xServer.pixmapManager.getPixmap(drawableId) == null ? drawable.visual.id : 0;
        ByteBuffer data = drawable.getImage(x, y, width, height);
        int length = data.limit();

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte(drawable.visual.depth);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt((length + 3) / 4);
            outputStream.writeInt(visualId);
            outputStream.writePad(20);
            outputStream.write(data);
            if ((-length & 3) > 0) outputStream.writePad(-length & 3);
        }
    }

    public static void copyArea(XClient client, XInputStream inputStream, XOutputStream outputStream) throws XRequestError {
        int srcDrawableId = inputStream.readInt();
        int dstDrawableId = inputStream.readInt();
        int gcId = inputStream.readInt();
        short srcX = inputStream.readShort();
        short srcY = inputStream.readShort();
        short dstX = inputStream.readShort();
        short dstY = inputStream.readShort();
        short width = inputStream.readShort();
        short height = inputStream.readShort();

        Drawable srcDrawable = client.xServer.drawableManager.getDrawable(srcDrawableId);
        if (srcDrawable == null) throw new BadDrawable(srcDrawableId);
        if (srcDrawable.getData() == null) {
            throw new IllegalStateException("srcDrawable has null data!");
        }

        Drawable dstDrawable = client.xServer.drawableManager.getDrawable(dstDrawableId);
        if (dstDrawable == null) throw new BadDrawable(dstDrawableId);
        if (dstDrawable.getData() == null) {
            throw new IllegalStateException("dstDrawable has null data!");
        }


        GraphicsContext graphicsContext =  client.xServer.graphicsContextManager.getGraphicsContext(gcId);
        if (graphicsContext == null) throw new BadGraphicsContext(gcId);

        if (srcDrawable.visual.depth != dstDrawable.visual.depth) throw new BadMatch();

        dstDrawable.copyArea(srcX, srcY, dstX, dstY, width, height, srcDrawable, graphicsContext);
        if (graphicsContext.getGraphicsExposures()) {
            ArrayList<int[]> missing = new ArrayList<>();
            graphicsContext.clip.forEachMissingSource(srcX, srcY, dstX, dstY, width, height,
                    srcDrawable.width, srcDrawable.height, dstDrawable.width, dstDrawable.height,
                    (x, y, w, h) -> missing.add(new int[]{x, y, w, h}));
            if (missing.isEmpty()) client.sendEvent(new NoExpose(dstDrawableId));
            else {
                for (int i = 0; i < missing.size(); i++) {
                    client.sendEvent(new GraphicsExpose(dstDrawableId, missing.get(i), missing.size() - i - 1));
                }
            }
        }
    }

    public static void polyLine(XClient client, XInputStream inputStream, XOutputStream outputStream) throws XRequestError {
        CoordinateMode coordinateMode = CoordinateMode.values()[client.getRequestData()];
        int drawableId = inputStream.readInt();
        int gcId = inputStream.readInt();

        Drawable drawable = client.xServer.drawableManager.getDrawable(drawableId);
        if (drawable == null) throw new BadDrawable(drawableId);
        GraphicsContext graphicsContext = client.xServer.graphicsContextManager.getGraphicsContext(gcId);
        if (graphicsContext == null) throw new BadGraphicsContext(gcId);
        int length = client.getRemainingRequestLength();

        short[] points = new short[length / 2];
        int i = 0;
        while (length != 0) {
            points[i++] = inputStream.readShort();
            points[i++] = inputStream.readShort();
            length -= 4;
        }

        if (coordinateMode == CoordinateMode.ORIGIN && graphicsContext.getLineWidth() > 0) {
            drawable.drawLines(graphicsContext.getForeground(), graphicsContext.getLineWidth(), points);
        }
    }

    public static void polyFillRectangle(XClient client, XInputStream inputStream, XOutputStream outputStream) throws XRequestError {
        int drawableId = inputStream.readInt();
        int gcId = inputStream.readInt();

        Drawable drawable = client.xServer.drawableManager.getDrawable(drawableId);
        if (drawable == null) throw new BadDrawable(drawableId);
        GraphicsContext graphicsContext = client.xServer.graphicsContextManager.getGraphicsContext(gcId);
        if (graphicsContext == null) throw new BadGraphicsContext(gcId);
        int length = client.getRemainingRequestLength();

        while (length != 0) {
            short x = inputStream.readShort();
            short y = inputStream.readShort();
            short width = inputStream.readShort();
            short height = inputStream.readShort();
            graphicsContext.clip.forEach(x, y, width, height, drawable.width, drawable.height,
                    (cx, cy, cw, ch) -> drawable.fillRect(cx, cy, cw, ch, graphicsContext.getForeground()));
            length -= 8;
        }
    }
}

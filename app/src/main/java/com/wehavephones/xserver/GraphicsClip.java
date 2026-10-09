package com.wehavephones.xserver;

/** Rectangle clip state shared by X11 image, copy and fill requests. */
public final class GraphicsClip {
    private int originX;
    private int originY;
    private short[] rectangles;

    public interface RectangleConsumer {
        void accept(int x, int y, int width, int height);
    }

    public int getOriginX() { return originX; }
    public int getOriginY() { return originY; }
    public void setOriginX(int x) { originX = x; }
    public void setOriginY(int y) { originY = y; }
    public void clear() { rectangles = null; }
    public void copyFrom(GraphicsClip source) {
        rectangles = source.rectangles == null ? null : source.rectangles.clone();
    }

    public void setRectangles(int x, int y, short[] rectangles) {
        originX = x;
        originY = y;
        this.rectangles = rectangles.clone();
    }

    /** A null region is unrestricted; an empty region permits no drawing. */
    public void forEach(int x, int y, int width, int height, int drawableWidth,
                        int drawableHeight, RectangleConsumer consumer) {
        int left = Math.max(0, x);
        int top = Math.max(0, y);
        int right = Math.min(drawableWidth, x + width);
        int bottom = Math.min(drawableHeight, y + height);
        if (right <= left || bottom <= top) return;
        if (rectangles == null) {
            consumer.accept(left, top, right - left, bottom - top);
            return;
        }
        for (int i = 0; i < rectangles.length; i += 4) {
            int cx = originX + rectangles[i];
            int cy = originY + rectangles[i + 1];
            int l = Math.max(left, cx);
            int t = Math.max(top, cy);
            int r = Math.min(right, cx + (rectangles[i + 2] & 0xffff));
            int b = Math.min(bottom, cy + (rectangles[i + 3] & 0xffff));
            if (r > l && b > t) consumer.accept(l, t, r - l, b - t);
        }
    }

    /** Destination rectangles whose requested source pixels are outside its image. */
    public void forEachMissingSource(int srcX, int srcY, int dstX, int dstY, int width,
                                     int height, int sourceWidth, int sourceHeight,
                                     int destWidth, int destHeight, RectangleConsumer consumer) {
        forEach(dstX, dstY, width, height, destWidth, destHeight, (x, y, w, h) -> {
            int left = Math.max(x, dstX - srcX);
            int top = Math.max(y, dstY - srcY);
            int right = Math.min(x + w, dstX - srcX + sourceWidth);
            int bottom = Math.min(y + h, dstY - srcY + sourceHeight);
            if (right <= left || bottom <= top) {
                consumer.accept(x, y, w, h);
                return;
            }
            if (top > y) consumer.accept(x, y, w, top - y);
            if (bottom < y + h) consumer.accept(x, bottom, w, y + h - bottom);
            if (left > x) consumer.accept(x, top, left - x, bottom - top);
            if (right < x + w) consumer.accept(right, top, x + w - right, bottom - top);
        });
    }
}

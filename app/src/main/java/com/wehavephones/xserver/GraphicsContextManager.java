package com.wehavephones.xserver;

import android.util.SparseArray;

import com.wehavephones.xconnector.XInputStream;

public class GraphicsContextManager extends XResourceManager {
    private final SparseArray<GraphicsContext> graphicsContexts = new SparseArray<>();

    public GraphicsContext getGraphicsContext(int id) {
        return graphicsContexts.get(id);
    }

    public GraphicsContext createGraphicsContext(int id, Drawable drawable) {
        if (graphicsContexts.indexOfKey(id) >= 0) return null;
        GraphicsContext graphicsContext = new GraphicsContext(id, drawable);
        graphicsContexts.put(id, graphicsContext);
        triggerOnCreateResourceListener(graphicsContext);
        return graphicsContext;
    }

    public void copyGraphicsContext(GraphicsContext src, GraphicsContext dst, Bitmask valueMask) {
        updateGraphicsContext(dst, src, valueMask);
    }

    public void freeGraphicsContext(int id) {
        triggerOnFreeResourceListener(graphicsContexts.get(id));
        graphicsContexts.remove(id);
    }

    public void updateGraphicsContext(GraphicsContext dstContext, GraphicsContext srcContext, Bitmask valueMask) {
        for (int index : valueMask) {
            switch (index) {
                case GraphicsContext.FLAG_FUNCTION:
                    dstContext.setFunction(srcContext.getFunction());
                    break;
                case GraphicsContext.FLAG_PLANE_MASK:
                    dstContext.setPlaneMask(srcContext.getPlaneMask());
                    break;
                case GraphicsContext.FLAG_FOREGROUND:
                    dstContext.setForeground(srcContext.getForeground());
                    break;
                case GraphicsContext.FLAG_BACKGROUND:
                    dstContext.setBackground(srcContext.getBackground());
                    break;
                case GraphicsContext.FLAG_LINE_WIDTH:
                    dstContext.setLineWidth(srcContext.getLineWidth());
                    break;
                case GraphicsContext.FLAG_SUBWINDOW_MODE:
                    dstContext.setSubwindowMode(srcContext.getSubwindowMode());
                    break;
                case GraphicsContext.FLAG_CLIP_X_ORIGIN:
                    dstContext.clip.setOriginX(srcContext.clip.getOriginX());
                    break;
                case GraphicsContext.FLAG_CLIP_Y_ORIGIN:
                    dstContext.clip.setOriginY(srcContext.clip.getOriginY());
                    break;
                case GraphicsContext.FLAG_CLIP_MASK:
                    dstContext.clip.copyFrom(srcContext.clip);
                    break;
                case GraphicsContext.FLAG_GRAPHICS_EXPOSURES:
                    dstContext.setGraphicsExposures(srcContext.getGraphicsExposures());
                    break;
                case GraphicsContext.FLAG_LINE_STYLE:
                case GraphicsContext.FLAG_CAP_STYLE:
                case GraphicsContext.FLAG_JOIN_STYLE:
                case GraphicsContext.FLAG_FILL_STYLE:
                case GraphicsContext.FLAG_FILL_RULE:
                case GraphicsContext.FLAG_DASHES:
                case GraphicsContext.FLAG_ARC_MODE:
                case GraphicsContext.FLAG_TILE:
                case GraphicsContext.FLAG_STIPPLE:
                case GraphicsContext.FLAG_FONT:
                case GraphicsContext.FLAG_TILE_STIPPLE_X_ORIGIN:
                case GraphicsContext.FLAG_TILE_STIPPLE_Y_ORIGIN:
                case GraphicsContext.FLAG_DASH_OFFSET:
                    break;
            }
        }
    }

    public void updateGraphicsContext(GraphicsContext graphicsContext, Bitmask valueMask, XInputStream inputStream) {
        for (int index : valueMask) {
            switch (index) {
                case GraphicsContext.FLAG_FUNCTION:
                    graphicsContext.setFunction(GraphicsContext.Function.values()[inputStream.readInt()]);
                    break;
                case GraphicsContext.FLAG_PLANE_MASK:
                    graphicsContext.setPlaneMask(inputStream.readInt());
                    break;
                case GraphicsContext.FLAG_FOREGROUND:
                    graphicsContext.setForeground(inputStream.readInt());
                    break;
                case GraphicsContext.FLAG_BACKGROUND:
                    graphicsContext.setBackground(inputStream.readInt());
                    break;
                case GraphicsContext.FLAG_LINE_WIDTH:
                    graphicsContext.setLineWidth(inputStream.readInt());
                    break;
                case GraphicsContext.FLAG_SUBWINDOW_MODE:
                    graphicsContext.setSubwindowMode(GraphicsContext.SubwindowMode.values()[inputStream.readInt()]);
                    break;
                case GraphicsContext.FLAG_CLIP_X_ORIGIN:
                    graphicsContext.clip.setOriginX(inputStream.readInt());
                    break;
                case GraphicsContext.FLAG_CLIP_Y_ORIGIN:
                    graphicsContext.clip.setOriginY(inputStream.readInt());
                    break;
                case GraphicsContext.FLAG_CLIP_MASK:
                    // Bitmap masks retain the inherited unsupported behavior. None
                    // resets a rectangle clip installed by SetClipRectangles.
                    inputStream.readInt();
                    graphicsContext.clip.clear();
                    break;
                case GraphicsContext.FLAG_GRAPHICS_EXPOSURES:
                    graphicsContext.setGraphicsExposures(inputStream.readInt() != 0);
                    break;
                case GraphicsContext.FLAG_LINE_STYLE:
                case GraphicsContext.FLAG_CAP_STYLE:
                case GraphicsContext.FLAG_JOIN_STYLE:
                case GraphicsContext.FLAG_FILL_STYLE:
                case GraphicsContext.FLAG_FILL_RULE:
                case GraphicsContext.FLAG_DASHES:
                case GraphicsContext.FLAG_ARC_MODE:
                case GraphicsContext.FLAG_TILE:
                case GraphicsContext.FLAG_STIPPLE:
                case GraphicsContext.FLAG_FONT:
                case GraphicsContext.FLAG_TILE_STIPPLE_X_ORIGIN:
                case GraphicsContext.FLAG_TILE_STIPPLE_Y_ORIGIN:
                case GraphicsContext.FLAG_DASH_OFFSET:
                    inputStream.skip(4);
                    break;
            }
        }
    }
}

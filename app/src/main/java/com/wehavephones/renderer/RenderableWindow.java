package com.wehavephones.renderer;

import com.wehavephones.xserver.Drawable;

class RenderableWindow {
    final Drawable content;
    final boolean layered;
    short rootX;
    short rootY;

    public RenderableWindow(Drawable content, int rootX, int rootY, boolean layered) {
        this.content = content;
        this.layered = layered;
        this.rootX = (short) rootX;
        this.rootY = (short) rootY;
    }
}

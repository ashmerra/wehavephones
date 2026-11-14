package com.wehavephones.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.wehavephones.renderer.GLRenderer;
import com.wehavephones.xserver.XServer;

@SuppressLint("ViewConstructor")
public class XServerView extends GLSurfaceView {
    /**
     * Games here present 30 to 60 FPS, yet the S23 kept its panel at 120 Hz
     * for the whole session. The vote lets the display run at 60 Hz.
     */
    private static final float GAME_FRAME_RATE = 60f;
    private final GLRenderer renderer;

    public XServerView(Context context, XServer xServer) {
        super(context);
        setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setEGLContextClientVersion(3);
        setEGLConfigChooser(8, 8, 8, 8, 0, 0);
        setPreserveEGLContextOnPause(true);
        renderer = new GLRenderer(this, xServer);
        setRenderer(renderer);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        super.surfaceCreated(holder);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            holder.getSurface().setFrameRate(GAME_FRAME_RATE, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT);
        }
    }

    public GLRenderer getRenderer() {
        return renderer;
    }
}

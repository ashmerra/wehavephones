package com.wehavephones;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;
import android.util.Rational;
import android.view.View;
import android.view.ViewGroup;

import com.wehavephones.xserver.extensions.PresentExtension;

import java.util.HashMap;
import java.util.Map;

/**
 * Picture-in-picture for the game session: the small window's parameters and
 * the overlays (touch controls, on-screen controls, performance overlay) that
 * are hidden while the game plays in it. Overlays are hidden with their alpha,
 * not their visibility, because showing or hiding the touch controls also
 * changes WoW's UI mode. Children tagged with {@link #KEEP_VISIBLE} stay.
 */
final class SessionPictureInPicture {
    private static final String TAG = "SessionPip";
    /** Frame interval in the small window: 20 FPS. */
    static final long FRAME_INTERVAL_MS = PresentExtension.SMALL_WINDOW_FRAME_INTERVAL_MS;
    /** View tag key (and value true) for root children that stay visible in the small window. */
    static final int KEEP_VISIBLE = R.id.keep_in_picture_in_picture;
    /** Android accepts aspect ratios from 1:2.39 to 2.39:1. */
    private static final float MAX_ASPECT = 2.39f;

    private final ViewGroup root;
    private final View game;
    private final Map<View, Float> hidden = new HashMap<>();
    private boolean active;

    SessionPictureInPicture(ViewGroup root, View game) {
        this.root = root;
        this.game = game;
        root.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
            @Override
            public void onChildViewAdded(View parent, View child) {
                if (active) hide(child);
            }

            @Override
            public void onChildViewRemoved(View parent, View child) {
                hidden.remove(child);
            }
        });
    }

    static boolean isSupported(Activity activity) {
        return activity.getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
    }

    /**
     * Window parameters for an X screen of the given size. With
     * {@code autoEnter} (Android 12+) the system moves the game into the
     * small window as the user leaves it.
     */
    static PictureInPictureParams params(int width, int height, boolean autoEnter) {
        PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder();
        if (width > 0 && height > 0) {
            float aspect = Math.max(1 / MAX_ASPECT, Math.min(MAX_ASPECT, width / (float) height));
            builder.setAspectRatio(new Rational(Math.round(aspect * 1000), 1000));
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(autoEnter);
            // A game frame is not video: cross-fade on resize instead of stretching it.
            builder.setSeamlessResizeEnabled(false);
        }
        return builder.build();
    }

    static void apply(Activity activity, PictureInPictureParams params) {
        try {
            activity.setPictureInPictureParams(params);
        }
        catch (RuntimeException e) {
            Log.w(TAG, "Could not set picture-in-picture parameters", e);
        }
    }

    static boolean enter(Activity activity, PictureInPictureParams params) {
        try {
            return activity.enterPictureInPictureMode(params);
        }
        catch (RuntimeException e) {
            // Turned off for this app in Android's settings, or not allowed right now.
            Log.w(TAG, "Could not enter picture-in-picture", e);
            return false;
        }
    }

    /** Hides the overlays in the small window, or restores them. */
    void setActive(boolean active) {
        if (this.active == active) return;
        this.active = active;
        if (active) {
            for (int i = 0; i < root.getChildCount(); i++) hide(root.getChildAt(i));
        }
        else {
            for (Map.Entry<View, Float> entry : hidden.entrySet()) entry.getKey().setAlpha(entry.getValue());
            hidden.clear();
        }
    }

    private void hide(View child) {
        if (child == game || hidden.containsKey(child) || Boolean.TRUE.equals(child.getTag(KEEP_VISIBLE))) return;
        hidden.put(child, child.getAlpha());
        child.setAlpha(0f);
    }
}

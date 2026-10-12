package com.wehavephones.mobile;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.wehavephones.container.Container;
import com.wehavephones.container.ContainerManager;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Locale;

public final class WeHavePhonesContainers {
    private WeHavePhonesContainers() {}

    public static Container resolve(Context context) {
        ContainerManager manager = new ContainerManager(context);
        ArrayList<Container> containers = manager.getContainers();
        if (containers.isEmpty()) return null;

        int storedId = WeHavePhonesConfig.getContainerId(context);
        if (storedId > 0) {
            Container stored = manager.getContainerById(storedId);
            if (stored != null) return stored;
        }

        for (Container container : containers) {
            String name = container.getName().toLowerCase(Locale.US);
            if (name.contains("wow") || name.contains("world of warcraft") || name.contains("wehavephones")) {
                WeHavePhonesConfig.setContainerId(context, container.id);
                return container;
            }
        }

        Container first = containers.get(0);
        WeHavePhonesConfig.setContainerId(context, first.id);
        return first;
    }

    public static void remember(Context context, Container container) {
        if (container != null) WeHavePhonesConfig.setContainerId(context, container.id);
    }

    public static interface CreateCallback {
        void onCreated(Container container, String error);
    }

    public static void createDefault(final Context context, final String wineVersion, final CreateCallback callback) {
        create(context, "WeHavePhones WoW", wineVersion, callback);
    }

    public static void create(final Context context, final String name, final String wineVersion, final CreateCallback callback) {
        final Handler handler = new Handler(Looper.getMainLooper());
        // createContainerAsync() binds its Handler to the calling thread, so it
        // must start on a Looper thread; first-run setup calls us from a worker.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(() -> create(context, name, wineVersion, callback));
            return;
        }
        try {
            JSONObject data = new JSONObject();
            data.put("name", name);
            if (wineVersion != null && !wineVersion.isEmpty()) data.put("wineVersion", wineVersion);
            data.put("screenSize", WeHavePhonesConfig.getGameScreenSize(context));

            ContainerManager manager = new ContainerManager(context);
            manager.createContainerAsync(data, (container) -> {
                if (container != null) {
                    remember(context, container);
                    handler.post(() -> callback.onCreated(container, null));
                }
                else {
                    handler.post(() -> callback.onCreated(null, "Container creation failed (missing imagefs or container pattern)"));
                }
            });
        }
        catch (Exception e) {
            handler.post(() -> callback.onCreated(null, "Container creation failed: " + e.getMessage()));
        }
    }
}

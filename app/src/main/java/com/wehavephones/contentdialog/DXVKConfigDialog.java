package com.wehavephones.contentdialog;

import android.content.Context;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Spinner;

import com.wehavephones.R;
import com.wehavephones.core.AppUtils;
import com.wehavephones.core.DefaultVersion;
import com.wehavephones.core.EnvVars;
import com.wehavephones.core.FileUtils;
import com.wehavephones.core.KeyValueSet;
import com.wehavephones.core.StringUtils;
import com.wehavephones.xenvironment.ImageFs;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class DXVKConfigDialog extends ContentDialog {
    public static final String DEFAULT_CONFIG = "version="+DefaultVersion.DXVK+",framerate=0,maxDeviceMemory=0";

    public DXVKConfigDialog(View anchor) {
        super(anchor.getContext(), R.layout.dxvk_config_dialog);
        setIcon(R.drawable.icon_settings);
        setTitle("DXVK "+anchor.getContext().getString(R.string.configuration));

        final Spinner sVersion = findViewById(R.id.SVersion);
        final Spinner sFramerate = findViewById(R.id.SFramerate);
        final Spinner sMaxDeviceMemory = findViewById(R.id.SMaxDeviceMemory);

        List<String> versionItems = new ArrayList<>();
        for (String version : getBundledVersions(anchor.getContext())) {
            versionItems.add(version.equals(DefaultVersion.DXVK) ? version + " (Default)" : version);
        }
        sVersion.setAdapter(new ArrayAdapter<>(anchor.getContext(), android.R.layout.simple_spinner_dropdown_item, versionItems));

        KeyValueSet config = parseConfig(anchor.getTag());
        AppUtils.setSpinnerSelectionFromIdentifier(sVersion, config.get("version"));
        AppUtils.setSpinnerSelectionFromIdentifier(sFramerate, config.get("framerate"));
        AppUtils.setSpinnerSelectionFromNumber(sMaxDeviceMemory, config.get("maxDeviceMemory"));

        setOnConfirmCallback(() -> {
            // Keep version as an identifier (supports suffixes like "2.3.1-arm64ec-gplasync").
            config.put("version", StringUtils.parseIdentifier(sVersion.getSelectedItem()));
            config.put("framerate", StringUtils.parseNumber(sFramerate.getSelectedItem()));
            config.put("maxDeviceMemory", StringUtils.parseNumber(sMaxDeviceMemory.getSelectedItem()));
            anchor.setTag(config.toString());
        });
    }

    /**
     * DXVK versions bundled as assets/dxwrapper/dxvk-<version>.tzst, the
     * default first, then newest first. Only these can be extracted at launch.
     */
    public static List<String> getBundledVersions(Context context) {
        List<String> versions = new ArrayList<>();
        try {
            String[] entries = context.getAssets().list("dxwrapper");
            if (entries != null) {
                for (String entry : entries) {
                    if (entry.startsWith("dxvk-") && entry.endsWith(".tzst")) {
                        versions.add(entry.substring("dxvk-".length(), entry.length() - ".tzst".length()));
                    }
                }
            }
        }
        catch (IOException e) {
            Log.w("DXVKConfigDialog", "Could not list dxwrapper assets: " + e);
        }
        versions.sort(Collections.reverseOrder());
        if (versions.remove(DefaultVersion.DXVK)) versions.add(0, DefaultVersion.DXVK);
        return versions;
    }

    public static KeyValueSet parseConfig(Object config) {
        String data = config != null && !config.toString().isEmpty() ? config.toString() : DEFAULT_CONFIG;
        return new KeyValueSet(data);
    }

    public static void setEnvVars(Context context, KeyValueSet config, EnvVars envVars) {
        // DXVK runs inside Wine but uses Win32 file APIs. When DXVK_STATE_CACHE_PATH is a POSIX
        // path, Wine will treat it as a host path. Our sessions use absolute Android paths
        // (not a chroot), so a guest-relative "/home/xuser/.cache" path doesn't exist.
        //
        // Align with Ludashi: point it at the real imagefs path on the host.
        File rootDir = ImageFs.find(context).getRootDir();
        String cachePath = rootDir.getPath() + ImageFs.CACHE_PATH;
        envVars.put("DXVK_STATE_CACHE_PATH", cachePath);
        // Best-effort: ensure the directory exists so DXVK can create the state cache file.
        //noinspection ResultOfMethodCallIgnored
        new File(cachePath).mkdirs();

        File dxvkConfigFile = new File(rootDir, ImageFs.CONFIG_PATH+"/dxvk.conf");

        String content = "";

        String maxDeviceMemory = config.get("maxDeviceMemory");
        if (!maxDeviceMemory.isEmpty() && !maxDeviceMemory.equals("0")) {
            content += "dxgi.maxDeviceMemory = "+maxDeviceMemory+"\n";
            content += "dxgi.maxSharedMemory = "+maxDeviceMemory+"\n";
        }

        String framerate = config.get("framerate");
        if (!framerate.isEmpty() && !framerate.equals("0")) {
            content += "dxgi.maxFrameRate = "+framerate+"\n";
            content += "d3d9.maxFrameRate = "+framerate+"\n";
        }

        FileUtils.delete(dxvkConfigFile);
        if (!content.isEmpty() && FileUtils.writeString(dxvkConfigFile, content)) {
            envVars.put("DXVK_CONFIG_FILE", ImageFs.CONFIG_PATH+"/dxvk.conf");
        }
    }
}

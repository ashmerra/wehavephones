package com.wehavephones.core;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;

/**
 * Registers libwehavephones_vulkan_compat.so as an implicit Vulkan layer for GPUs
 * without native BCn (found on the Pixel 8a, Mali-G715). The wrapper and the
 * BCn compute layer only apply their BCn emulation and Mali shader patches
 * through Vulkan 1.0 entry points; the layer routes DXVK's Vulkan 1.3 paths
 * to them. It also replaces the wrapper's OpConstantComposite pass, which
 * corrupts the heap, so that pass is switched off whenever the layer is used.
 * See app/src/main/cpp/wehavephones_vulkan_compat.
 */
public final class VulkanCompatLayer {
    public static final String ENABLE_ENV = "ENABLE_WEHAVEPHONES_VULKAN_COMPAT";
    static final String LIBRARY_NAME = "libwehavephones_vulkan_compat.so";
    static final String MANIFEST_PATH = "usr/share/vulkan/implicit_layer.d/wehavephones_vulkan_compat.json";
    /** Manifest written by builds before the layer was renamed. */
    static final String LEGACY_MANIFEST_PATH = "usr/share/vulkan/implicit_layer.d/wehavephones_bcn_formats.json";

    private VulkanCompatLayer() {}

    /** Writes the manifest (the library path changes with every APK install) and enables the layer. */
    public static void install(Context context, File rootDir, EnvVars envVars) {
        File library = new File(context.getApplicationInfo().nativeLibraryDir, LIBRARY_NAME);
        if (!library.isFile()) return;
        File manifest = new File(rootDir, MANIFEST_PATH);
        String json = manifestJson(library.getAbsolutePath());
        if (json == null) return;
        if (!manifest.isFile() || !json.equals(FileUtils.readString(manifest))) {
            File parent = manifest.getParentFile();
            if (parent != null) parent.mkdirs();
            if (!FileUtils.writeString(manifest, json)) return;
        }
        FileUtils.delete(new File(rootDir, LEGACY_MANIFEST_PATH));
        if (!envVars.has(ENABLE_ENV)) envVars.put(ENABLE_ENV, "1");
        if (!envVars.has("WRAPPER_NO_PATCH_OPCONSTCOMP")) envVars.put("WRAPPER_NO_PATCH_OPCONSTCOMP", "1");
    }

    static String manifestJson(String libraryPath) {
        try {
            JSONObject functions = new JSONObject();
            functions.put("vkGetInstanceProcAddr", "WeHavePhonesVulkanCompat_GetInstanceProcAddr");
            functions.put("vkGetDeviceProcAddr", "WeHavePhonesVulkanCompat_GetDeviceProcAddr");

            JSONObject layer = new JSONObject();
            layer.put("name", "VK_LAYER_WEHAVEPHONES_vulkan_compat");
            layer.put("type", "GLOBAL");
            layer.put("library_path", libraryPath);
            layer.put("api_version", "1.3.0");
            layer.put("implementation_version", "1");
            layer.put("description", "Routes Vulkan 1.3 format queries, uploads and inline shaders to the wrapper fixes");
            layer.put("functions", functions);
            layer.put("enable_environment", new JSONObject().put(ENABLE_ENV, "1"));
            layer.put("disable_environment", new JSONObject().put("DISABLE_WEHAVEPHONES_VULKAN_COMPAT", "1"));

            JSONObject manifest = new JSONObject();
            manifest.put("file_format_version", "1.0.0");
            manifest.put("layer", layer);
            return manifest.toString(2);
        }
        catch (JSONException e) {
            return null;
        }
    }
}

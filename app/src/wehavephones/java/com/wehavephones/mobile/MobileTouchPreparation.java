package com.wehavephones.mobile;

import android.content.Context;
import com.wehavephones.container.Container;
import com.wehavephones.mobile.addons.install.AddonInstaller;
import com.wehavephones.mobile.addons.required.MobileTouchSetup;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Applies the opt-in to all installed products before any launch, including Battle.net's Play. */
public final class MobileTouchPreparation {
    private MobileTouchPreparation() {}
    public static void prepare(Context context, Container container) throws IOException {
        if (ProcessScanner.findWowProcess() != null) throw new IOException("Close World of Warcraft before changing touch mode");
        byte[] script;
        try (InputStream stream = context.getAssets().open("wehavephones/addons/WeHavePhonesTouch/Touch.lua");
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int length;
            while ((length = stream.read(buffer)) != -1) output.write(buffer, 0, length);
            script = output.toByteArray();
        }
        synchronized (AddonInstaller.LOCK) {
            for (WowProduct product : WowInstallationScanner.scan(container)) {
                int clientInterface = WowFlavor.interfaceFromVersion(product.getVersion());
                MobileTouchSetup.prepare(product.directory, WeHavePhonesConfig.isMobileTouchEnabled(context),
                        WeHavePhonesConfig.getMobileTouchScale(context), clientInterface > 0 ? clientInterface : product.getFlavor().fallbackInterface, script);
            }
        }
    }
}

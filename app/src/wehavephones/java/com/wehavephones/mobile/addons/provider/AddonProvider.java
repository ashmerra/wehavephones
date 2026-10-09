package com.wehavephones.mobile.addons.provider;

import com.wehavephones.mobile.addons.model.Channel;
import com.wehavephones.mobile.addons.model.ProviderType;
import com.wehavephones.mobile.addons.model.RemoteAddon;
import com.wehavephones.mobile.addons.model.RemoteFile;
import com.wehavephones.mobile.WowFlavor;

import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface AddonProvider {
    ProviderType type();

    boolean requiresKey();

    boolean isConfigured();

    List<RemoteAddon> search(String query, WowFlavor flavor) throws ProviderException;

    RemoteAddon getById(String externalId, WowFlavor flavor) throws ProviderException;

    RemoteFile getLatestFile(String externalId, WowFlavor flavor, Channel channel,
                             String currentVersion) throws ProviderException;

    Map<String, RemoteFile> getLatestFiles(Collection<String> externalIds, WowFlavor flavor,
                                           Channel channel) throws ProviderException;

    ResolvedDownload resolveDownload(RemoteFile file) throws ProviderException;

    String getChangelog(String externalId, String releaseId, WowFlavor flavor) throws ProviderException;

    /** Cheap authenticated call to validate the configured key. */
    void testKey() throws ProviderException;
}

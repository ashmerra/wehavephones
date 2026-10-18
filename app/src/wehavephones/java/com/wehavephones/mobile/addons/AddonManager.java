package com.wehavephones.mobile.addons;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import com.wehavephones.mobile.WeHavePhonesContainers;
import com.wehavephones.mobile.WeHavePhonesPaths;
import com.wehavephones.mobile.WowInstallationScanner;
import com.wehavephones.mobile.WowProduct;
import com.wehavephones.mobile.addons.install.AddonInstaller;
import com.wehavephones.mobile.addons.install.DiscoveredAddon;
import com.wehavephones.mobile.addons.install.FileOps;
import com.wehavephones.mobile.addons.install.SafeZipExtractor;
import com.wehavephones.mobile.addons.install.ZipImporter;
import com.wehavephones.mobile.addons.model.AddonRecord;
import com.wehavephones.mobile.addons.model.Channel;
import com.wehavephones.mobile.addons.model.InstallStatus;
import com.wehavephones.mobile.addons.model.ProviderType;
import com.wehavephones.mobile.addons.model.RemoteAddon;
import com.wehavephones.mobile.addons.model.RemoteFile;
import com.wehavephones.mobile.addons.model.TocData;
import com.wehavephones.mobile.WowFlavor;
import com.wehavephones.mobile.addons.net.Downloader;
import com.wehavephones.mobile.addons.provider.AddonProvider;
import com.wehavephones.mobile.addons.provider.ProviderException;
import com.wehavephones.mobile.addons.provider.ProviderRegistry;
import com.wehavephones.mobile.addons.provider.ResolvedDownload;
import com.wehavephones.mobile.addons.repo.AddonIndex;
import com.wehavephones.mobile.addons.repo.AddonIndexStore;
import com.wehavephones.mobile.addons.required.ConsolePort;
import com.wehavephones.mobile.addons.required.ControllerSetupAddon;
import com.wehavephones.mobile.addons.scan.AddonScanner;
import com.wehavephones.mobile.addons.update.UpdateChecker;
import com.wehavephones.mobile.addons.util.GameGuard;
import com.wehavephones.container.Container;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Activity-scoped orchestrator for the addon manager: products, scanning,
 * index persistence, provider searches, installs and updates.
 */
public final class AddonManager {
    public interface Callback<T> {
        void onSuccess(T value);

        void onError(String message);
    }

    public static final class ImportPreview {
        public final File stagingDir;
        public final ArrayList<DiscoveredAddon> addons;
        public final String sourceName;

        ImportPreview(File stagingDir, ArrayList<DiscoveredAddon> addons, String sourceName) {
            this.stagingDir = stagingDir;
            this.addons = addons;
            this.sourceName = sourceName;
        }
    }

    private interface Job<T> {
        T run() throws Exception;
    }

    private static final long PRODUCT_CACHE_MS = 60 * 1000L;
    private static final long MAX_IMPORT_BYTES = 512L * 1024L * 1024L;

    private final Context context;
    private final AddonIndexStore store;
    private final ProviderRegistry registry;
    private final ExecutorService executor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object indexLock = new Object();

    private AddonIndex index;
    private volatile boolean closed;
    /** Staging folders of import previews this manager holds in {@link AddonInstaller}. */
    private final Set<File> heldPreviews = ConcurrentHashMap.newKeySet();
    private ArrayList<WowProduct> cachedProducts = new ArrayList<>();
    private long productsLoadedAt;

    public AddonManager(Context context) {
        this.context = context.getApplicationContext();
        this.store = new AddonIndexStore(this.context);
        this.index = this.store.load();
        this.registry = new ProviderRegistry(this.context);
        // Single worker: addon filesystem mutations and the JSON index are
        // serialized; every UI action is queued instead of racing.
        this.executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "wehavephones-addons");
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    public void shutdown() {
        closed = true;
        executor.shutdownNow();
        // A preview whose dialog went away with the activity is never confirmed.
        // Release it so the next rescan deletes it. Deleting it here could race
        // an install that is still moving folders out of it.
        for (File staging : heldPreviews) AddonInstaller.releaseStaging(staging);
        heldPreviews.clear();
    }

    public boolean isClosed() {
        return closed;
    }

    public ProviderRegistry providers() {
        return registry;
    }

    public AddonIndex index() {
        return index;
    }

    public List<AddonRecord> records() {
        synchronized (indexLock) {
            return new ArrayList<>(index.addons);
        }
    }

    public AddonRecord findRecord(String id) {
        synchronized (indexLock) {
            return index.findById(id);
        }
    }

    public AddonIndex.Settings settings() {
        synchronized (indexLock) {
            return new AddonIndex.Settings(index.preferNolib, index.autoCheckOnOpen, index.confirmDelete,
                    index.wowRootOverride);
        }
    }

    public void updateSettings(boolean preferNolib, boolean autoCheckOnOpen, boolean confirmDelete,
                               String wowRootOverride) {
        synchronized (indexLock) {
            index.preferNolib = preferNolib;
            index.autoCheckOnOpen = autoCheckOnOpen;
            index.confirmDelete = confirmDelete;
            index.wowRootOverride = wowRootOverride != null ? wowRootOverride : "";
        }
        runAsync(new Job<Boolean>() {
            @Override
            public Boolean run() throws Exception {
                saveIndex();
                return Boolean.TRUE;
            }
        }, null);
    }

    /** Products are scanned lazily and cached for a minute. */
    public List<WowProduct> products() {
        long now = System.currentTimeMillis();
        if (now - productsLoadedAt < PRODUCT_CACHE_MS && !cachedProducts.isEmpty()) {
            return cachedProducts;
        }
        Container container = WeHavePhonesContainers.resolve(context);
        cachedProducts = container != null ? WowInstallationScanner.scan(container) : new ArrayList<WowProduct>();
        productsLoadedAt = now;
        return cachedProducts;
    }

    public WowProduct selectedProduct() {
        List<WowProduct> products = products();
        if (products.isEmpty()) return null;
        String preferred = AddonPrefs.getSelectedProduct(context);
        WowProduct fallback = null;
        for (WowProduct product : products) {
            if (product.directoryName.equals(preferred)) return product;
            if (fallback == null && product.directoryName.equals("_retail_")) fallback = product;
        }
        if (fallback != null) return fallback;
        return products.get(0);
    }

    public void selectProduct(String directoryName) {
        AddonPrefs.setSelectedProduct(context, directoryName);
        productsLoadedAt = 0;
    }

    public WowFlavor flavor() {
        WowProduct product = selectedProduct();
        return product != null ? product.getFlavor() : WowFlavor.UNKNOWN;
    }

    public File addonsDir() {
        WowProduct product = selectedProduct();
        if (product == null) return null;
        if (index.wowRootOverride != null && !index.wowRootOverride.isEmpty() && product.directory != null) {
            String relative = product.directory.getName();
            return new File(new File(index.wowRootOverride, relative), "Interface/AddOns");
        }
        return new File(product.directory, "Interface/AddOns");
    }

    public int interfaceNumber() {
        WowProduct product = selectedProduct();
        if (product == null) return 0;
        int computed = WowFlavor.interfaceFromVersion(product.getVersion());
        if (computed > 0) return computed;
        return flavor().fallbackInterface;
    }

    public void rescan(final Callback<Integer> callback) {
        runAsync(new Job<Integer>() {
            @Override
            public Integer run() throws Exception {
                File addons = addonsDir();
                if (addons == null || !addons.isDirectory()) return -1;
                AddonInstaller.recoverInterrupted(addons);
                syncScan();
                saveIndex();
                return records().size();
            }
        }, callback);
    }

    public void checkUpdates(final boolean force, final Callback<Integer> callback) {
        runAsync(new Job<Integer>() {
            @Override
            public Integer run() throws Exception {
                List<AddonRecord> flavorRecords;
                synchronized (indexLock) {
                    flavorRecords = index.forFlavor(flavor().directoryName);
                    int checked = UpdateChecker.checkAll(flavorRecords, registry, flavor(), interfaceNumber(), null);
                    saveIndex();
                    AddonPrefs.setLastUpdateAll(context, System.currentTimeMillis());
                    return checked;
                }
            }
        }, callback);
    }

    public void search(final ProviderType providerType, final String query,
                       final Callback<List<RemoteAddon>> callback) {
        runAsync(new Job<List<RemoteAddon>>() {
            @Override
            public List<RemoteAddon> run() throws Exception {
                AddonProvider provider = registry.get(providerType);
                if (provider == null) throw new ProviderException(ProviderException.Kind.UNSUPPORTED,
                        "Unknown provider");
                if (!provider.isConfigured()) throw new ProviderException(ProviderException.Kind.AUTH,
                        providerType.displayName + " is not configured");
                return provider.search(query, flavor());
            }
        }, callback);
    }

    public void testProvider(final ProviderType providerType, final Callback<String> callback) {
        runAsync(new Job<String>() {
            @Override
            public String run() throws Exception {
                AddonProvider provider = registry.get(providerType);
                if (provider == null) throw new ProviderException(ProviderException.Kind.UNSUPPORTED,
                        "Unknown provider");
                provider.testKey();
                return providerType.displayName + " key OK";
            }
        }, callback);
    }

    public void saveProviderKey(final ProviderType providerType, final String value,
                                final Callback<String> callback) {
        runAsync(new Job<String>() {
            @Override
            public String run() throws Exception {
                registry.setKey(providerType, value);
                return "saved";
            }
        }, callback);
    }

    public void installRemote(final RemoteAddon addon, final RemoteFile file,
                              final Callback<String> callback) {
        runAsync(new Job<String>() {
            @Override
            public String run() throws Exception {
                return installInternal(addon, file);
            }
        }, callback);
    }

    /** Search-result install: resolves the latest file first when needed. */
    public void installAddon(final RemoteAddon addon, final Callback<String> callback) {
        runAsync(new Job<String>() {
            @Override
            public String run() throws Exception {
                if (addon == null) throw new IOException("No addon selected");
                AddonProvider provider = registry.get(addon.provider);
                if (provider == null || !provider.isConfigured()) {
                    throw new ProviderException(ProviderException.Kind.AUTH,
                            addon.provider.displayName + " is not configured");
                }
                RemoteFile file = addon.latestFile;
                if (file == null) {
                    file = provider.getLatestFile(addon.externalId, flavor(), Channel.STABLE, "");
                }
                if (file == null) throw new IOException("No installable release found for " + addon.name);
                return installInternal(addon, file);
            }
        }, callback);
    }

    public void updateRecord(final AddonRecord record, final Callback<String> callback) {
        runAsync(new Job<String>() {
            @Override
            public String run() throws Exception {
                if (record == null) throw new IOException("No addon selected");
                AddonProvider provider = registry.get(record.provider());
                if (provider == null || !provider.isConfigured()) {
                    throw new ProviderException(ProviderException.Kind.AUTH,
                            record.provider().displayName + " is not configured");
                }
                RemoteFile file = provider.getLatestFile(record.externalId, flavor(),
                        Channel.fromId(record.channel), record.installedVersion());
                if (file == null) throw new IOException("No installable release found");
                RemoteAddon addon = new RemoteAddon(record.provider(), record.externalId,
                        record.displayName(), record.toc != null ? record.toc.author : "",
                        record.toc != null ? record.toc.notes : "",
                        record.toc != null ? record.toc.website : "", 0, file);
                return installInternal(addon, file);
            }
        }, callback);
    }

    public void updateAll(final Callback<Integer> callback) {
        runAsync(new Job<Integer>() {
            @Override
            public Integer run() throws Exception {
                requireGameClosed();
                List<AddonRecord> pending;
                synchronized (indexLock) {
                    List<AddonRecord> flavorRecords = index.forFlavor(flavor().directoryName);
                    UpdateChecker.checkAll(flavorRecords, registry, flavor(), interfaceNumber(), null);
                    pending = new ArrayList<>();
                    for (AddonRecord record : flavorRecords) {
                        if (InstallStatus.UPDATE.id.equals(record.status) && record.hasProviderBinding()) {
                            pending.add(record);
                        }
                    }
                }
                int updated = 0;
                for (AddonRecord record : pending) {
                    try {
                        AddonProvider provider = registry.get(record.provider());
                        if (provider == null || !provider.isConfigured()) continue;
                        RemoteFile file = provider.getLatestFile(record.externalId, flavor(),
                                Channel.fromId(record.channel), record.installedVersion());
                        if (file == null) continue;
                        RemoteAddon addon = new RemoteAddon(record.provider(), record.externalId,
                                record.displayName(), record.toc != null ? record.toc.author : "",
                                record.toc != null ? record.toc.notes : "",
                                record.toc != null ? record.toc.website : "", 0, file);
                        installInternal(addon, file);
                        updated++;
                    }
                    catch (Exception e) {
                        synchronized (indexLock) {
                            record.lastError = messageFor(e);
                        }
                    }
                }
                syncScan();
                saveIndex();
                AddonPrefs.setLastUpdateAll(context, System.currentTimeMillis());
                return updated;
            }
        }, callback);
    }

    private String installInternal(final RemoteAddon addon, final RemoteFile file) throws Exception {
        File addons = ensureAddonsDir();
        requireGameClosed();
        AddonProvider provider = registry.get(file.provider);
        if (provider == null || !provider.isConfigured()) {
            throw new ProviderException(ProviderException.Kind.AUTH,
                    file.provider.displayName + " is not configured");
        }
        File tmpZip = new File(tmpDir(), FileOps.uuid() + ".zip");
        File staging = new File(addons, ".wehavephones-staging-" + FileOps.uuid());
        try {
            ResolvedDownload resolved = provider.resolveDownload(file);
            Downloader.download(resolved.url, resolved.headers, resolved.secretHeaders,
                    resolved.secretHosts, tmpZip, Downloader.MAX_DOWNLOAD_BYTES, null);
            if (resolved.sha1 != null && !resolved.sha1.isEmpty()) {
                String actual = Downloader.hash(tmpZip, "SHA-1");
                if (!actual.equalsIgnoreCase(resolved.sha1)) {
                    throw new IOException("Downloaded archive failed the SHA-1 check");
                }
            }
            String sha256 = Downloader.hash(tmpZip, "SHA-256");
            SafeZipExtractor.extract(tmpZip, staging, null);
            ArrayList<DiscoveredAddon> discovered =
                    new ArrayList<>(ZipImporter.discoverAddons(staging, flavor()));
            if (discovered.isEmpty()) {
                throw new IOException("Archive contains no installable addon folder");
            }
            AddonRecord existing = findExisting(file.provider, file.externalId, discovered);
            boolean keepDisabled = existing != null && !existing.enabled;
            AddonInstaller.Result result = AddonInstaller.install(new AddonInstaller.Request(
                    addons, staging, discovered,
                    existing != null ? existing.folderNames : null, keepDisabled));
            AddonInstaller.commit(result);
            cleanupDeleteBackups(null);
            recordInstall(addon, file, result.installedFolders, sha256, keepDisabled, existing);
            syncScan();
            saveIndex();
            return result.installedFolders.isEmpty() ? "" : result.installedFolders.get(0);
        }
        finally {
            FileOps.deleteRecursively(tmpZip);
            FileOps.deleteRecursively(staging);
        }
    }

    public void prepareImport(final Uri uri, final Callback<ImportPreview> callback) {
        runAsync(new Job<ImportPreview>() {
            @Override
            public ImportPreview run() throws Exception {
                ensureAddonsDir();
                File tmpZip = new File(tmpDir(), "import-" + FileOps.uuid() + ".zip");
                copyUri(uri, tmpZip);
                File staging = new File(addonsDir(), ".wehavephones-staging-" + FileOps.uuid());
                try {
                    SafeZipExtractor.extract(tmpZip, staging, null);
                }
                catch (Exception e) {
                    FileOps.deleteRecursively(staging);
                    throw e;
                }
                finally {
                    FileOps.deleteRecursively(tmpZip);
                }
                ArrayList<DiscoveredAddon> discovered =
                        new ArrayList<>(ZipImporter.discoverAddons(staging, flavor()));
                if (discovered.isEmpty()) {
                    FileOps.deleteRecursively(staging);
                    throw new IOException("ZIP contains no addon folder with a matching TOC file");
                }
                String sourceName = uri != null && uri.getLastPathSegment() != null
                        ? uri.getLastPathSegment() : "import.zip";
                // Returning from the file picker rescans the list while the preview waits.
                holdPreview(staging);
                if (closed) {
                    dropPreview(staging);
                    throw new IOException("Addon manager closed");
                }
                return new ImportPreview(staging, discovered, sourceName);
            }
        }, callback);
    }

    public void discardPreview(ImportPreview preview) {
        if (preview == null) return;
        runAsync(new Job<Boolean>() {
            @Override
            public Boolean run() throws Exception {
                dropPreview(preview.stagingDir);
                return Boolean.TRUE;
            }
        }, null);
    }

    private void holdPreview(File staging) {
        heldPreviews.add(staging);
        AddonInstaller.holdStaging(staging);
    }

    private void dropPreview(File staging) {
        heldPreviews.remove(staging);
        AddonInstaller.discardStaging(staging);
    }

    public void confirmImport(final ImportPreview preview, final List<DiscoveredAddon> chosen,
                              final Callback<String> callback) {
        runAsync(new Job<String>() {
            @Override
            public String run() throws Exception {
                if (preview == null) throw new IOException("No addons selected for import");
                // The dialog is gone, so the preview is spent whether or not the install succeeds.
                try {
                    if (chosen == null || chosen.isEmpty()) {
                        throw new IOException("No addons selected for import");
                    }
                    File addons = ensureAddonsDir();
                    requireGameClosed();
                    ArrayList<String> previous = new ArrayList<>();
                    for (DiscoveredAddon addon : chosen) {
                        AddonRecord existing = findByFolder(addon.folderName);
                        if (existing != null) {
                            for (String folder : existing.folderNames) {
                                if (!previous.contains(folder)) previous.add(folder);
                            }
                        }
                    }
                    AddonInstaller.Result result = AddonInstaller.install(new AddonInstaller.Request(
                            addons, preview.stagingDir, chosen, previous, false));
                    AddonInstaller.commit(result);
                    cleanupDeleteBackups(null);
                    for (DiscoveredAddon addon : chosen) {
                        recordLocalInstall(addon, preview.sourceName, result.installedFolders);
                    }
                    syncScan();
                    saveIndex();
                    return result.installedFolders.isEmpty() ? "" : result.installedFolders.get(0);
                }
                finally {
                    dropPreview(preview.stagingDir);
                }
            }
        }, callback);
    }

    public void setEnabled(final AddonRecord record, final boolean enabled, final Callback<String> callback) {
        runAsync(new Job<String>() {
            @Override
            public String run() throws Exception {
                if (record == null) throw new IOException("No addon selected");
                requireGameClosed();
                AddonInstaller.setEnabled(ensureAddonsDir(), record.folderNames, enabled);
                syncScan();
                saveIndex();
                return enabled ? "enabled" : "disabled";
            }
        }, callback);
    }

    public void delete(final AddonRecord record, final Callback<String> callback) {
        runAsync(new Job<String>() {
            @Override
            public String run() throws Exception {
                if (record == null) throw new IOException("No addon selected");
                if (isRequired(record)) {
                    throw new IOException(record.displayName()
                            + " is required for controller play and is reinstalled automatically. Disable it instead");
                }
                requireGameClosed();
                File backup = AddonInstaller.removeToBackup(ensureAddonsDir(), record.folderNames);
                cleanupDeleteBackups(backup);
                synchronized (indexLock) {
                    index.remove(record);
                }
                syncScan();
                saveIndex();
                return backup.getName();
            }
        }, callback);
    }

    public void loadChangelog(final AddonRecord record, final Callback<String> callback) {
        runAsync(new Job<String>() {
            @Override
            public String run() throws Exception {
                if (record == null) throw new IOException("No addon selected");
                if (record.latestChangelog != null && !record.latestChangelog.isEmpty()) {
                    return record.latestChangelog;
                }
                ProviderType type = record.provider();
                AddonProvider provider = registry.get(type);
                if (provider == null || !provider.isConfigured()) return "";
                String releaseId = !record.latestReleaseId.isEmpty()
                        ? record.latestReleaseId : record.providerVersionId;
                return provider.getChangelog(record.externalId, releaseId, flavor());
            }
        }, callback);
    }

    /** ConsolePort and its setup companion outside Forever: the app reinstalls them, so they can be disabled but not deleted. */
    public boolean isRequired(AddonRecord record) {
        if (record == null || !ConsolePort.requiredFor(flavor())) return false;
        if (ConsolePort.isConsolePort(record)) return true;
        for (String folder : record.folderNames) {
            if (ControllerSetupAddon.isFolder(folder)) return true;
        }
        return false;
    }

    public AddonProvider providerFor(AddonRecord record) {
        return record != null ? registry.get(record.provider()) : null;
    }

    // ---------------------------------------------------------------- internals

    private static String capText(String value) {
        if (value == null) return "";
        return value.length() > 20000 ? value.substring(0, 20000) : value;
    }

    private static int statusRank(AddonRecord record) {
        InstallStatus status = record.installStatus();
        switch (status) {
            case UPDATE: return 0;
            case ERROR: return 1;
            case UNKNOWN: return 2;
            case UP_TO_DATE: return 3;
            case LOCAL_ONLY: return 4;
            case REMOVED: return 5;
            case DISABLED:
            default: return 6;
        }
    }

    private void requireGameClosed() throws IOException {
        if (GameGuard.isGameRunning()) {
            throw new IOException("Close World of Warcraft first (" + GameGuard.runningExecutable() + ")");
        }
    }

    private File ensureAddonsDir() throws IOException {
        File addons = addonsDir();
        if (addons == null) throw new IOException("No World of Warcraft installation detected");
        if (!addons.isDirectory() && !addons.mkdirs()) {
            throw new IOException("Cannot create " + addons);
        }
        return addons;
    }

    private File tmpDir() {
        File dir = new File(WeHavePhonesPaths.wehavephonesDir(context), "addons-tmp");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    private void syncScan() {
        File addons = addonsDir();
        if (addons == null) return;
        synchronized (indexLock) {
            AddonScanner.ScanResult result = AddonScanner.scan(addons, flavor(),
                    index.forFlavor(flavor().directoryName));
            ArrayList<AddonRecord> others = new ArrayList<>();
            for (AddonRecord record : index.addons) {
                if (!record.flavorDir.equals(flavor().directoryName)) others.add(record);
            }
            index.addons.clear();
            index.addons.addAll(others);
            index.addons.addAll(result.records);
        }
    }

    private void saveIndex() throws IOException {
        synchronized (indexLock) {
            store.save(index);
        }
    }

    private AddonRecord findExisting(ProviderType provider, String externalId,
                                     List<DiscoveredAddon> discovered) {
        synchronized (indexLock) {
            AddonRecord byBinding = index.findByBinding(provider.id, externalId, flavor().directoryName);
            if (byBinding != null) return byBinding;
            for (DiscoveredAddon addon : discovered) {
                AddonRecord byFolder = findByFolder(addon.folderName);
                if (byFolder != null) return byFolder;
            }
            return null;
        }
    }

    private AddonRecord findByFolder(String folderName) {
        if (folderName == null) return null;
        synchronized (indexLock) {
            for (AddonRecord record : index.addons) {
                if (!record.flavorDir.equals(flavor().directoryName)) continue;
                for (String folder : record.folderNames) {
                    if (folder.equalsIgnoreCase(folderName)
                            || (folder + ".disabled").equalsIgnoreCase(folderName)) {
                        return record;
                    }
                }
            }
        }
        return null;
    }

    private void recordInstall(RemoteAddon addon, RemoteFile file, List<String> folders,
                               String sha256, boolean keepDisabled, AddonRecord existing) {
        synchronized (indexLock) {
            AddonRecord record = existing != null ? existing : new AddonRecord();
            record.flavorDir = flavor().directoryName;
            record.providerId = file.provider.id;
            record.externalId = file.externalId;
            record.providerVersionId = file.releaseId;
            record.channel = file.channel;
            if (addon != null && addon.name != null && !addon.name.isEmpty()) record.name = addon.name;
            record.folderNames.clear();
            record.folderNames.addAll(folders);
            record.enabled = !keepDisabled;
            record.unrecognized = false;
            record.installedAt = System.currentTimeMillis();
            record.installedFrom = file.downloadUrl != null ? file.downloadUrl : "";
            record.archiveSha256 = sha256;
            record.latestVersion = file.version;
            record.latestReleaseId = file.releaseId;
            record.latestReleaseDate = file.releaseDate;
            record.latestUrl = file.downloadUrl != null ? file.downloadUrl : "";
            record.latestChangelog = capText(file.changelog);
            record.lastCheckedAt = record.installedAt;
            record.lastError = "";
            record.setStatus(keepDisabled ? InstallStatus.DISABLED : InstallStatus.UP_TO_DATE);
            index.upsert(record);
        }
    }

    private void recordLocalInstall(DiscoveredAddon addon, String sourceName, List<String> folders) {
        synchronized (indexLock) {
            AddonRecord record = findByFolder(addon.folderName);
            if (record == null) record = new AddonRecord();
            record.flavorDir = flavor().directoryName;
            if (addon.toc != null) {
                TocData.ProviderBinding binding = addon.toc.identity();
                if (binding != null && binding.provider.isRemote()) {
                    record.providerId = binding.provider.id;
                    record.externalId = binding.externalId;
                }
            }
            record.name = addon.displayName();
            record.folderNames.clear();
            record.folderNames.addAll(folders);
            record.enabled = true;
            record.unrecognized = false;
            record.installedAt = System.currentTimeMillis();
            record.installedFrom = "local:" + sourceName;
            record.lastCheckedAt = record.installedAt;
            record.lastError = "";
            if (!record.hasProviderBinding() && (record.latestVersion == null || record.latestVersion.isEmpty())) {
                record.setStatus(InstallStatus.LOCAL_ONLY);
            }
            index.upsert(record);
        }
    }

    private void cleanupDeleteBackups(File keep) {
        File addons = addonsDir();
        if (addons == null) return;
        File[] children = addons.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (!child.isDirectory() || !FileOps.isDeletedDir(child.getName())) continue;
            if (keep != null && child.getPath().equals(keep.getPath())) continue;
            FileOps.deleteRecursively(child);
        }
    }

    private void copyUri(Uri uri, File target) throws IOException {
        if (uri == null) throw new IOException("No file selected");
        long total = 0;
        try (InputStream stream = context.getContentResolver().openInputStream(uri);
             OutputStream output = new FileOutputStream(target)) {
            if (stream == null) throw new IOException("Cannot open the selected file");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                total += read;
                if (total > MAX_IMPORT_BYTES) {
                    throw new IOException("Selected file is larger than the import limit");
                }
                output.write(buffer, 0, read);
            }
        }
        catch (IOException e) {
            FileOps.deleteRecursively(target);
            throw e;
        }
    }

    private <T> void runAsync(final Job<T> job, final Callback<T> callback) {
        if (closed) {
            if (callback != null) postError(callback, "Addon manager is no longer active");
            return;
        }
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final T value;
                    synchronized (AddonInstaller.LOCK) {
                        value = job.run();
                    }
                    if (callback != null && !closed) {
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (!closed) callback.onSuccess(value);
                            }
                        });
                    }
                }
                catch (final Throwable throwable) {
                    if (callback != null && !closed) {
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (!closed) callback.onError(messageFor(throwable));
                            }
                        });
                    }
                }
            }
        });
    }

    private void postError(final Callback<?> callback, final String message) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                callback.onError(message);
            }
        });
    }

    private static String messageFor(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isEmpty()) message = throwable.getClass().getSimpleName();
        if (throwable instanceof ProviderException) {
            ProviderException provider = (ProviderException)throwable;
            switch (provider.kind) {
                case AUTH: return "Authentication failed: " + message;
                case RATE_LIMIT: return "Rate limited: " + message;
                case UNSUPPORTED: return message;
                default: break;
            }
        }
        return message;
    }

    @SuppressWarnings("unused")
    public List<AddonRecord> recordsForCurrentFlavor() {
        synchronized (indexLock) {
            ArrayList<AddonRecord> result = new ArrayList<>();
            for (AddonRecord record : index.addons) {
                if (record.flavorDir.equals(flavor().directoryName)) result.add(record);
            }
            Collections.sort(result, new java.util.Comparator<AddonRecord>() {
                @Override
                public int compare(AddonRecord first, AddonRecord second) {
                    int rank = Integer.compare(statusRank(first), statusRank(second));
                    if (rank != 0) return rank;
                    return first.displayName().compareToIgnoreCase(second.displayName());
                }
            });
            return result;
        }
    }

}

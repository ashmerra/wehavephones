package com.wehavephones.mobile.devices;

import android.content.Context;
import android.util.Log;

import com.wehavephones.core.FileUtils;
import com.wehavephones.core.TarCompressorUtils;
import com.wehavephones.mobile.addons.install.SafeZipExtractor;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Makes rule drivers usable: a driver bundled in the APK is extracted to
 * files/contents/adrenotools/&lt;id&gt; (where AdrenotoolsManager and GpuProbe look), a driver with a
 * url is downloaded once, checked against its sha256 and installed the same way. Every install
 * is staged and the library hash is verified before the driver appears under its final name.
 */
public final class DeviceDriverStore {
    private static final String TAG = DeviceRulesStore.TAG;
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final long MAX_ARCHIVE_BYTES = 256L * 1024 * 1024;
    private static final int MAX_REDIRECTS = 5;
    private static final Object LOCK = new Object();
    private static final AtomicBoolean PREFETCHING = new AtomicBoolean();

    public interface Downloader {
        void download(String url, File target, long maxBytes) throws IOException;
    }

    // Replaced by host tests.
    static Downloader downloader = new HttpDownloader();

    private DeviceDriverStore() {}

    public static File driverDir(Context context, String driverId) {
        return new File(new File(context.getFilesDir(), "contents/adrenotools"), driverId);
    }

    static File downloadDir(Context context) {
        return new File(new File(new File(context.getFilesDir(), "wehavephones"), "devices"), "drivers");
    }

    /** True when the driver can be loaded now (installed, or installable from the APK or a verified download). */
    public static boolean ensureAvailable(Context context, DeviceRules.Driver driver) {
        if (driver.isSystem()) return true;
        synchronized (LOCK) {
            try {
                if (isInstalled(driverDir(context, driver.id), driver)) return true;
                if (driver.asset != null && assetExists(context, driver.asset)) {
                    Log.i(TAG, "installing driver " + driver.id + " from the APK");
                    return installFromAsset(context, driver);
                }
                File archive = downloadedArchive(context, driver);
                if (archive.isFile() && driver.sha256.equals(sha256(archive))) {
                    Log.i(TAG, "installing driver " + driver.id + " from the downloaded archive");
                    return installFromArchive(context, driver, archive);
                }
            }
            catch (IOException e) {
                Log.w(TAG, "driver " + driver.id + " unavailable: " + e.getMessage());
            }
            return false;
        }
    }

    /** Downloads and verifies the profile's url drivers in the background. */
    public static void prefetch(final Context context, final DeviceRules.Profile profile) {
        if (!PREFETCHING.compareAndSet(false, true)) return;
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    prefetchNow(context, profile);
                }
                finally {
                    PREFETCHING.set(false);
                }
            }
        }, "WeHavePhonesDevices-prefetch");
        thread.setDaemon(true);
        thread.start();
    }

    /** Blocking prefetch; call it off the UI thread. Drivers that ship in the APK are left alone. */
    static void prefetchNow(Context context, DeviceRules.Profile profile) {
        for (DeviceRules.Driver driver : profile.driverEntries) {
            if (driver.isSystem() || driver.url == null) continue;
            if (driver.asset != null && assetExists(context, driver.asset)) continue;
            try {
                downloadAndInstall(context, driver);
            }
            catch (IOException e) {
                Log.w(TAG, "driver " + driver.id + " download failed: " + e.getMessage());
            }
        }
    }

    /**
     * Makes one driver ready now (the Diagnostics driver picker): installs it from the APK or
     * downloads, verifies and installs it. Blocking; call it off the UI thread.
     */
    public static void install(Context context, DeviceRules.Driver driver) throws IOException {
        if (driver.isSystem()) return;
        if (ensureAvailable(context, driver)) return;
        if (driver.url == null) throw new IOException("driver " + driver.id + " is not in this app and has no download");
        if (!downloadAndInstall(context, driver)) throw new IOException("driver " + driver.id + " did not install");
    }

    private static boolean downloadAndInstall(Context context, DeviceRules.Driver driver) throws IOException {
        synchronized (LOCK) {
            if (isInstalled(driverDir(context, driver.id), driver)) return true;
            File archive = downloadedArchive(context, driver);
            if (!archive.isFile() || !driver.sha256.equals(sha256(archive))) {
                File dir = archive.getParentFile();
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Could not create " + dir);
                File part = new File(archive.getPath() + ".part");
                Log.i(TAG, "downloading driver " + driver.id);
                try {
                    downloader.download(driver.url, part, MAX_ARCHIVE_BYTES);
                    if (!driver.sha256.equals(sha256(part))) throw new IOException("sha256 mismatch");
                    archive.delete();
                    if (!part.renameTo(archive)) throw new IOException("could not save the download");
                }
                finally {
                    part.delete();
                }
            }
            boolean installed = installFromArchive(context, driver, archive);
            if (installed) archive.delete();
            return installed;
        }
    }

    /** True when the driver is installed under its rules entry (no download, no extraction). */
    public static boolean isInstalled(Context context, DeviceRules.Driver driver) {
        if (driver.isSystem()) return true;
        synchronized (LOCK) {
            try {
                return isInstalled(driverDir(context, driver.id), driver);
            }
            catch (IOException e) {
                return false;
            }
        }
    }

    static File downloadedArchive(Context context, DeviceRules.Driver driver) {
        return new File(downloadDir(context), driver.id + "-" + driver.sha256.substring(0, 12) + "." + driver.archiveKind());
    }

    /** meta.json names the expected library and the library hash matches the rules. */
    static boolean isInstalled(File dir, DeviceRules.Driver driver) throws IOException {
        File meta = new File(dir, "meta.json");
        if (!meta.isFile()) return false;
        try {
            String libraryName = new JSONObject(FileUtils.readString(meta)).optString("libraryName", "");
            if (!driver.libraryName.equals(libraryName)) return false;
        }
        catch (Exception e) {
            return false;
        }
        File library = new File(dir, driver.libraryName);
        return library.isFile() && driver.librarySha256.equals(sha256(library));
    }

    private static boolean assetExists(Context context, String asset) {
        try (InputStream in = context.getAssets().open(asset)) {
            return true;
        }
        catch (IOException e) {
            return false;
        }
    }

    private static boolean installFromAsset(Context context, DeviceRules.Driver driver) throws IOException {
        File staging = stagingDir(context, driver);
        FileUtils.delete(staging);
        if (!staging.mkdirs()) throw new IOException("Could not create " + staging);
        boolean ok = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, driver.asset, staging, guard(staging));
        return finishInstall(context, driver, staging, ok);
    }

    private static boolean installFromArchive(Context context, DeviceRules.Driver driver, File archive) throws IOException {
        File staging = stagingDir(context, driver);
        FileUtils.delete(staging);
        if (!staging.mkdirs()) throw new IOException("Could not create " + staging);
        boolean ok;
        if ("zip".equals(driver.archiveKind())) {
            ok = extractZip(archive, staging);
        }
        else {
            ok = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, archive, staging, guard(staging));
        }
        return finishInstall(context, driver, staging, ok);
    }

    private static boolean extractZip(File archive, File staging) {
        SafeZipExtractor.Limits limits = SafeZipExtractor.Limits.defaults();
        limits.maxEntries = 64;
        limits.maxEntryBytes = 128L * 1024 * 1024;
        limits.maxTotalBytes = 160L * 1024 * 1024;
        try {
            SafeZipExtractor.extract(archive, staging, limits);
            return true;
        }
        catch (Exception e) {
            Log.w(TAG, "driver archive rejected: " + e.getMessage());
            return false;
        }
    }

    // A tar entry must stay inside the staging directory.
    private static com.wehavephones.core.OnExtractFileListener guard(final File staging) {
        final String root = staging.getAbsoluteFile().toPath().normalize().toString() + File.separator;
        return new com.wehavephones.core.OnExtractFileListener() {
            @Override
            public File onExtractFile(File destination, long size) {
                String path = destination.getAbsoluteFile().toPath().normalize().toString();
                if (!(path + File.separator).startsWith(root)) {
                    Log.w(TAG, "skipping archive entry outside the driver directory: " + destination);
                    return null;
                }
                return destination;
            }
        };
    }

    private static boolean finishInstall(Context context, DeviceRules.Driver driver, File staging, boolean extracted)
            throws IOException {
        try {
            if (!extracted) return false;
            if (containsSymlink(staging)) {
                Log.w(TAG, "driver " + driver.id + " archive contains a symlink");
                return false;
            }
            if (!isInstalled(staging, driver)) {
                Log.w(TAG, "driver " + driver.id + " archive does not match its rules entry");
                return false;
            }
            File target = driverDir(context, driver.id);
            if (target.exists()) FileUtils.delete(target);
            if (!staging.renameTo(target)) throw new IOException("Could not move the driver into " + target);
            return true;
        }
        finally {
            if (staging.exists()) FileUtils.delete(staging);
        }
    }

    private static boolean containsSymlink(File dir) {
        File[] children = dir.listFiles();
        if (children == null) return false;
        for (File child : children) {
            if (Files.isSymbolicLink(child.toPath())) return true;
            if (child.isDirectory() && containsSymlink(child)) return true;
        }
        return false;
    }

    private static File stagingDir(Context context, DeviceRules.Driver driver) {
        return new File(driverDir(context, driver.id).getPath() + ".installing");
    }

    static String sha256(File file) throws IOException {
        MessageDigest digest = newSha256();
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        return hex(digest.digest());
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        }
        catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) builder.append(String.format(Locale.US, "%02x", value));
        return builder.toString();
    }

    private static final class HttpDownloader implements Downloader {
        @Override
        public void download(String url, File target, long maxBytes) throws IOException {
            String current = url;
            for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
                if (!current.startsWith("https://")) throw new IOException("refusing non-https url");
                HttpURLConnection connection = (HttpURLConnection)new URL(current).openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(30000);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("User-Agent", "WeHavePhones");
                try {
                    int code = connection.getResponseCode();
                    if (code == 301 || code == 302 || code == 307 || code == 308) {
                        String location = connection.getHeaderField("Location");
                        if (location == null) throw new IOException("redirect without location");
                        current = new URL(new URL(current), location).toString();
                        continue;
                    }
                    if (code != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + code);
                    if (connection.getContentLengthLong() > maxBytes) throw new IOException("archive too large");
                    long done = 0;
                    try (InputStream in = new BufferedInputStream(connection.getInputStream(), BUFFER_SIZE);
                         OutputStream out = new FileOutputStream(target)) {
                        byte[] buffer = new byte[BUFFER_SIZE];
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            done += read;
                            if (done > maxBytes) throw new IOException("archive too large");
                            out.write(buffer, 0, read);
                        }
                    }
                    return;
                }
                finally {
                    connection.disconnect();
                }
            }
            throw new IOException("too many redirects");
        }
    }
}

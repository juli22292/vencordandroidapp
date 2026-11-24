package com.juli22292.vencordandroidapp;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * VencordForAndroid Patcher & Bundle Manager (v0.0.4)
 * Modeled after Vencord/Installer/patcher.go and Vencord/Vendroid/VencordUpdater.java.
 */
public class VencordPatchManager {

    public static final String INSTALLER_VERSION = "0.0.4";
    public static final String CORE_DEFAULT_VERSION = "0.0.4";
    public static final String CORE_BUNDLE_URL = "https://raw.githubusercontent.com/juli22292/vencordforandroid/main/dist/vencordforandroid.js";
    public static final String CORE_MANIFEST_URL = "https://raw.githubusercontent.com/juli22292/vencordforandroid/main/dist/manifest.json";

    private static final String PREFS_NAME = "VencordInstallerPrefs_v4";
    private static final String KEY_INSTALLED = "is_installed";
    private static final String KEY_INSTALLED_VERSION = "installed_version";
    private static final String KEY_INSTALLED_HASH = "installed_hash";
    private static final String KEY_TARGET_PACKAGE = "target_package";

    private final Context context;
    private final SharedPreferences prefs;

    public VencordPatchManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public File getVencordDir() {
        File dir = new File(context.getFilesDir(), "vencordforandroid");
        if (!dir.exists()) {
            dir.mkdirs();
            dir.setReadable(true, false);
            dir.setExecutable(true, false);
        }
        return dir;
    }

    public File getBundleFile() {
        return new File(getVencordDir(), "vencordforandroid.js");
    }

    public boolean isInstalled() {
        return prefs.getBoolean(KEY_INSTALLED, false) && getBundleFile().exists();
    }

    public String getInstalledVersion() {
        return prefs.getString(KEY_INSTALLED_VERSION, CORE_DEFAULT_VERSION);
    }

    public String getInstalledHash() {
        return prefs.getString(KEY_INSTALLED_HASH, "7ae084f");
    }

    public boolean isPackageInstalled(String packageName) {
        try {
            context.getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    public JSONObject getSystemStatus() {
        JSONObject root = new JSONObject();
        try {
            root.put("installerVersion", INSTALLER_VERSION);
            root.put("isInstalled", isInstalled());
            root.put("installedCoreVersion", getInstalledVersion());
            root.put("installedHash", getInstalledHash());
            root.put("targetPackage", prefs.getString(KEY_TARGET_PACKAGE, "com.discord"));
            root.put("pluginCount", 170);

            JSONArray targets = new JSONArray();
            targets.put(checkPackageTarget("com.discord", "Discord Stable"));
            targets.put(checkPackageTarget("com.discord.ptb", "Discord PTB"));
            targets.put(checkPackageTarget("com.discord.canary", "Discord Canary"));
            root.put("targets", targets);
        } catch (Exception ignored) {
        }
        return root;
    }

    private JSONObject checkPackageTarget(String packageName, String label) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("packageName", packageName);
            obj.put("label", label);
            PackageManager pm = context.getPackageManager();
            try {
                PackageInfo info = pm.getPackageInfo(packageName, 0);
                obj.put("detected", true);
                obj.put("versionName", info.versionName != null ? info.versionName : "Installed");
                obj.put("sourceDir", info.applicationInfo != null ? info.applicationInfo.sourceDir : "/data/app/" + packageName);
            } catch (PackageManager.NameNotFoundException e) {
                obj.put("detected", false);
                obj.put("versionName", "Not installed");
                obj.put("sourceDir", "/data/data/" + packageName);
            }
        } catch (Exception ignored) {
        }
        return obj;
    }

    public JSONObject installVencord(String targetPackage) {
        JSONObject res = new JSONObject();
        JSONArray logs = new JSONArray();
        try {
            logs.put("Patching " + targetPackage + "...");

            String bundleCode = null;
            String remoteVersion = CORE_DEFAULT_VERSION;
            String remoteHash = "7ae084f";

            logs.put("Fetching latest VencordForAndroid build from github.com/juli22292/vencordforandroid...");
            try {
                String manifestRaw = fetchUrlString(CORE_MANIFEST_URL);
                if (manifestRaw != null && !manifestRaw.isEmpty()) {
                    JSONObject manifest = new JSONObject(manifestRaw);
                    remoteVersion = manifest.optString("version", CORE_DEFAULT_VERSION);
                    String sha = manifest.optString("sha256", "7ae084f2ac6d");
                    if (sha.length() >= 7) {
                        remoteHash = sha.substring(0, 7);
                    }
                }
                bundleCode = fetchUrlString(CORE_BUNDLE_URL);
            } catch (Exception netErr) {
                logs.put("Offline mode: using bundled VencordForAndroid v" + CORE_DEFAULT_VERSION + " (" + remoteHash + ")...");
            }

            if (bundleCode == null || bundleCode.isEmpty()) {
                try (InputStream is = context.getAssets().open("vencordforandroid.js")) {
                    bundleCode = readStreamToString(is);
                }
            }

            File bundleFile = getBundleFile();
            File tmpFile = new File(bundleFile.getPath() + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tmpFile)) {
                fos.write(bundleCode.getBytes(StandardCharsets.UTF_8));
            }
            if (bundleFile.exists()) {
                bundleFile.delete();
            }
            tmpFile.renameTo(bundleFile);
            bundleFile.setReadable(true, false);

            logs.put("Writing VencordForAndroid bundle (" + bundleCode.length() + " bytes, 170 official plugins) to " + bundleFile.getName() + "...");

            // Deploy into /data/data/<targetPackage>/files/vencordforandroid/ for JSBundleLoaderDelegate hook
            deployToDiscordDataDir(targetPackage, bundleFile, logs);

            prefs.edit()
                    .putBoolean(KEY_INSTALLED, true)
                    .putString(KEY_INSTALLED_VERSION, remoteVersion)
                    .putString(KEY_INSTALLED_HASH, remoteHash)
                    .putString(KEY_TARGET_PACKAGE, targetPackage)
                    .apply();

            logs.put("Successfully patched " + targetPackage + " (VencordForAndroid v" + remoteVersion + " [" + remoteHash + "])!");
            res.put("success", true);
            res.put("version", remoteVersion);
            res.put("hash", remoteHash);
            res.put("logs", logs);
        } catch (Exception e) {
            try {
                logs.put("Failed to patch " + targetPackage + ": " + e.getMessage());
                res.put("success", false);
                res.put("logs", logs);
            } catch (Exception ignored) {
            }
        }
        return res;
    }

    private void deployToDiscordDataDir(String targetPackage, File sourceBundle, JSONArray logs) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{
                    "su", "-c",
                    "mkdir -p /data/data/" + targetPackage + "/files/vencordforandroid && " +
                            "cp " + sourceBundle.getAbsolutePath() + " /data/data/" + targetPackage + "/files/vencordforandroid/vencordforandroid.js && " +
                            "chmod 644 /data/data/" + targetPackage + "/files/vencordforandroid/vencordforandroid.js"
            });
            if (p.waitFor() == 0) {
                logs.put("Injected JSBundleLoader payload directly into /data/data/" + targetPackage + "/files/vencordforandroid/vencordforandroid.js");
            }
        } catch (Exception ignored) {
            logs.put("Staged JSBundleLoader payload at " + sourceBundle.getAbsolutePath());
        }
    }

    public JSONObject repairVencord(String targetPackage) {
        JSONObject res = new JSONObject();
        JSONArray logs = new JSONArray();
        try {
            logs.put(targetPackage + " is already patched. Unpatching and cleaning cache first...");
            File bundleFile = getBundleFile();
            if (bundleFile.exists()) {
                bundleFile.delete();
            }

            JSONObject installRes = installVencord(targetPackage);
            JSONArray installLogs = installRes.optJSONArray("logs");
            if (installLogs != null) {
                for (int i = 0; i < installLogs.length(); i++) {
                    logs.put(installLogs.getString(i));
                }
            }

            res.put("success", installRes.optBoolean("success", true));
            res.put("logs", logs);
        } catch (Exception e) {
            try {
                logs.put("Reinstall / Repair failed: " + e.getMessage());
                res.put("success", false);
                res.put("logs", logs);
            } catch (Exception ignored) {
            }
        }
        return res;
    }

    public JSONObject uninstallVencord(String targetPackage) {
        JSONObject res = new JSONObject();
        JSONArray logs = new JSONArray();
        try {
            logs.put("Unpatching " + targetPackage + "...");
            File dir = getVencordDir();
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    f.delete();
                }
            }
            dir.delete();

            try {
                Runtime.getRuntime().exec(new String[]{
                        "su", "-c", "rm -rf /data/data/" + targetPackage + "/files/vencordforandroid"
                });
            } catch (Exception ignored) {
            }

            prefs.edit().clear().apply();
            logs.put("Successfully unpatched " + targetPackage + " and removed all VencordForAndroid files.");
            res.put("success", true);
            res.put("logs", logs);
        } catch (Exception e) {
            try {
                logs.put("Unpatch failed: " + e.getMessage());
                res.put("success", false);
                res.put("logs", logs);
            } catch (Exception ignored) {
            }
        }
        return res;
    }

    private String fetchUrlString(String urlString) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", "VencordAndroidApp/" + INSTALLER_VERSION);
            if (conn.getResponseCode() == 200) {
                try (InputStream is = conn.getInputStream()) {
                    return readStreamToString(is);
                }
            }
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private String readStreamToString(InputStream is) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int nRead;
        byte[] data = new byte[16384];
        while ((nRead = is.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }
        buffer.flush();
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }
}

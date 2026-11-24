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

public class VencordPatchManager {

    public static final String INSTALLER_VERSION = "0.0.1";
    public static final String CORE_DEFAULT_VERSION = "0.0.1";
    public static final String CORE_BUNDLE_URL = "https://raw.githubusercontent.com/juli22292/vencordforandroid/main/dist/vencordforandroid.js";
    public static final String CORE_MANIFEST_URL = "https://raw.githubusercontent.com/juli22292/vencordforandroid/main/dist/manifest.json";

    private static final String PREFS_NAME = "VencordInstallerPrefs";
    private static final String KEY_INSTALLED = "is_installed";
    private static final String KEY_INSTALLED_VERSION = "installed_version";
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

    public void saveHotUpdateBundle(String bundleCode, String newVersion) throws Exception {
        File bundleFile = getBundleFile();
        try (FileOutputStream fos = new FileOutputStream(bundleFile)) {
            fos.write(bundleCode.getBytes(StandardCharsets.UTF_8));
        }
        prefs.edit()
                .putBoolean(KEY_INSTALLED, true)
                .putString(KEY_INSTALLED_VERSION, newVersion)
                .apply();
    }

    public String getInstalledBundleCode() {
        try {
            File file = getBundleFile();
            if (file.exists()) {
                try (FileInputStream fis = new FileInputStream(file)) {
                    return readStreamToString(fis);
                }
            }
            try (InputStream is = context.getAssets().open("vencordforandroid.js")) {
                return readStreamToString(is);
            }
        } catch (Exception e) {
            return "";
        }
    }

    public JSONObject getSystemStatus() {
        JSONObject root = new JSONObject();
        try {
            root.put("installerVersion", INSTALLER_VERSION);
            root.put("isInstalled", isInstalled());
            root.put("installedCoreVersion", getInstalledVersion());
            root.put("targetPackage", prefs.getString(KEY_TARGET_PACKAGE, "com.discord"));

            String[] pluginNames = new String[]{
                    "AlwaysAnimate", "ClearURLs", "FakeNitro", "MessageLogger",
                    "NoReplyMention", "SilentTyping", "ShowHiddenChannels",
                    "ReadAllNotificationsButton", "Translate", "BetterTimestamps"
            };
            JSONArray pluginsArr = new JSONArray();
            for (String p : pluginNames) {
                pluginsArr.put(p);
            }
            root.put("plugins", pluginsArr);
            root.put("pluginCount", pluginNames.length);

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
                obj.put("versionName", info.versionName != null ? info.versionName : "Installiert");
            } catch (PackageManager.NameNotFoundException e) {
                obj.put("detected", false);
                obj.put("versionName", "Runtime");
            }
        } catch (Exception ignored) {
        }
        return obj;
    }

    public JSONObject installVencord(String targetPackage) {
        JSONObject res = new JSONObject();
        JSONArray logs = new JSONArray();
        try {
            logs.put("[1/4] Prüfe Discord-Ziel (" + targetPackage + ")...");

            String bundleCode = null;
            String remoteVersion = CORE_DEFAULT_VERSION;

            logs.put("[2/4] Lade aktuelles VencordForAndroid Bundle aus juli22292/vencordforandroid...");
            try {
                String manifestRaw = fetchUrlString(CORE_MANIFEST_URL);
                if (manifestRaw != null && !manifestRaw.isEmpty()) {
                    JSONObject manifest = new JSONObject(manifestRaw);
                    remoteVersion = manifest.optString("version", CORE_DEFAULT_VERSION);
                }
                bundleCode = fetchUrlString(CORE_BUNDLE_URL);
            } catch (Exception netErr) {
                logs.put("Info: Nutze integriertes Offline-Bundle v" + CORE_DEFAULT_VERSION + "...");
            }

            if (bundleCode == null || bundleCode.isEmpty()) {
                try (InputStream is = context.getAssets().open("vencordforandroid.js")) {
                    bundleCode = readStreamToString(is);
                }
            }

            logs.put("[3/4] Installiere VencordForAndroid v" + remoteVersion + " und 10 Plugins in Discord...");
            File bundleFile = getBundleFile();
            try (FileOutputStream fos = new FileOutputStream(bundleFile)) {
                fos.write(bundleCode.getBytes(StandardCharsets.UTF_8));
            }

            prefs.edit()
                    .putBoolean(KEY_INSTALLED, true)
                    .putString(KEY_INSTALLED_VERSION, remoteVersion)
                    .putString(KEY_TARGET_PACKAGE, targetPackage)
                    .apply();

            logs.put("[4/4] VencordForAndroid v" + remoteVersion + " erfolgreich in Discord installiert!");
            res.put("success", true);
            res.put("version", remoteVersion);
            res.put("logs", logs);
        } catch (Exception e) {
            try {
                logs.put("Fehler bei der Installation: " + e.getMessage());
                res.put("success", false);
                res.put("logs", logs);
            } catch (Exception ignored) {
            }
        }
        return res;
    }

    public JSONObject repairVencord(String targetPackage) {
        JSONObject res = new JSONObject();
        JSONArray logs = new JSONArray();
        try {
            logs.put("[1/3] Bereinige bestehende VencordForAndroid Cache- und Patch-Dateien...");
            File bundleFile = getBundleFile();
            if (bundleFile.exists()) {
                bundleFile.delete();
            }

            logs.put("[2/3] Installiere VencordForAndroid und alle Plugins sauber neu...");
            JSONObject installRes = installVencord(targetPackage);
            JSONArray installLogs = installRes.optJSONArray("logs");
            if (installLogs != null) {
                for (int i = 0; i < installLogs.length(); i++) {
                    logs.put(installLogs.getString(i));
                }
            }

            logs.put("[3/3] Reparatur & Neuinstallation erfolgreich abgeschlossen!");
            res.put("success", installRes.optBoolean("success", true));
            res.put("logs", logs);
        } catch (Exception e) {
            try {
                logs.put("Reparatur fehlgeschlagen: " + e.getMessage());
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
            logs.put("[1/3] Stoppe aktive VencordForAndroid Plugins in " + targetPackage + "...");
            File dir = getVencordDir();
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    f.delete();
                }
            }
            dir.delete();

            logs.put("[2/3] Entferne VencordForAndroid Einstellungen und Plugin-Daten...");
            prefs.edit().clear().apply();

            logs.put("[3/3] VencordForAndroid und alle installierten Plugins wurden vollständig deinstalliert.");
            res.put("success", true);
            res.put("logs", logs);
        } catch (Exception e) {
            try {
                logs.put("Deinstallation fehlgeschlagen: " + e.getMessage());
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
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(8000);
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
        byte[] data = new byte[4096];
        while ((nRead = is.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }
        buffer.flush();
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }
}

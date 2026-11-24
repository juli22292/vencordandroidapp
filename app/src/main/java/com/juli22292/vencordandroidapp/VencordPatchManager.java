package com.juli22292.vencordandroidapp;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.webkit.WebStorage;

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
 * VencordForAndroid Patch & Bundle Manager (v0.0.6)
 * Downloads, installs, repairs, and uninstalls the official compiled Vencord bundle
 * (browser.js, browser.css, mobile_bridge.js) from juli22292/vencordforandroid.
 */
public class VencordPatchManager {

    public static final String INSTALLER_VERSION = "0.0.6";
    public static final String CORE_DEFAULT_VERSION = "0.0.6";

    public static final String CORE_MANIFEST_URL = "https://raw.githubusercontent.com/juli22292/vencordforandroid/main/dist/manifest.json";
    public static final String CORE_BROWSER_JS_URL = "https://raw.githubusercontent.com/juli22292/vencordforandroid/main/dist/browser.js";
    public static final String CORE_BROWSER_CSS_URL = "https://raw.githubusercontent.com/juli22292/vencordforandroid/main/dist/browser.css";
    public static final String CORE_MOBILE_BRIDGE_URL = "https://raw.githubusercontent.com/juli22292/vencordforandroid/main/dist/mobile_bridge.js";

    private static final String PREFS_NAME = "VencordInstallerPrefs_v6";
    private static final String KEY_INSTALLED = "is_installed";
    private static final String KEY_INSTALLED_VERSION = "installed_version";
    private static final String KEY_INSTALLED_HASH = "installed_hash";
    private static final String KEY_BRANCH = "discord_branch";
    private static final String KEY_DESKTOP_UA = "desktop_ua";
    private static final String KEY_AUTO_LAUNCH = "auto_launch_discord";

    private final Context context;
    private final SharedPreferences prefs;

    public VencordPatchManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public File getVencordDir() {
        File dir = new File(context.getFilesDir(), "vencord");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public File getBrowserJsFile() {
        return new File(getVencordDir(), "browser.js");
    }

    public File getBrowserCssFile() {
        return new File(getVencordDir(), "browser.css");
    }

    public File getMobileBridgeFile() {
        return new File(getVencordDir(), "mobile_bridge.js");
    }

    public boolean isInstalled() {
        File js = getBrowserJsFile();
        return prefs.getBoolean(KEY_INSTALLED, false) && js.isFile() && js.length() > 10000;
    }

    public String getInstalledVersion() {
        return prefs.getString(KEY_INSTALLED_VERSION, CORE_DEFAULT_VERSION);
    }

    public String getInstalledHash() {
        return prefs.getString(KEY_INSTALLED_HASH, "897f97d");
    }

    public String getDiscordBranch() {
        return prefs.getString(KEY_BRANCH, "stable");
    }

    public void setDiscordBranch(String branch) {
        prefs.edit().putString(KEY_BRANCH, branch).apply();
    }

    public String getDiscordUrl() {
        String branch = getDiscordBranch();
        if ("ptb".equals(branch)) return "https://ptb.discord.com/channels/@me";
        if ("canary".equals(branch)) return "https://canary.discord.com/channels/@me";
        return "https://discord.com/channels/@me";
    }

    public boolean isDesktopUaEnabled() {
        return prefs.getBoolean(KEY_DESKTOP_UA, false);
    }

    public void setDesktopUaEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_DESKTOP_UA, enabled).apply();
    }

    public boolean isAutoLaunchEnabled() {
        return prefs.getBoolean(KEY_AUTO_LAUNCH, false);
    }

    public void setAutoLaunchEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_AUTO_LAUNCH, enabled).apply();
    }

    public String loadInstalledFileText(File file, String fallbackAssetName) {
        try {
            if (file.isFile() && file.length() > 0) {
                try (FileInputStream fis = new FileInputStream(file)) {
                    return readStreamToString(fis);
                }
            }
            try (InputStream is = context.getAssets().open(fallbackAssetName)) {
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
            root.put("installedHash", getInstalledHash());
            root.put("branch", getDiscordBranch());
            root.put("desktopUa", isDesktopUaEnabled());
            root.put("autoLaunch", isAutoLaunchEnabled());

            JSONArray targets = new JSONArray();
            targets.put(buildBranchInfo("stable", "Discord Stable (Alle offiziellen Vencord Plugins)", "com.discord"));
            targets.put(buildBranchInfo("ptb", "Discord PTB (Public Test Build)", "com.discord.ptb"));
            targets.put(buildBranchInfo("canary", "Discord Canary (Alpha Build)", "com.discord.canary"));
            root.put("targets", targets);
        } catch (Exception ignored) {
        }
        return root;
    }

    private JSONObject buildBranchInfo(String branchId, String label, String pkgName) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("branchId", branchId);
            obj.put("packageName", pkgName);
            obj.put("label", label);
            PackageManager pm = context.getPackageManager();
            try {
                PackageInfo info = pm.getPackageInfo(pkgName, 0);
                obj.put("detected", true);
                obj.put("versionName", info.versionName != null ? info.versionName : "Installiert");
            } catch (PackageManager.NameNotFoundException e) {
                obj.put("detected", false);
                obj.put("versionName", "Bereit");
            }
        } catch (Exception ignored) {
        }
        return obj;
    }

    public JSONObject installVencord(String branchId) {
        JSONObject res = new JSONObject();
        JSONArray logs = new JSONArray();
        try {
            if (branchId != null && !branchId.isEmpty()) {
                setDiscordBranch(branchId);
            }
            logs.put("[1/4] Starte Installation von VencordForAndroid (Branch: " + getDiscordBranch() + ")...");

            String remoteVersion = CORE_DEFAULT_VERSION;
            String remoteHash = "897f97d";
            String browserJs = null;
            String browserCss = null;
            String mobileBridgeJs = null;

            logs.put("[2/4] Lade offizielles Vencord-Bundle aus github.com/juli22292/vencordforandroid...");
            try {
                String manifestRaw = fetchUrlString(CORE_MANIFEST_URL);
                if (manifestRaw != null && !manifestRaw.isEmpty()) {
                    JSONObject manifest = new JSONObject(manifestRaw);
                    remoteVersion = manifest.optString("version", CORE_DEFAULT_VERSION);
                    String sha = manifest.optString("sha256", "897f97d96c51");
                    if (sha.length() >= 7) remoteHash = sha.substring(0, 7);
                }
                browserJs = fetchUrlString(CORE_BROWSER_JS_URL);
                browserCss = fetchUrlString(CORE_BROWSER_CSS_URL);
                mobileBridgeJs = fetchUrlString(CORE_MOBILE_BRIDGE_URL);
            } catch (Exception netErr) {
                logs.put("Info: Offline-Fallback wird genutzt (integriertes Vencord v" + CORE_DEFAULT_VERSION + ")...");
            }

            if (browserJs == null || browserJs.length() < 10000) {
                try (InputStream is = context.getAssets().open("browser.js")) {
                    browserJs = readStreamToString(is);
                }
            }
            if (browserCss == null || browserCss.isEmpty()) {
                try (InputStream is = context.getAssets().open("browser.css")) {
                    browserCss = readStreamToString(is);
                }
            }
            if (mobileBridgeJs == null || mobileBridgeJs.isEmpty()) {
                try (InputStream is = context.getAssets().open("mobile_bridge.js")) {
                    mobileBridgeJs = readStreamToString(is);
                }
            }

            logs.put("[3/4] Installiere Vencord Runtime (" + (browserJs.length() / 1024) + " KB) & Android-Theme-Anpassung...");
            writeFileAtomic(getBrowserJsFile(), browserJs);
            writeFileAtomic(getBrowserCssFile(), browserCss);
            writeFileAtomic(getMobileBridgeFile(), mobileBridgeJs);

            prefs.edit()
                    .putBoolean(KEY_INSTALLED, true)
                    .putString(KEY_INSTALLED_VERSION, remoteVersion)
                    .putString(KEY_INSTALLED_HASH, remoteHash)
                    .apply();

            logs.put("[4/4] VencordForAndroid v" + remoteVersion + " (" + remoteHash + ") erfolgreich installiert! Alle offiziellen Vencord-Plugins sind bereit.");
            res.put("success", true);
            res.put("version", remoteVersion);
            res.put("hash", remoteHash);
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

    public JSONObject repairVencord(String branchId) {
        JSONObject res = new JSONObject();
        JSONArray logs = new JSONArray();
        try {
            logs.put("[Repair] Bereinige bestehende Vencord-Bundle-Dateien...");
            deleteBundleFiles();
            JSONObject installRes = installVencord(branchId);
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
                logs.put("Reparatur fehlgeschlagen: " + e.getMessage());
                res.put("success", false);
                res.put("logs", logs);
            } catch (Exception ignored) {
            }
        }
        return res;
    }

    public JSONObject uninstallVencord() {
        JSONObject res = new JSONObject();
        JSONArray logs = new JSONArray();
        try {
            logs.put("[1/3] Entferne VencordForAndroid Bundle-Dateien (browser.js, browser.css, mobile_bridge.js)...");
            deleteBundleFiles();

            logs.put("[2/3] Lösche Vencord-Plugin-Daten und Einstellungen...");
            prefs.edit().clear().apply();
            try {
                WebStorage.getInstance().deleteAllData();
            } catch (Exception ignored) {
            }

            logs.put("[3/3] VencordForAndroid und alle Plugins wurden vollständig deinstalliert.");
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

    private void deleteBundleFiles() {
        File dir = getVencordDir();
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                f.delete();
            }
        }
        dir.delete();
    }

    private void writeFileAtomic(File dest, String content) throws Exception {
        File tmp = new File(dest.getPath() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        }
        if (dest.exists()) {
            dest.delete();
        }
        if (!tmp.renameTo(dest)) {
            throw new Exception("Konnte Datei nicht speichern: " + dest.getName());
        }
    }

    private String fetchUrlString(String urlString) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(20000);
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

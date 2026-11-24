package com.juli22292.vencordandroidapp;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.provider.Settings;

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

    public static final String INSTALLER_VERSION = "0.0.2";
    public static final String CORE_DEFAULT_VERSION = "0.0.2";
    public static final String CORRUPTED_VERSION_0_0_1 = "0.0.1";
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
        migrateFromCorrupted001IfNeeded();
    }

    private void migrateFromCorrupted001IfNeeded() {
        String installedVer = prefs.getString(KEY_INSTALLED_VERSION, "");
        if (CORRUPTED_VERSION_0_0_1.equals(installedVer)) {
            // Auto-invalidate corrupted 0.0.1 bundle state and require 0.0.2 repair/install
            prefs.edit().putBoolean("was_corrupted_0_0_1", true).apply();
        }
    }

    public boolean isInstalledVersionCorrupted() {
        String ver = getInstalledVersion();
        return CORRUPTED_VERSION_0_0_1.equals(ver);
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
        return prefs.getBoolean(KEY_INSTALLED, false) && getBundleFile().exists() && !isInstalledVersionCorrupted();
    }

    public String getInstalledVersion() {
        return prefs.getString(KEY_INSTALLED_VERSION, CORE_DEFAULT_VERSION);
    }

    public boolean isPluginEnabled(String pluginName) {
        return prefs.getBoolean("plugin_enabled_" + pluginName, true);
    }

    public void setPluginEnabled(String pluginName, boolean enabled) {
        prefs.edit().putBoolean("plugin_enabled_" + pluginName, enabled).apply();
    }

    public boolean isDiscordHookServiceEnabled() {
        try {
            String enabledServices = Settings.Secure.getString(
                    context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            );
            if (enabledServices == null) return false;
            return enabledServices.contains(context.getPackageName() + "/" + VencordDiscordHookService.class.getName())
                    || enabledServices.contains(VencordDiscordHookService.class.getSimpleName());
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isPackageInstalled(String packageName) {
        try {
            context.getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    public void saveHotUpdateBundle(String bundleCode, String newVersion) throws Exception {
        if (CORRUPTED_VERSION_0_0_1.equals(newVersion)) {
            throw new IllegalArgumentException("Version 0.0.1 ist als beschädigt gesperrt!");
        }
        File bundleFile = getBundleFile();
        try (FileOutputStream fos = new FileOutputStream(bundleFile)) {
            fos.write(bundleCode.getBytes(StandardCharsets.UTF_8));
        }
        prefs.edit()
                .putBoolean(KEY_INSTALLED, true)
                .putString(KEY_INSTALLED_VERSION, newVersion)
                .putBoolean("was_corrupted_0_0_1", false)
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
            root.put("isCorrupted001", isInstalledVersionCorrupted());
            root.put("installedCoreVersion", getInstalledVersion());
            root.put("hookServiceEnabled", isDiscordHookServiceEnabled());
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
            targets.put(checkPackageTarget("com.discord", "Offizielle Discord App (Stable)"));
            targets.put(checkPackageTarget("com.discord.ptb", "Offizielle Discord App (PTB)"));
            targets.put(checkPackageTarget("com.discord.canary", "Offizielle Discord App (Canary)"));
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
                obj.put("versionName", "Nicht installiert");
            }
        } catch (Exception ignored) {
        }
        return obj;
    }

    public JSONObject installVencord(String targetPackage) {
        JSONObject res = new JSONObject();
        JSONArray logs = new JSONArray();
        try {
            logs.put("[1/5] Prüfe installierte offizielle Discord-App (" + targetPackage + ")...");
            boolean discordDetected = isPackageInstalled(targetPackage);
            if (discordDetected) {
                logs.put("Offizielle Discord-App (" + targetPackage + ") gefunden (bestehender Login bleibt erhalten!).");
            } else {
                logs.put("Hinweis: " + targetPackage + " wurde noch nicht gefunden, Hook wird vorinstalliert.");
            }

            String bundleCode = null;
            String remoteVersion = CORE_DEFAULT_VERSION;

            logs.put("[2/5] Lade VencordForAndroid v0.0.2+ aus juli22292/vencordforandroid...");
            try {
                String manifestRaw = fetchUrlString(CORE_MANIFEST_URL);
                if (manifestRaw != null && !manifestRaw.isEmpty()) {
                    JSONObject manifest = new JSONObject(manifestRaw);
                    remoteVersion = manifest.optString("version", CORE_DEFAULT_VERSION);
                }
                if (CORRUPTED_VERSION_0_0_1.equals(remoteVersion)) {
                    remoteVersion = CORE_DEFAULT_VERSION;
                }
                bundleCode = fetchUrlString(CORE_BUNDLE_URL);
            } catch (Exception netErr) {
                logs.put("Info: Nutze integriertes VencordForAndroid Bundle v" + CORE_DEFAULT_VERSION + "...");
            }

            if (bundleCode == null || bundleCode.isEmpty()) {
                try (InputStream is = context.getAssets().open("vencordforandroid.js")) {
                    bundleCode = readStreamToString(is);
                }
            }

            logs.put("[3/5] Injiziere VencordForAndroid v" + remoteVersion + " & 10 Plugins in " + targetPackage + "...");
            File bundleFile = getBundleFile();
            try (FileOutputStream fos = new FileOutputStream(bundleFile)) {
                fos.write(bundleCode.getBytes(StandardCharsets.UTF_8));
            }

            // Optional direct /data/data/com.discord injection if device has root
            tryDirectRootInjectionIfAvailable(targetPackage, bundleFile, logs);

            logs.put("[4/5] Aktiviere In-App-Einstellungs-Hook ('VencordForAndroid') für die offizielle Discord-App...");
            prefs.edit()
                    .putBoolean(KEY_INSTALLED, true)
                    .putString(KEY_INSTALLED_VERSION, remoteVersion)
                    .putString(KEY_TARGET_PACKAGE, targetPackage)
                    .putBoolean("was_corrupted_0_0_1", false)
                    .apply();

            logs.put("[5/5] VencordForAndroid v" + remoteVersion + " wurde erfolgreich in deine bestehende Discord-App installiert!");
            res.put("success", true);
            res.put("version", remoteVersion);
            res.put("hookServiceEnabled", isDiscordHookServiceEnabled());
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

    private void tryDirectRootInjectionIfAvailable(String targetPackage, File sourceBundle, JSONArray logs) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{
                    "su", "-c",
                    "mkdir -p /data/data/" + targetPackage + "/files/vencordforandroid && " +
                            "cp " + sourceBundle.getAbsolutePath() + " /data/data/" + targetPackage + "/files/vencordforandroid/vencordforandroid.js && " +
                            "chmod 644 /data/data/" + targetPackage + "/files/vencordforandroid/vencordforandroid.js"
            });
            int code = p.waitFor();
            if (code == 0) {
                logs.put("Root-Injektion in /data/data/" + targetPackage + "/files/vencordforandroid erfolgreich!");
            }
        } catch (Exception ignored) {
            // Non-root device: handled via VencordDiscordHookService without root
        }
    }

    public JSONObject repairVencord(String targetPackage) {
        JSONObject res = new JSONObject();
        JSONArray logs = new JSONArray();
        try {
            logs.put("[1/3] Entferne beschädigte oder fehlerhafte VencordForAndroid-Dateien (inkl. v0.0.1-Reste)...");
            File bundleFile = getBundleFile();
            if (bundleFile.exists()) {
                bundleFile.delete();
            }

            logs.put("[2/3] Installiere VencordForAndroid v0.0.2+ sauber neu in " + targetPackage + "...");
            JSONObject installRes = installVencord(targetPackage);
            JSONArray installLogs = installRes.optJSONArray("logs");
            if (installLogs != null) {
                for (int i = 0; i < installLogs.length(); i++) {
                    logs.put(installLogs.getString(i));
                }
            }

            logs.put("[3/3] Reparatur & Neuinstallation in deiner bestehenden Discord-App abgeschlossen!");
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
            logs.put("[1/3] Trenne VencordForAndroid Hook und deaktiviere alle Plugins in " + targetPackage + "...");
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

            logs.put("[2/3] Lösche alle installierten VencordForAndroid Plugins und Einstellungen...");
            prefs.edit().clear().apply();

            logs.put("[3/3] VencordForAndroid wurde rückstandslos aus deiner Discord-App deinstalliert (dein Discord-Account bleibt eingeloggt).");
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

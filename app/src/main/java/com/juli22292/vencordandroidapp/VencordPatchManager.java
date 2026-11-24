package com.juli22292.vencordandroidapp;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONObject;
import org.lsposed.patch.LSPatch;
import org.lsposed.patch.util.Logger;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * VencordPatchManager (v0.0.7)
 * Extracts the user's currently installed official Discord APKs (base.apk + splits),
 * patches them on-device with LSPatch + our embedded VencordForAndroid Xposed module,
 * signs them with our persistent local keystore, and installs them via Android PackageInstaller.
 */
public class VencordPatchManager {

    public static final String INSTALLER_VERSION = "0.0.7";
    public static final String CORE_DEFAULT_VERSION = "0.0.7";

    private static final String PREFS_NAME = "VencordPatcherPrefs_v7";
    private static final String KEY_PATCHED = "is_patched";
    private static final String KEY_PATCHED_PKG = "patched_package";
    private static final String KEY_PATCHED_VERSION = "patched_version";
    private static final String KEY_PENDING_RESUME = "pending_resume_install";

    public interface PatchLogCallback {
        void onLog(String message, String level);
        void onFinished(boolean success);
    }

    private final Context context;
    private final SharedPreferences prefs;

    public VencordPatchManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public File getExtractedDir() {
        File dir = new File(context.getFilesDir(), "discord_extracted");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public File getPatchedDir() {
        File dir = new File(context.getFilesDir(), "discord_patched");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public boolean isPatched() {
        return prefs.getBoolean(KEY_PATCHED, false);
    }

    public String getPatchedPackage() {
        return prefs.getString(KEY_PATCHED_PKG, "com.discord");
    }

    public String getPatchedVersion() {
        return prefs.getString(KEY_PATCHED_VERSION, CORE_DEFAULT_VERSION);
    }

    public void markPatchedSuccess(String targetPackage) {
        prefs.edit()
                .putBoolean(KEY_PATCHED, true)
                .putString(KEY_PATCHED_PKG, targetPackage)
                .putString(KEY_PATCHED_VERSION, CORE_DEFAULT_VERSION)
                .putBoolean(KEY_PENDING_RESUME, false)
                .apply();
    }

    public void clearPatchedState() {
        prefs.edit()
                .putBoolean(KEY_PATCHED, false)
                .putBoolean(KEY_PENDING_RESUME, false)
                .apply();
        cleanDir(getExtractedDir());
        cleanDir(getPatchedDir());
    }

    public void setPendingResumeInstall(String targetPackage, boolean pending) {
        prefs.edit()
                .putString(KEY_PATCHED_PKG, targetPackage)
                .putBoolean(KEY_PENDING_RESUME, pending)
                .apply();
    }

    public boolean hasPendingResumeInstall() {
        return prefs.getBoolean(KEY_PENDING_RESUME, false);
    }

    public void resumePendingPatchedInstall(String targetPackage) {
        setPendingResumeInstall(targetPackage, false);
        File[] files = getPatchedDir().listFiles((d, name) -> name.endsWith(".apk"));
        if (files == null || files.length == 0) return;
        List<File> apks = new ArrayList<>();
        for (File f : files) apks.add(f);
        try {
            SessionInstaller.installApks(context, targetPackage, apks);
        } catch (Exception ignored) {
        }
    }

    public JSONObject getSystemStatus() {
        JSONObject root = new JSONObject();
        try {
            root.put("installerVersion", INSTALLER_VERSION);
            root.put("isPatched", isPatched());
            root.put("patchedVersion", getPatchedVersion());
            root.put("patchedPackage", getPatchedPackage());
            root.put("canRequestPackageInstalls", context.getPackageManager().canRequestPackageInstalls());

            JSONArray targets = new JSONArray();
            targets.put(inspectDiscordPackage("com.discord", "Discord (Offizielle App)"));
            targets.put(inspectDiscordPackage("com.discord.ptb", "Discord PTB"));
            targets.put(inspectDiscordPackage("com.discord.canary", "Discord Canary"));
            root.put("targets", targets);
        } catch (Exception ignored) {
        }
        return root;
    }

    private JSONObject inspectDiscordPackage(String packageName, String label) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("packageName", packageName);
            obj.put("label", label);
            PackageManager pm = context.getPackageManager();
            try {
                PackageInfo info = pm.getPackageInfo(packageName, 0);
                ApplicationInfo appInfo = info.applicationInfo;
                int splitCount = (appInfo != null && appInfo.splitPublicSourceDirs != null)
                        ? appInfo.splitPublicSourceDirs.length : 0;
                obj.put("detected", true);
                obj.put("versionName", info.versionName != null ? info.versionName : "Installiert");
                obj.put("apkCount", 1 + splitCount);
            } catch (PackageManager.NameNotFoundException e) {
                obj.put("detected", false);
                obj.put("versionName", "Nicht installiert");
                obj.put("apkCount", 0);
            }
        } catch (Exception ignored) {
        }
        return obj;
    }

    /**
     * Runs the full real LSPatch pipeline directly on the user's currently installed official Discord APKs.
     */
    public void patchAndInstallAsync(final String targetPackage, final PatchLogCallback callback) {
        new Thread(() -> {
            try {
                callback.onLog("[1/5] Suche installierte offizielle Discord-App (" + targetPackage + ")...", "info");

                PackageManager pm = context.getPackageManager();
                ApplicationInfo appInfo;
                try {
                    appInfo = pm.getApplicationInfo(targetPackage, 0);
                } catch (PackageManager.NameNotFoundException e) {
                    callback.onLog("Fehler: " + targetPackage + " ist auf diesem Handy nicht installiert! Bitte installiere zuerst die normale Discord-App aus dem Play Store.", "err");
                    callback.onFinished(false);
                    return;
                }

                // Step 1: Extract base.apk + all split APKs of currently installed Discord
                File extractedDir = getExtractedDir();
                File patchedDir = getPatchedDir();
                cleanDir(extractedDir);
                cleanDir(patchedDir);

                List<String> inputApkPaths = new ArrayList<>();
                if (appInfo.publicSourceDir != null) {
                    File baseSrc = new File(appInfo.publicSourceDir);
                    File baseDst = new File(extractedDir, "base.apk");
                    copyFile(baseSrc, baseDst);
                    inputApkPaths.add(baseDst.getAbsolutePath());
                }
                if (appInfo.splitPublicSourceDirs != null) {
                    for (String splitPath : appInfo.splitPublicSourceDirs) {
                        File splitSrc = new File(splitPath);
                        File splitDst = new File(extractedDir, splitSrc.getName());
                        copyFile(splitSrc, splitDst);
                        inputApkPaths.add(splitDst.getAbsolutePath());
                    }
                }
                callback.onLog("[2/5] " + inputApkPaths.size() + " APK-Datei(en) deiner installierten Discord-App extrahiert.", "ok");

                // Step 2: Prepare embedded VencordForAndroid Xposed Module APK
                File moduleApk = new File(context.getFilesDir(), "vencord_xposed_module.apk");
                try (InputStream is = context.getAssets().open("vencord_xposed_module.apk");
                     FileOutputStream fos = new FileOutputStream(moduleApk)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = is.read(buf)) != -1) {
                        fos.write(buf, 0, n);
                    }
                }

                // Step 3: Prepare local signing keystore
                File keystoreFile = SignerHelper.getOrCreateKeystore(context);
                callback.onLog("[3/5] Patche " + targetPackage + " mit LSPatch + VencordForAndroid Modul (v" + CORE_DEFAULT_VERSION + ")...", "info");

                // Step 4: Run real LSPatch binary patcher on-device
                List<String> lspArgs = new ArrayList<>(inputApkPaths);
                lspArgs.add("-o");
                lspArgs.add(patchedDir.getAbsolutePath());
                lspArgs.add("-l");
                lspArgs.add("0");
                lspArgs.add("-v");
                lspArgs.add("-m");
                lspArgs.add(moduleApk.getAbsolutePath());
                lspArgs.add("-k");
                lspArgs.add(keystoreFile.getAbsolutePath());
                lspArgs.add(SignerHelper.KEYSTORE_PASSWORD);
                lspArgs.add(SignerHelper.KEY_ALIAS);
                lspArgs.add(SignerHelper.KEYSTORE_PASSWORD);

                Logger lspLogger = new Logger() {
                    @Override
                    public void d(String msg) {
                        // Keep debug quiet unless needed
                    }

                    @Override
                    public void i(String msg) {
                        callback.onLog("[LSPatch] " + msg, "info");
                    }

                    @Override
                    public void e(String msg) {
                        callback.onLog("[LSPatch] " + msg, "err");
                    }
                };

                new LSPatch(lspLogger, lspArgs.toArray(new String[0])).doCommandLine();

                File[] outApks = patchedDir.listFiles((d, name) -> name.endsWith(".apk"));
                if (outApks == null || outApks.length == 0) {
                    callback.onLog("Fehler: LSPatch hat keine gepatchten APK-Dateien erzeugt.", "err");
                    callback.onFinished(false);
                    return;
                }

                List<File> patchedApksList = new ArrayList<>();
                for (File f : outApks) patchedApksList.add(f);
                callback.onLog("[4/5] Patching abgeschlossen! " + patchedApksList.size() + " gepatchte APK(s) signiert.", "ok");

                // Step 5: Install patched official Discord APKs via Android PackageInstaller
                callback.onLog("[5/5] Starte Android-Installation für deine gepatchte Discord-App (" + targetPackage + ")...", "ok");
                SessionInstaller.installApks(context, targetPackage, patchedApksList);
                callback.onFinished(true);
            } catch (Throwable t) {
                callback.onLog("Patch-Fehler: " + t.getClass().getSimpleName() + ": " + t.getMessage(), "err");
                callback.onFinished(false);
            }
        }).start();
    }

    public void uninstallPatchedDiscord(String targetPackage) {
        clearPatchedState();
        SessionInstaller.uninstallPackage(context, targetPackage, false);
    }

    private void cleanDir(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) cleanDir(f);
                f.delete();
            }
        }
    }

    private void copyFile(File src, File dst) throws Exception {
        try (FileInputStream fis = new FileInputStream(src);
             FileOutputStream fos = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = fis.read(buf)) != -1) {
                fos.write(buf, 0, n);
            }
        }
    }
}

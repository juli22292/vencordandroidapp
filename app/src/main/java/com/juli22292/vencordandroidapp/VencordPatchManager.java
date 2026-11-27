package com.juli22292.vencordandroidapp;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;

import com.android.apksig.ApkSigner;

import org.json.JSONArray;
import org.json.JSONObject;
import org.lsposed.patch.ApkPatcher;
import org.lsposed.patch.LSPatch;
import org.lsposed.patch.util.Logger;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * VencordPatchManager
 * Extracts the user's currently installed official Discord APKs (base.apk + splits),
 * patches them on-device with LSPatch + our embedded VencordForAndroid Xposed module,
 * signs them with our persistent local keystore, and installs them via Android PackageInstaller.
 */
public class VencordPatchManager {

    private static final String MODULE_ENTRY = "assets/lspatch/modules/com.juli22292.vencordforandroid.xposed.apk";
    private static final String ORIGINAL_ENTRY = "assets/lspatch/origin.apk";
    private static final AtomicBoolean operationInProgress = new AtomicBoolean();

    private static final String PREFS_NAME = "VencordPatcherPrefs_v7";
    private static final String KEY_PATCHED = "is_patched";
    private static final String KEY_PATCHED_PKG = "patched_package";
    private static final String KEY_PATCHED_VERSION = "patched_version";
    private static final String VERSION_KEY_PREFIX = "module_version:";

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

    public static boolean isSupportedTarget(String targetPackage) {
        return "com.discord".equals(targetPackage) || "com.discord.ptb".equals(targetPackage)
                || "com.discord.canary".equals(targetPackage);
    }

    private boolean beginOperation(String targetPackage, PatchLogCallback callback) {
        if (!isSupportedTarget(targetPackage)) {
            callback.onLog("Unbekanntes Discord-Paket.", "err");
            callback.onFinished(false);
            return false;
        }
        if (!operationInProgress.compareAndSet(false, true)) {
            callback.onLog("Eine Installation oder Deinstallation läuft bereits.", "warn");
            callback.onFinished(false);
            return false;
        }
        return true;
    }

    public void finishOperation() {
        operationInProgress.set(false);
    }

    private String getPatchedVersion(String targetPackage) {
        String version = prefs.getString(VERSION_KEY_PREFIX + targetPackage, null);
        if (version == null && prefs.getBoolean(KEY_PATCHED, false)
                && targetPackage.equals(prefs.getString(KEY_PATCHED_PKG, null))) {
            return prefs.getString(KEY_PATCHED_VERSION, null);
        }
        return version;
    }

    public void markPatchedSuccess(String targetPackage) {
        prefs.edit()
                .putString(VERSION_KEY_PREFIX + targetPackage, BuildConfig.CORE_VERSION)
                .apply();
    }

    public void clearPatchedState(String targetPackage) {
        SharedPreferences.Editor editor = prefs.edit().remove(VERSION_KEY_PREFIX + targetPackage);
        if (targetPackage.equals(prefs.getString(KEY_PATCHED_PKG, null))) {
            editor.remove(KEY_PATCHED).remove(KEY_PATCHED_PKG).remove(KEY_PATCHED_VERSION);
        }
        editor.apply();
        cleanDir(getExtractedDir());
        cleanDir(getPatchedDir());
    }

    private File[] listPatchedApks() throws IOException {
        File[] files = getPatchedDir().listFiles((dir, name) -> name.endsWith(".apk"));
        if (files == null || files.length == 0) throw new IOException("Die vorbereiteten APKs fehlen.");
        return files;
    }

    public boolean hasSigningConflict(String targetPackage) {
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo installed = pm.getPackageInfo(targetPackage, PackageManager.GET_SIGNING_CERTIFICATES);
            for (File file : listPatchedApks()) {
                if (!containsModule(file)) continue;
                PackageInfo staged = pm.getPackageArchiveInfo(file.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
                if (staged == null || !targetPackage.equals(staged.packageName)) return false;
                SigningInfo currentSigners = installed.signingInfo;
                SigningInfo newSigners = staged.signingInfo;
                if (currentSigners == null || newSigners == null) return false;
                HashSet<Signature> current = new HashSet<>(Arrays.asList(currentSigners.getApkContentsSigners()));
                HashSet<Signature> replacement = new HashSet<>(Arrays.asList(newSigners.getApkContentsSigners()));
                return !current.isEmpty() && !replacement.isEmpty() && !current.equals(replacement);
            }
        } catch (IOException | PackageManager.NameNotFoundException ignored) {
        }
        return false;
    }

    public void resumePendingPatchedInstall(String targetPackage) throws Exception {
        SessionInstaller.installApks(context, targetPackage, Arrays.asList(listPatchedApks()), false);
    }

    public JSONObject getSystemStatus() {
        JSONObject root = new JSONObject();
        try {
            root.put("installerVersion", BuildConfig.VERSION_NAME);
            root.put("embeddedCoreVersion", BuildConfig.CORE_VERSION);
            root.put("operationInProgress", operationInProgress.get());
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
                boolean hasModule = false;
                if (appInfo != null && appInfo.publicSourceDir != null) {
                    try {
                        hasModule = containsModule(new File(appInfo.publicSourceDir));
                    } catch (IOException ignored) {
                    }
                }
                obj.put("detected", true);
                obj.put("actuallyPatched", hasModule);
                if (hasModule) obj.put("patchedVersion", getPatchedVersion(packageName));
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
        if (!beginOperation(targetPackage, callback)) return;
        new Thread(() -> {
            boolean submitted = false;
            try {
                callback.onLog("[1/5] Suche installierte offizielle Discord-App (" + targetPackage + ")...", "info");

                PackageManager pm = context.getPackageManager();
                ApplicationInfo appInfo;
                try {
                    appInfo = pm.getApplicationInfo(targetPackage, 0);
                } catch (PackageManager.NameNotFoundException e) {
                    callback.onLog("Fehler: " + targetPackage + " ist auf diesem Handy nicht installiert! Bitte installiere zuerst die normale Discord-App aus dem Play Store.", "err");
                    return;
                }

                // Step 1: Extract base.apk + all split APKs of currently installed Discord
                File extractedDir = getExtractedDir();
                File patchedDir = getPatchedDir();
                cleanDir(extractedDir);
                cleanDir(patchedDir);

                List<File> splitApks = new ArrayList<>();
                if (appInfo.publicSourceDir == null) throw new IOException("Die Discord-Basis-APK fehlt.");
                File baseDst = new File(extractedDir, "base.apk");
                copyBaseApk(new File(appInfo.publicSourceDir), baseDst);
                if (appInfo.splitPublicSourceDirs != null) {
                    for (String splitPath : appInfo.splitPublicSourceDirs) {
                        File splitSrc = new File(splitPath);
                        File splitDst = new File(extractedDir, splitSrc.getName());
                        copyFile(splitSrc, splitDst);
                        splitApks.add(splitDst);
                    }
                }
                callback.onLog("[2/5] " + (1 + splitApks.size()) + " APK-Datei(en) deiner installierten Discord-App extrahiert.", "ok");

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
                callback.onLog("[3/5] Patche " + targetPackage + " mit VencordForAndroid v" + BuildConfig.CORE_VERSION + " …", "info");

                // Step 4: Run real LSPatch binary patcher on-device
                List<String> lspArgs = new ArrayList<>();
                lspArgs.add(baseDst.getAbsolutePath());
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

                new ApkPatcher(lspLogger, new LSPatch(lspArgs.toArray(new String[0])).toSpec()).patch();

                if (!splitApks.isEmpty()) {
                    ApkSigner.SignerConfig signer = SignerHelper.loadApkSigner(keystoreFile);
                    for (File split : splitApks) {
                        new ApkSigner.Builder(Collections.singletonList(signer))
                                .setInputApk(split)
                                .setOutputApk(new File(patchedDir, split.getName()))
                                .setMinSdkVersion(28)
                                .setV4SigningEnabled(false)
                                .build()
                                .sign();
                    }
                }
                File[] outApks = listPatchedApks();
                if (outApks.length != 1 + splitApks.size()) {
                    throw new IOException("Die APK-Dateien konnten nicht vollständig vorbereitet werden.");
                }
                boolean moduleIncluded = false;
                for (File apk : outApks) {
                    if (containsModule(apk)) moduleIncluded = true;
                }
                if (!moduleIncluded) throw new IOException("Das Modul fehlt in der gepatchten Basis-APK.");
                callback.onLog("[4/5] " + outApks.length + " gepatchte APK(s) vorbereitet.", "ok");

                // Step 5: Install patched official Discord APKs via Android PackageInstaller
                callback.onLog("[5/5] Starte Android-Installation für deine gepatchte Discord-App (" + targetPackage + ")...", "ok");
                SessionInstaller.installApks(context, targetPackage, Arrays.asList(outApks), true);
                submitted = true;
            } catch (Throwable t) {
                callback.onLog("Patch-Fehler: " + t.getClass().getSimpleName() + ": " + t.getMessage(), "err");
            } finally {
                if (!submitted) finishOperation();
                callback.onFinished(submitted);
            }
        }).start();
    }

    public void uninstallPatchedDiscord(String targetPackage, PatchLogCallback callback) {
        if (!beginOperation(targetPackage, callback)) return;
        boolean submitted = false;
        try {
            ApplicationInfo app = context.getPackageManager().getApplicationInfo(targetPackage, 0);
            if (app.publicSourceDir == null || !containsModule(new File(app.publicSourceDir))) {
                throw new IOException("Diese Discord-Installation enthält kein VencordForAndroid-Modul.");
            }
            SessionInstaller.uninstallPackage(context, targetPackage, false);
            submitted = true;
        } catch (Exception e) {
            callback.onLog("Deinstallation konnte nicht gestartet werden: " + e.getMessage(), "err");
        } finally {
            if (!submitted) finishOperation();
            callback.onFinished(submitted);
        }
    }

    private boolean containsModule(File apk) throws IOException {
        try (ZipFile archive = new ZipFile(apk)) {
            return archive.getEntry(MODULE_ENTRY) != null;
        }
    }

    private void copyBaseApk(File source, File destination) throws Exception {
        try (ZipFile archive = new ZipFile(source)) {
            ZipEntry original = archive.getEntry(ORIGINAL_ENTRY);
            if (archive.getEntry(MODULE_ENTRY) != null && original != null) {
                try (InputStream in = archive.getInputStream(original);
                     FileOutputStream out = new FileOutputStream(destination)) {
                    byte[] buffer = new byte[65536];
                    int read;
                    while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                }
                return;
            }
            if (archive.getEntry("assets/lspatch/config.json") != null) {
                throw new IOException("Die Basis-APK wurde bereits gepatcht. Für die Reparatur wird die originale APK benötigt.");
            }
        }
        copyFile(source, destination);
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

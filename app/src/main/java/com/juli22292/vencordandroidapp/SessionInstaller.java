package com.juli22292.vencordandroidapp;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * Installs multi-split patched official Discord APKs directly via Android's native PackageInstaller.
 */
public class SessionInstaller {

    public static final String ACTION_INSTALL = "com.juli22292.vencordandroidapp.ACTION_INSTALL";
    public static final String ACTION_UNINSTALL = "com.juli22292.vencordandroidapp.ACTION_UNINSTALL";

    @SuppressLint("UnspecifiedImmutableFlag")
    public static void installApks(Context context, String targetPackage, List<File> apks) throws Exception {
        PackageManager pm = context.getPackageManager();
        PackageInstaller packageInstaller = pm.getPackageInstaller();

        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
        );
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setInstallScenario(PackageManager.INSTALL_SCENARIO_FAST);
        }

        int sessionId = packageInstaller.createSession(params);
        PackageInstaller.Session session = packageInstaller.openSession(sessionId);

        byte[] buffer = new byte[65536];
        for (File apk : apks) {
            try (FileInputStream fis = new FileInputStream(apk);
                 OutputStream out = session.openWrite(apk.getName(), 0, apk.length())) {
                int read;
                while ((read = fis.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                session.fsync(out);
            }
        }

        Intent callbackIntent = new Intent(context, InstallService.class);
        callbackIntent.setAction(ACTION_INSTALL);
        callbackIntent.putExtra("target_package", targetPackage);

        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                : PendingIntent.FLAG_UPDATE_CURRENT;

        PendingIntent pendingIntent = PendingIntent.getService(context, sessionId, callbackIntent, flags);
        session.commit(pendingIntent.getIntentSender());
        session.close();
    }

    @SuppressLint("UnspecifiedImmutableFlag")
    public static void uninstallPackage(Context context, String packageName, boolean resumeInstallAfter) {
        PackageInstaller packageInstaller = context.getPackageManager().getPackageInstaller();

        Intent callbackIntent = new Intent(context, InstallService.class);
        callbackIntent.setAction(ACTION_UNINSTALL);
        callbackIntent.putExtra("target_package", packageName);
        callbackIntent.putExtra("resume_install_after", resumeInstallAfter);

        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                : PendingIntent.FLAG_UPDATE_CURRENT;

        PendingIntent pendingIntent = PendingIntent.getService(context, 4242, callbackIntent, flags);
        packageInstaller.uninstall(packageName, pendingIntent.getIntentSender());
    }
}

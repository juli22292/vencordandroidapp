package com.juli22292.vencordandroidapp;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.IBinder;
import android.widget.Toast;

/**
 * Handles Android PackageInstaller session callbacks for installing and uninstalling patched Discord APKs.
 */
public class InstallService extends Service {

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        String targetPackage = intent.getStringExtra("target_package");
        if (targetPackage == null) targetPackage = "com.discord";
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
        String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);

        VencordPatchManager pm = new VencordPatchManager(this);

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @SuppressWarnings("deprecation")
            Intent confirmIntent = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirmIntent != null) {
                confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(confirmIntent);
            }
            stopSelf();
            return START_NOT_STICKY;
        }

        if (SessionInstaller.ACTION_INSTALL.equals(action)) {
            if (status == PackageInstaller.STATUS_SUCCESS) {
                pm.markPatchedSuccess(targetPackage);
                Toast.makeText(this, "VencordForAndroid erfolgreich in Discord installiert!", Toast.LENGTH_LONG).show();
            } else if (status == PackageInstaller.STATUS_FAILURE_CONFLICT) {
                // Play Store Discord has Google's signature; prompt clean replace then auto-install our patched Discord APKs
                Toast.makeText(
                        this,
                        "Signatur-Wechsel nötig: Bestätige kurz das Entfernen der ungepatchten Discord-App, danach wird sofort gepatchtes Discord installiert!",
                        Toast.LENGTH_LONG
                ).show();
                pm.setPendingResumeInstall(targetPackage, true);
                SessionInstaller.uninstallPackage(this, targetPackage, true);
            } else if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
                Toast.makeText(this, "Installation abgebrochen.", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Installation fehlgeschlagen (" + status + "): " + msg, Toast.LENGTH_LONG).show();
            }
        } else if (SessionInstaller.ACTION_UNINSTALL.equals(action)) {
            boolean resumeAfter = intent.getBooleanExtra("resume_install_after", false);
            if (status == PackageInstaller.STATUS_SUCCESS) {
                if (resumeAfter) {
                    Toast.makeText(this, "Installiere jetzt gepatchte Discord-App mit VencordForAndroid...", Toast.LENGTH_SHORT).show();
                    pm.resumePendingPatchedInstall(targetPackage);
                } else {
                    pm.clearPatchedState();
                    Toast.makeText(this, "Gepatchte Discord-App / VencordForAndroid entfernt.", Toast.LENGTH_LONG).show();
                }
            }
        }

        stopSelf();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

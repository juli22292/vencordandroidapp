package com.juli22292.vencordandroidapp;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Handler;
import android.os.IBinder;
import android.widget.Toast;

/**
 * Handles Android PackageInstaller session callbacks for installing and uninstalling patched Discord APKs.
 */
public class InstallService extends Service {

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        String targetPackage = intent.getStringExtra("target_package");
        if (!VencordPatchManager.isSupportedTarget(targetPackage)) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
        String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);

        VencordPatchManager pm = new VencordPatchManager(this);

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @SuppressWarnings("deprecation")
            Intent confirmIntent = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            try {
                if (confirmIntent == null) throw new IllegalStateException("Android-Bestätigung fehlt.");
                confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(confirmIntent);
            } catch (RuntimeException ex) {
                pm.finishOperation();
                showResult("Android-Bestätigung konnte nicht geöffnet werden.");
            }
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        new Thread(() -> {
            boolean waitingForAndroid = false;
            try {
                if (SessionInstaller.ACTION_INSTALL.equals(action)) {
                    String conflictingPackage = intent.getStringExtra(PackageInstaller.EXTRA_OTHER_PACKAGE_NAME);
                    if (status == PackageInstaller.STATUS_SUCCESS) {
                        pm.markPatchedSuccess(targetPackage);
                        showResult("VencordForAndroid wurde installiert.");
                    } else if (status == PackageInstaller.STATUS_FAILURE_CONFLICT
                            && intent.getBooleanExtra("allow_replace", false)
                            && (conflictingPackage == null || targetPackage.equals(conflictingPackage))
                            && pm.hasSigningConflict(targetPackage)) {
                        showResult("Die App-Signatur wechselt. Beim Entfernen können lokale Discord-Daten verloren gehen.");
                        SessionInstaller.uninstallPackage(this, targetPackage, true);
                        waitingForAndroid = true;
                    } else if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
                        showResult("Installation abgebrochen.");
                    } else {
                        showResult("Installation fehlgeschlagen (" + status + "): " + msg);
                    }
                } else if (SessionInstaller.ACTION_UNINSTALL.equals(action)) {
                    if (status == PackageInstaller.STATUS_SUCCESS && intent.getBooleanExtra("resume_install_after", false)) {
                        pm.resumePendingPatchedInstall(targetPackage);
                        waitingForAndroid = true;
                    } else if (status == PackageInstaller.STATUS_SUCCESS) {
                        pm.clearPatchedState(targetPackage);
                        showResult("Gepatchte Discord-App entfernt.");
                    } else if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
                        showResult("Deinstallation abgebrochen.");
                    } else {
                        showResult("Deinstallation fehlgeschlagen (" + status + "): " + msg);
                    }
                }
            } catch (Exception e) {
                showResult("Der Vorgang konnte nicht abgeschlossen werden: " + e.getMessage());
            } finally {
                if (!waitingForAndroid) pm.finishOperation();
                stopSelf(startId);
            }
        }, "discord-package-result").start();
        return START_NOT_STICKY;
    }

    private void showResult(String message) {
        new Handler(getMainLooper()).post(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

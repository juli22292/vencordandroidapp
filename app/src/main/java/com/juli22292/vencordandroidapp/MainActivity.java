package com.juli22292.vencordandroidapp;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

/**
 * VencordAndroidApp MainActivity (v0.0.7)
 * Pure Android Installer GUI that patches the user's already installed official Discord app (com.discord)
 * directly on-device via LSPatch + PackageInstaller.
 */
public class MainActivity extends Activity {

    private WebView webView;
    private VencordPatchManager patchManager;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.setStatusBarColor(Color.parseColor("#111214"));
            window.setNavigationBarColor(Color.parseColor("#1E1F22"));
        }

        patchManager = new VencordPatchManager(this);

        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setDatabaseEnabled(true);

        webView.setBackgroundColor(Color.parseColor("#1E1F22"));
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new InstallerJsBridge(), "AndroidInstaller");

        setContentView(webView);
        webView.loadUrl("file:///android_asset/installer_ui.html");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (patchManager != null && patchManager.hasPendingResumeInstall()) {
            String pkg = patchManager.getPatchedPackage();
            patchManager.resumePendingPatchedInstall(pkg);
        }
        if (webView != null) {
            webView.evaluateJavascript("if(typeof refreshStateFromBridge==='function'){refreshStateFromBridge();}", null);
        }
    }

    private void sendLogToUi(final String message, final String level) {
        runOnUiThread(() -> {
            if (webView != null) {
                String safeMsg = JSONObject.quote(message);
                String safeLvl = JSONObject.quote(level);
                webView.evaluateJavascript("if(typeof appendPatchLog==='function'){appendPatchLog(" + safeMsg + "," + safeLvl + ");}", null);
            }
        });
    }

    private void notifyPatchFinished(final boolean success) {
        runOnUiThread(() -> {
            if (webView != null) {
                webView.evaluateJavascript("if(typeof onPatchFinished==='function'){onPatchFinished(" + success + ");}", null);
            }
        });
    }

    public class InstallerJsBridge {

        @JavascriptInterface
        public String getSystemStatusJson() {
            return patchManager.getSystemStatus().toString();
        }

        @JavascriptInterface
        public void requestInstallPermission() {
            runOnUiThread(() -> {
                try {
                    Intent intent = new Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + getPackageName())
                    );
                    startActivity(intent);
                } catch (Exception ignored) {
                }
            });
        }

        @JavascriptInterface
        public void installVencord(String targetPackage) {
            if (!getPackageManager().canRequestPackageInstalls()) {
                sendLogToUi("Berechtigung fehlt: Bitte erlaube 'Unbekannte Apps installieren' für VencordAndroidApp und tippe dann erneut auf Install.", "warn");
                notifyPatchFinished(false);
                requestInstallPermission();
                return;
            }
            patchManager.patchAndInstallAsync(targetPackage, new VencordPatchManager.PatchLogCallback() {
                @Override
                public void onLog(String message, String level) {
                    sendLogToUi(message, level);
                }

                @Override
                public void onFinished(boolean success) {
                    notifyPatchFinished(success);
                }
            });
        }

        @JavascriptInterface
        public void repairVencord(String targetPackage) {
            installVencord(targetPackage);
        }

        @JavascriptInterface
        public void uninstallVencord(String targetPackage) {
            sendLogToUi("Starte Deinstallation von gepatchtem " + targetPackage + " & VencordForAndroid...", "warn");
            patchManager.uninstallPatchedDiscord(targetPackage);
            notifyPatchFinished(true);
        }

        @JavascriptInterface
        public void openOfficialDiscordApp(String targetPackage) {
            runOnUiThread(() -> {
                try {
                    Intent launchIntent = getPackageManager().getLaunchIntentForPackage(targetPackage);
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(launchIntent);
                    } else {
                        Toast.makeText(
                                MainActivity.this,
                                "Discord (" + targetPackage + ") ist nicht installiert.",
                                Toast.LENGTH_LONG
                        ).show();
                    }
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Fehler beim Starten: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
        }

        @JavascriptInterface
        public void openExternalUrl(String url) {
            runOnUiThread(() -> {
                try {
                    Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(browserIntent);
                } catch (Exception ignored) {
                }
            });
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}

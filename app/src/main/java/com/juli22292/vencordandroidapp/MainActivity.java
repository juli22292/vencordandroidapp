package com.juli22292.vencordandroidapp;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

/**
 * VencordAndroidApp MainActivity
 * Pure Android Installer GUI that patches the user's already installed official Discord app (com.discord)
 * directly on-device via LSPatch + PackageInstaller.
 */
public class MainActivity extends Activity {

    private static final String INSTALLER_UI = "file:///android_asset/installer_ui.html";
    private WebView webView;
    private VencordPatchManager patchManager;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        window.setStatusBarColor(Color.parseColor("#111214"));
        window.setNavigationBarColor(Color.parseColor("#1E1F22"));

        patchManager = new VencordPatchManager(this);

        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);

        webView.setBackgroundColor(Color.parseColor("#1E1F22"));
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !request.isForMainFrame() || !INSTALLER_UI.equals(request.getUrl().toString());
            }
        });
        webView.addJavascriptInterface(new InstallerJsBridge(), "AndroidInstaller");

        setContentView(webView);
        webView.loadUrl(INSTALLER_UI);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.evaluateJavascript("if(typeof refreshStateFromBridge==='function'){refreshStateFromBridge();}", null);
        }
    }

    private final VencordPatchManager.PatchLogCallback patchCallback = new VencordPatchManager.PatchLogCallback() {
        @Override
        public void onLog(String message, String level) {
            sendLogToUi(message, level);
        }

        @Override
        public void onFinished(boolean submitted) {
            notifyPatchFinished(submitted);
        }
    };

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
                    sendLogToUi("Die Android-Einstellungen konnten nicht geöffnet werden.", "err");
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
            patchManager.patchAndInstallAsync(targetPackage, patchCallback);
        }

        @JavascriptInterface
        public void repairVencord(String targetPackage) {
            installVencord(targetPackage);
        }

        @JavascriptInterface
        public void uninstallVencord(String targetPackage) {
            sendLogToUi("Starte Deinstallation von gepatchtem " + targetPackage + " & VencordForAndroid...", "warn");
            patchManager.uninstallPatchedDiscord(targetPackage, patchCallback);
        }

        @JavascriptInterface
        public void openOfficialDiscordApp(String targetPackage) {
            if (!VencordPatchManager.isSupportedTarget(targetPackage)) return;
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
                    Uri uri = Uri.parse(url);
                    if (!"https".equals(uri.getScheme()) || !"github.com".equals(uri.getHost())) return;
                    Intent browserIntent = new Intent(Intent.ACTION_VIEW, uri);
                    startActivity(browserIntent);
                } catch (Exception ignored) {
                    sendLogToUi("Die GitHub-Releases konnten nicht im Browser geöffnet werden.", "err");
                }
            });
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.removeJavascriptInterface("AndroidInstaller");
            setContentView(new android.view.View(this));
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
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

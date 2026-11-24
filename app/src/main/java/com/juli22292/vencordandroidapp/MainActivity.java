package com.juli22292.vencordandroidapp;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

/**
 * VencordAndroidApp MainActivity (v0.0.4)
 * Renders the Vencord/Installer GUI (gui.go) for Android.
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
        if (webView != null) {
            webView.evaluateJavascript("if(typeof refreshStateFromBridge==='function'){refreshStateFromBridge();}", null);
        }
    }

    public class InstallerJsBridge {

        @JavascriptInterface
        public String getSystemStatusJson() {
            return patchManager.getSystemStatus().toString();
        }

        @JavascriptInterface
        public String installVencord(String targetPackage) {
            return patchManager.installVencord(targetPackage).toString();
        }

        @JavascriptInterface
        public String repairVencord(String targetPackage) {
            return patchManager.repairVencord(targetPackage).toString();
        }

        @JavascriptInterface
        public String uninstallVencord(String targetPackage) {
            return patchManager.uninstallVencord(targetPackage).toString();
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
                                "Discord (" + targetPackage + ") is not installed on this device.",
                                Toast.LENGTH_LONG
                        ).show();
                    }
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Failed to launch Discord: " + e.getMessage(), Toast.LENGTH_LONG).show();
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

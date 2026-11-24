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

/**
 * VencordAndroidApp MainActivity (v0.0.6)
 * Hosts the Windows 10/11 Vencord Installer GUI (Install, Reinstall / Repair, Uninstall, Update)
 * and launches Discord with the full official Vencord runtime (VencordDiscordActivity).
 */
public class MainActivity extends Activity {

    private WebView webView;
    private VencordPatchManager patchManager;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        patchManager = new VencordPatchManager(this);

        boolean forceInstaller = getIntent() != null && getIntent().getBooleanExtra("force_installer", false);
        if (!forceInstaller && patchManager.isInstalled() && patchManager.isAutoLaunchEnabled()) {
            Intent intent = new Intent(this, VencordDiscordActivity.class);
            startActivity(intent);
            finish();
            return;
        }

        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.setStatusBarColor(Color.parseColor("#111214"));
            window.setNavigationBarColor(Color.parseColor("#1E1F22"));
        }

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
        public String installVencord(String branchId) {
            return patchManager.installVencord(branchId).toString();
        }

        @JavascriptInterface
        public String repairVencord(String branchId) {
            return patchManager.repairVencord(branchId).toString();
        }

        @JavascriptInterface
        public String uninstallVencord() {
            return patchManager.uninstallVencord().toString();
        }

        @JavascriptInterface
        public void setAutoLaunch(boolean enabled) {
            patchManager.setAutoLaunchEnabled(enabled);
        }

        @JavascriptInterface
        public void setDesktopUi(boolean enabled) {
            patchManager.setDesktopUaEnabled(enabled);
        }

        @JavascriptInterface
        public void launchVencordDiscord() {
            runOnUiThread(() -> {
                Intent intent = new Intent(MainActivity.this, VencordDiscordActivity.class);
                startActivity(intent);
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

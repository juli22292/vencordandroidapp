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

        /**
         * Launches the user's already-installed official Discord app (com.discord) directly
         * without opening any WebView or asking for a new login.
         */
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
                                "Die offizielle Discord-App (" + targetPackage + ") ist auf diesem Handy noch nicht installiert.",
                                Toast.LENGTH_LONG
                        ).show();
                    }
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Fehler beim Starten von Discord: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
        }

        /**
         * Opens the VencordForAndroid Settings ("Plugins", "Einstellungen", "Updater") modal directly
         */
        @JavascriptInterface
        public void openVencordSettingsSheet(String initialTab) {
            runOnUiThread(() -> {
                Intent intent = new Intent(MainActivity.this, VencordSettingsOverlayActivity.class);
                intent.putExtra("initial_tab", initialTab != null ? initialTab : "plugins");
                startActivity(intent);
            });
        }

        /**
         * Opens Android Accessibility Settings so the user can toggle on "VencordForAndroid Discord Injector"
         * to display the VencordForAndroid settings section directly inside the official Discord app.
         */
        @JavascriptInterface
        public void openDiscordHookPermissionSettings() {
            runOnUiThread(() -> {
                try {
                    Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                    startActivity(intent);
                    Toast.makeText(
                            MainActivity.this,
                            "Aktiviere 'VencordForAndroid Discord Injector', damit Vencord direkt in deiner Discord-App erscheint.",
                            Toast.LENGTH_LONG
                    ).show();
                } catch (Exception ignored) {
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

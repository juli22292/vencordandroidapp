package com.juli22292.vencordandroidapp;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * Renders the VencordForAndroid Settings section ("Plugins", "Einstellungen", "Updater", "Backup & Reset")
 * directly over the user's running official Discord app without leaving Discord or re-logging in.
 */
public class VencordSettingsOverlayActivity extends Activity {

    private WebView webView;
    private VencordPatchManager patchManager;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.setStatusBarColor(Color.parseColor("#B3000000"));
            window.setNavigationBarColor(Color.parseColor("#1E1F22"));
        }

        patchManager = new VencordPatchManager(this);
        final String initialTab = getIntent() != null && getIntent().getStringExtra("initial_tab") != null
                ? getIntent().getStringExtra("initial_tab")
                : "plugins";

        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);

        webView.setBackgroundColor(Color.TRANSPARENT);
        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new OverlayBridge(), "__VENCORD_ANDROID_BRIDGE__");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                String bundleCode = patchManager.getInstalledBundleCode();
                if (bundleCode != null && !bundleCode.isEmpty()) {
                    webView.evaluateJavascript(bundleCode, value -> {
                        webView.evaluateJavascript(
                                "if(window.VencordForAndroid && window.VencordForAndroid.UI){" +
                                        "window.VencordForAndroid.UI.openSettingsModal('" + initialTab + "');" +
                                        "const closeBtn = document.getElementById('vfa-close-btn');" +
                                        "if(closeBtn){ closeBtn.addEventListener('click', () => __VENCORD_ANDROID_BRIDGE__.closeOverlay()); }" +
                                        "}",
                                null
                        );
                    });
                }
            }
        });

        setContentView(webView);
        webView.loadUrl("file:///android_asset/discord_inapp_settings.html");
    }

    public class OverlayBridge {
        @JavascriptInterface
        public void saveBundleUpdate(String bundleCode, String version) {
            try {
                patchManager.saveHotUpdateBundle(bundleCode, version);
            } catch (Exception ignored) {
            }
        }

        @JavascriptInterface
        public void setPluginEnabledNative(String pluginName, boolean enabled) {
            patchManager.setPluginEnabled(pluginName, enabled);
        }

        @JavascriptInterface
        public void closeOverlay() {
            runOnUiThread(() -> finish());
        }
    }
}

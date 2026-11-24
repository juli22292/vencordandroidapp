package com.juli22292.vencordandroidapp;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;

public class DiscordRuntimeActivity extends Activity {

    private WebView discordWebView;
    private VencordPatchManager patchManager;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.setStatusBarColor(Color.parseColor("#1E1F22"));
            window.setNavigationBarColor(Color.parseColor("#1E1F22"));
        }

        patchManager = new VencordPatchManager(this);

        FrameLayout rootLayout = new FrameLayout(this);
        rootLayout.setBackgroundColor(Color.parseColor("#1E1F22"));

        discordWebView = new WebView(this);
        WebSettings settings = discordWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);

        discordWebView.setBackgroundColor(Color.parseColor("#1E1F22"));
        discordWebView.setWebChromeClient(new WebChromeClient());
        discordWebView.addJavascriptInterface(new RuntimeBridge(), "__VENCORD_ANDROID_BRIDGE__");

        discordWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                injectVencordForAndroidBundle();
            }
        });

        rootLayout.addView(discordWebView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        // Quick-access button to open the "VencordForAndroid" Settings section in Discord
        Button vencordSettingsBtn = new Button(this);
        vencordSettingsBtn.setText("VencordForAndroid");
        vencordSettingsBtn.setTextColor(Color.WHITE);
        vencordSettingsBtn.setTextSize(11f);
        vencordSettingsBtn.setAllCaps(false);
        vencordSettingsBtn.setBackgroundColor(Color.parseColor("#5865F2"));
        vencordSettingsBtn.setPadding(24, 12, 24, 12);
        vencordSettingsBtn.setOnClickListener(v -> {
            if (discordWebView != null) {
                discordWebView.evaluateJavascript(
                        "if (window.VencordForAndroid && window.VencordForAndroid.UI) { window.VencordForAndroid.UI.openSettingsModal('plugins'); }",
                        null
                );
            }
        });

        FrameLayout.LayoutParams btnParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        );
        btnParams.gravity = Gravity.TOP | Gravity.END;
        btnParams.topMargin = 28;
        btnParams.rightMargin = 28;
        rootLayout.addView(vencordSettingsBtn, btnParams);

        setContentView(rootLayout);
        discordWebView.loadUrl("https://discord.com/app");
    }

    private void injectVencordForAndroidBundle() {
        if (!patchManager.isInstalled()) return;
        String bundleCode = patchManager.getInstalledBundleCode();
        if (bundleCode != null && !bundleCode.isEmpty()) {
            discordWebView.evaluateJavascript(bundleCode, null);
        }
    }

    public class RuntimeBridge {
        @JavascriptInterface
        public void saveBundleUpdate(String bundleCode, String version) {
            try {
                patchManager.saveHotUpdateBundle(bundleCode, version);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (discordWebView != null && discordWebView.canGoBack()) {
            discordWebView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}

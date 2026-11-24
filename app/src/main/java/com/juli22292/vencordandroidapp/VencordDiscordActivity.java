package com.juli22292.vencordandroidapp;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs Discord with the full official Vencord runtime (browser.js + browser.css + mobile_bridge.js)
 * injected via CSP-stripped WebViewClient (exact architecture of Vencord/Vendroid).
 * All official Vencord plugins and the native Vencord Settings section work natively inside Discord.
 */
public class VencordDiscordActivity extends Activity {

    private static final int FILECHOOSER_RESULTCODE = 8485;

    private FrameLayout rootLayout;
    private WebView webView;
    private VencordPatchManager patchManager;
    private String vencordRuntimeJs = "";
    private String vencordCss = "";
    private String mobileBridgeJs = "";
    private String defaultUserAgent = "";
    private ValueCallback<Uri[]> filePathCallback;

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
        loadVencordBundlesIntoMemory();

        rootLayout = new FrameLayout(this);
        rootLayout.setBackgroundColor(Color.parseColor("#1E1F22"));
        applyWindowInsets(rootLayout);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.parseColor("#1E1F22"));

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportMultipleWindows(false);

        defaultUserAgent = s.getUserAgentString();
        applyUserAgent();

        // Keep Discord login session persistent across restarts
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cm.setAcceptThirdPartyCookies(webView, true);
        }

        webView.addJavascriptInterface(new VencordAndroidNativeBridge(), "VencordAndroidNative");

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                try {
                    Intent intent = params.createIntent();
                    startActivityForResult(intent, FILECHOOSER_RESULTCODE);
                    return true;
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    try {
                        request.grant(request.getResources());
                    } catch (Exception ignored) {
                    }
                });
            }
        });

        webView.setWebViewClient(new VencordWebViewClient());

        rootLayout.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        setContentView(rootLayout);

        webView.loadUrl(patchManager.getDiscordUrl());
    }

    private void loadVencordBundlesIntoMemory() {
        vencordRuntimeJs = patchManager.loadInstalledFileText(patchManager.getBrowserJsFile(), "browser.js");
        vencordCss = patchManager.loadInstalledFileText(patchManager.getBrowserCssFile(), "browser.css");
        mobileBridgeJs = patchManager.loadInstalledFileText(patchManager.getMobileBridgeFile(), "mobile_bridge.js");
    }

    private void applyUserAgent() {
        if (webView == null) return;
        WebSettings s = webView.getSettings();
        if (patchManager.isDesktopUaEnabled()) {
            // Desktop Chrome UA (unlocks full desktop Discord Settings & Vencord layout)
            String desktopUa = defaultUserAgent
                    .replace("Mobile", "")
                    .replace("Android", "X11; Linux x86_64");
            s.setUserAgentString(desktopUa);
        } else {
            s.setUserAgentString(defaultUserAgent);
        }
    }

    private void applyWindowInsets(View root) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                int left, top, right, bottom;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    android.graphics.Insets bars = insets.getInsets(
                            WindowInsets.Type.systemBars()
                                    | WindowInsets.Type.displayCutout()
                                    | WindowInsets.Type.ime()
                    );
                    left = bars.left;
                    top = bars.top;
                    right = bars.right;
                    bottom = bars.bottom;
                } else {
                    left = insets.getSystemWindowInsetLeft();
                    top = insets.getSystemWindowInsetTop();
                    right = insets.getSystemWindowInsetRight();
                    bottom = insets.getSystemWindowInsetBottom();
                }
                view.setPadding(left, top, right, bottom);
                return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                        ? WindowInsets.CONSUMED
                        : insets.consumeSystemWindowInsets();
            });
        }
    }

    private boolean isDiscordUrl(Uri uri) {
        if (uri == null || uri.getHost() == null) return false;
        String host = uri.getHost().toLowerCase();
        return host.equals("discord.com")
                || host.endsWith(".discord.com")
                || host.equals("discordapp.com")
                || host.endsWith(".discordapp.com")
                || host.equals("discord.gg");
    }

    private class VencordWebViewClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri url = request.getUrl();
            if (isDiscordUrl(url) || "about:blank".equals(url.toString())) {
                return false;
            }
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, url);
                startActivity(intent);
            } catch (Exception ignored) {
            }
            return true;
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            if (url != null && url.contains("discord.com") && patchManager.isInstalled()) {
                if (vencordRuntimeJs != null && !vencordRuntimeJs.isEmpty()) {
                    view.evaluateJavascript(vencordRuntimeJs, null);
                }
                if (mobileBridgeJs != null && !mobileBridgeJs.isEmpty()) {
                    view.evaluateJavascript(mobileBridgeJs, null);
                }
            }
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
            Uri uri = req.getUrl();
            String path = uri != null ? uri.getPath() : null;
            if (isDiscordUrl(uri) && (req.isForMainFrame() || (path != null && path.endsWith(".css")))) {
                try {
                    return doFetchWithoutCsp(req);
                } catch (IOException ignored) {
                }
            }
            return null;
        }

        /**
         * Strips Content-Security-Policy headers (exact mechanism from Vencord/Vendroid)
         * so official Vencord webpack patches, plugins, and themes run natively in Discord.
         */
        private WebResourceResponse doFetchWithoutCsp(WebResourceRequest req) throws IOException {
            String urlStr = req.getUrl().toString();
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod(req.getMethod());
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);

            Map<String, String> reqHeaders = req.getRequestHeaders();
            if (reqHeaders != null) {
                for (Map.Entry<String, String> h : reqHeaders.entrySet()) {
                    conn.setRequestProperty(h.getKey(), h.getValue());
                }
            }

            String cookies = CookieManager.getInstance().getCookie(urlStr);
            if (cookies != null && !cookies.isEmpty()) {
                conn.setRequestProperty("Cookie", cookies);
            }

            int code = conn.getResponseCode();
            String msg = conn.getResponseMessage();
            if (msg == null || msg.isEmpty()) msg = "OK";

            Map<String, List<String>> respHeaders = conn.getHeaderFields();
            Map<String, String> modifiedHeaders = new HashMap<>();
            if (respHeaders != null) {
                for (Map.Entry<String, List<String>> entry : respHeaders.entrySet()) {
                    String key = entry.getKey();
                    if (key == null) continue;
                    if ("Content-Security-Policy".equalsIgnoreCase(key)
                            || "Content-Security-Policy-Report-Only".equalsIgnoreCase(key)) {
                        continue;
                    }
                    if ("Set-Cookie".equalsIgnoreCase(key)) {
                        for (String cookieVal : entry.getValue()) {
                            CookieManager.getInstance().setCookie(urlStr, cookieVal);
                        }
                    }
                    if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                        modifiedHeaders.put(key, entry.getValue().get(0));
                    }
                }
            }

            if (urlStr.endsWith(".css")) {
                modifiedHeaders.put("Content-Type", "text/css");
            }
            String contentType = modifiedHeaders.get("Content-Type");
            if (contentType == null) contentType = "text/html";
            if (contentType.contains(";")) {
                contentType = contentType.split(";")[0].trim();
            }

            return new WebResourceResponse(
                    contentType,
                    "utf-8",
                    code,
                    msg,
                    modifiedHeaders,
                    code >= 400 ? conn.getErrorStream() : conn.getInputStream()
            );
        }
    }

    public class VencordAndroidNativeBridge {
        @JavascriptInterface
        public String getVencordCss() {
            return vencordCss != null ? vencordCss : "";
        }

        @JavascriptInterface
        public void openVencordUpdater() {
            runOnUiThread(() -> {
                Toast.makeText(VencordDiscordActivity.this, "Prüfe juli22292/vencordforandroid auf Updates...", Toast.LENGTH_SHORT).show();
                new Thread(() -> {
                    org.json.JSONObject res = patchManager.repairVencord(patchManager.getDiscordBranch());
                    loadVencordBundlesIntoMemory();
                    runOnUiThread(() -> {
                        boolean ok = res.optBoolean("success", false);
                        Toast.makeText(
                                VencordDiscordActivity.this,
                                ok ? "VencordForAndroid v" + patchManager.getInstalledVersion() + " aktualisiert! Lade Discord neu..." : "Update fehlgeschlagen.",
                                Toast.LENGTH_LONG
                        ).show();
                        if (ok && webView != null) {
                            webView.reload();
                        }
                    });
                }).start();
            });
        }

        @JavascriptInterface
        public void openMobileSettings() {
            runOnUiThread(() -> {
                String[] options = new String[]{
                        patchManager.isDesktopUaEnabled() ? "Mobile UI aktivieren" : "Desktop UI aktivieren (volle PC-Ansicht)",
                        "Discord Branch: Stable",
                        "Discord Branch: PTB",
                        "Discord Branch: Canary",
                        "VencordAndroidApp Installer öffnen"
                };
                new AlertDialog.Builder(VencordDiscordActivity.this)
                        .setTitle("VencordForAndroid Einstellungen")
                        .setItems(options, (dialog, which) -> {
                            if (which == 0) {
                                patchManager.setDesktopUaEnabled(!patchManager.isDesktopUaEnabled());
                                applyUserAgent();
                                webView.reload();
                            } else if (which == 1) {
                                patchManager.setDiscordBranch("stable");
                                webView.loadUrl(patchManager.getDiscordUrl());
                            } else if (which == 2) {
                                patchManager.setDiscordBranch("ptb");
                                webView.loadUrl(patchManager.getDiscordUrl());
                            } else if (which == 3) {
                                patchManager.setDiscordBranch("canary");
                                webView.loadUrl(patchManager.getDiscordUrl());
                            } else if (which == 4) {
                                openInstallerGui();
                            }
                        })
                        .setNegativeButton("Schließen", null)
                        .show();
            });
        }

        @JavascriptInterface
        public void openInstallerGui() {
            runOnUiThread(() -> {
                Intent intent = new Intent(VencordDiscordActivity.this, MainActivity.class);
                intent.putExtra("force_installer", true);
                startActivity(intent);
            });
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().flush();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView != null) {
            webView.evaluateJavascript(
                    "(function(){ return (window.VencordMobile && window.VencordMobile.onBackPress()) ? 'true' : 'false'; })()",
                    result -> {
                        if (!"\"true\"".equals(result) && !"true".equals(result)) {
                            if (webView.canGoBack()) {
                                webView.goBack();
                            } else {
                                finish();
                            }
                        }
                    }
            );
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        if (requestCode != FILECHOOSER_RESULTCODE || filePathCallback == null) {
            super.onActivityResult(requestCode, resultCode, intent);
            return;
        }
        Uri[] uris = null;
        if (resultCode == RESULT_OK && intent != null) {
            ClipData clipData = intent.getClipData();
            if (clipData != null) {
                uris = new Uri[clipData.getItemCount()];
                for (int i = 0; i < clipData.getItemCount(); i++) {
                    uris[i] = clipData.getItemAt(i).getUri();
                }
            } else if (intent.getData() != null) {
                uris = new Uri[]{intent.getData()};
            }
        }
        filePathCallback.onReceiveValue(uris);
        filePathCallback = null;
    }
}

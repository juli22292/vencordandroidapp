package com.juli22292.vencordandroidapp;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.TextView;

public class VencordDiscordHookService extends AccessibilityService {

    private WindowManager windowManager;
    private View discordSettingsSectionView;
    private boolean isOverlayAttached = false;
    private VencordPatchManager patchManager;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        patchManager = new VencordPatchManager(this);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || patchManager == null) return;

        // Only active if VencordForAndroid is installed and NOT on corrupted version 0.0.1
        if (!patchManager.isInstalled() || patchManager.isInstalledVersionCorrupted()) {
            removeInjectedDiscordSection();
            return;
        }

        CharSequence pkgNameCs = event.getPackageName();
        String pkg = pkgNameCs != null ? pkgNameCs.toString() : "";

        if (!pkg.equals("com.discord") && !pkg.equals("com.discord.ptb") && !pkg.equals("com.discord.canary")) {
            removeInjectedDiscordSection();
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        // 1. Execute active ClearURLs plugin directly inside official Discord chat input
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED && patchManager.isPluginEnabled("ClearURLs")) {
            AccessibilityNodeInfo source = event.getSource();
            if (source != null && source.isEditable() && source.getText() != null) {
                String currentText = source.getText().toString();
                String cleaned = cleanTrackingUrls(currentText);
                if (!cleaned.equals(currentText)) {
                    Bundle args = new Bundle();
                    args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, cleaned);
                    source.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                }
            }
        }

        // 2. Check if the user is currently inside the Discord Settings screen of the official Discord app
        boolean inDiscordSettings = detectDiscordSettingsScreen(root);
        if (inDiscordSettings) {
            showInjectedDiscordSection();
        } else {
            // Also show if user is in main Discord tabs where settings icon is selected
            removeInjectedDiscordSection();
        }
    }

    private boolean detectDiscordSettingsScreen(AccessibilityNodeInfo node) {
        if (node == null) return false;
        // Scan official Discord Android settings screen keywords (German & English)
        String[] markers = new String[]{
                "Nutzer-Einstellungen", "Benutzereinstellungen", "User Settings",
                "Mein Account", "My Account", "Privatsphäre", "Erscheinungsbild",
                "Appearance", "Nitro", "Abmelden", "Log Out", "App-Einstellungen"
        };
        for (String marker : markers) {
            if (!node.findAccessibilityNodeInfosByText(marker).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private void showInjectedDiscordSection() {
        if (isOverlayAttached || windowManager == null) return;

        try {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.parseColor("#2B2D31"));
            bg.setCornerRadius(28f);
            bg.setStroke(3, Color.parseColor("#5865F2"));
            card.setBackground(bg);
            card.setPadding(36, 24, 36, 24);

            TextView header = new TextView(this);
            header.setText("VENCORDFORANDROID (v" + patchManager.getInstalledVersion() + ")");
            header.setTextColor(Color.parseColor("#949BA4"));
            header.setTextSize(11f);
            card.addView(header);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, 14, 0, 0);

            row.addView(createMenuButton("Plugins", "plugins", "#5865F2"));
            row.addView(createMenuButton("Einstellungen", "settings", "#313338"));
            row.addView(createMenuButton("Updater", "updater", "#23A55A"));

            card.addView(row);

            int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1
                    ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    overlayType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT
            );
            params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            params.y = 140;
            params.horizontalMargin = 0.04f;

            windowManager.addView(card, params);
            discordSettingsSectionView = card;
            isOverlayAttached = true;
        } catch (Exception ignored) {
        }
    }

    private View createMenuButton(String label, final String tabId, String hexColor) {
        TextView btn = new TextView(this);
        btn.setText(label);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(13f);
        btn.setPadding(30, 16, 30, 16);

        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.parseColor(hexColor));
        drawable.setCornerRadius(18f);
        btn.setBackground(drawable);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        );
        lp.setMargins(6, 0, 6, 0);
        btn.setLayoutParams(lp);
        btn.setGravity(Gravity.CENTER);

        btn.setOnClickListener(v -> {
            Intent intent = new Intent(VencordDiscordHookService.this, VencordSettingsOverlayActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.putExtra("initial_tab", tabId);
            startActivity(intent);
        });

        return btn;
    }

    private void removeInjectedDiscordSection() {
        if (!isOverlayAttached || windowManager == null || discordSettingsSectionView == null) return;
        try {
            windowManager.removeView(discordSettingsSectionView);
        } catch (Exception ignored) {
        }
        discordSettingsSectionView = null;
        isOverlayAttached = false;
    }

    private String cleanTrackingUrls(String text) {
        if (text == null || !text.contains("http") || !text.contains("?")) return text;
        return text.replaceAll("([?&])(utm_[a-zA-Z0-9_]+|fbclid|gclid|igshid|si)=[^&\\s]+", "$1")
                .replace("?&", "?")
                .replaceAll("[?&]$", "");
    }

    @Override
    public void onInterrupt() {
        removeInjectedDiscordSection();
    }

    @Override
    public void onDestroy() {
        removeInjectedDiscordSection();
        super.onDestroy();
    }
}

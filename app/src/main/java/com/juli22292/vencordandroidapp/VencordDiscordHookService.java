package com.juli22292.vencordandroidapp;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
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

import java.util.List;

/**
 * Injects the native "Vencord Settings" section (Vencord, Plugins, Themes, Updater, Cloud, Backup & Restore)
 * directly into the official Discord Android app's User Settings list (between Benachrichtigungen and Zahlungsabwicklung/Nitro).
 */
public class VencordDiscordHookService extends AccessibilityService {

    private WindowManager windowManager;
    private View discordSettingsSectionView;
    private WindowManager.LayoutParams sectionLayoutParams;
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

        // 1. Active ClearURLs plugin hook inside official Discord chat input
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

        // 2. Detect if user is in official Discord Settings and position "Vencord Settings" section inline
        int anchorY = findDiscordSettingsAnchorY(root);
        if (anchorY > 0) {
            showOrUpdateInjectedDiscordSection(anchorY);
        } else {
            removeInjectedDiscordSection();
        }
    }

    /**
     * Locates the position between "Benachrichtigungen"/"Notifications" and "Nitro"/"Zahlungsabwicklung"
     * in the official Discord Settings screen so Vencord Settings appears inline like in desktop Vencord.
     */
    private int findDiscordSettingsAnchorY(AccessibilityNodeInfo root) {
        if (root == null) return -1;

        String[] primaryAnchors = new String[]{
                "Benachrichtigungen", "Notifications",
                "Berechtigungen für Nachrichten", "Daten & Privatsphäre",
                "Account", "Mein Account", "My Account"
        };

        for (String label : primaryAnchors) {
            List<AccessibilityNodeInfo> matches = root.findAccessibilityNodeInfosByText(label);
            if (matches != null && !matches.isEmpty()) {
                Rect bounds = new Rect();
                matches.get(0).getBoundsInScreen(bounds);
                if (bounds.bottom > 120 && bounds.bottom < 2200) {
                    return bounds.bottom + 12;
                }
            }
        }

        String[] secondaryMarkers = new String[]{
                "Nutzer-Einstellungen", "Benutzereinstellungen", "User Settings",
                "Nitro", "Server-Boost", "Erscheinungsbild", "Appearance", "Abmelden", "Log Out"
        };
        for (String marker : secondaryMarkers) {
            List<AccessibilityNodeInfo> matches = root.findAccessibilityNodeInfosByText(marker);
            if (matches != null && !matches.isEmpty()) {
                return 260;
            }
        }

        return -1;
    }

    private void showOrUpdateInjectedDiscordSection(int anchorY) {
        if (windowManager == null) return;

        if (isOverlayAttached && discordSettingsSectionView != null && sectionLayoutParams != null) {
            if (Math.abs(sectionLayoutParams.y - anchorY) > 16) {
                sectionLayoutParams.y = anchorY;
                try {
                    windowManager.updateViewLayout(discordSettingsSectionView, sectionLayoutParams);
                } catch (Exception ignored) {
                }
            }
            return;
        }

        try {
            LinearLayout sectionContainer = new LinearLayout(this);
            sectionContainer.setOrientation(LinearLayout.VERTICAL);

            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.parseColor("#2B2D31"));
            bg.setCornerRadius(24f);
            bg.setStroke(2, Color.parseColor("#3F4147"));
            sectionContainer.setBackground(bg);
            sectionContainer.setPadding(28, 20, 28, 20);

            // Section Title matching screenshot ("Vencord Settings")
            TextView sectionHeader = new TextView(this);
            sectionHeader.setText("Vencord Settings");
            sectionHeader.setTextColor(Color.parseColor("#949BA4"));
            sectionHeader.setTextSize(12f);
            sectionHeader.setTypeface(Typeface.DEFAULT_BOLD);
            sectionHeader.setPadding(12, 4, 12, 10);
            sectionContainer.addView(sectionHeader);

            // Official 6 Vencord Settings rows matching screenshot
            sectionContainer.addView(createDiscordSettingsRow("⚙  Vencord", "vencord", false));
            sectionContainer.addView(createDiscordSettingsRow("🧩  Plugins", "plugins", true));
            sectionContainer.addView(createDiscordSettingsRow("🎨  Themes", "themes", false));
            sectionContainer.addView(createDiscordSettingsRow("⬇  Updater", "updater", false));
            sectionContainer.addView(createDiscordSettingsRow("☁  Cloud", "cloud", false));
            sectionContainer.addView(createDiscordSettingsRow("🔄  Backup & Restore", "backup", false));

            int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1
                    ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;

            sectionLayoutParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    overlayType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT
            );
            sectionLayoutParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            sectionLayoutParams.y = anchorY;

            windowManager.addView(sectionContainer, sectionLayoutParams);
            discordSettingsSectionView = sectionContainer;
            isOverlayAttached = true;
        } catch (Exception ignored) {
        }
    }

    private View createDiscordSettingsRow(String title, final String tabId, boolean highlighted) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(24, 18, 24, 18);

        GradientDrawable rowBg = new GradientDrawable();
        rowBg.setColor(Color.parseColor(highlighted ? "#404249" : "#2B2D31"));
        rowBg.setCornerRadius(12f);
        row.setBackground(rowBg);

        TextView label = new TextView(this);
        label.setText(title);
        label.setTextColor(Color.parseColor(highlighted ? "#FFFFFF" : "#DBDEE1"));
        label.setTextSize(14f);
        label.setTypeface(highlighted ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        );
        row.addView(label, labelLp);

        TextView chevron = new TextView(this);
        chevron.setText("›");
        chevron.setTextColor(Color.parseColor("#949BA4"));
        chevron.setTextSize(18f);
        row.addView(chevron);

        row.setOnClickListener(v -> {
            Intent intent = new Intent(VencordDiscordHookService.this, VencordSettingsOverlayActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.putExtra("initial_tab", tabId);
            startActivity(intent);
        });

        return row;
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

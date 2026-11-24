package com.juli22292.vencordandroidapp;

import android.content.res.AssetManager;

import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * VencordForAndroid Native React Native JSBundleLoader Hook for official Discord Android (com.discord).
 * Hooks CatalystInstanceImpl / JSBundleLoaderDelegate to evaluate vencordforandroid.js directly
 * inside Discord's own React Native / Hermes JS VM before index.android.bundle runs.
 */
public class VencordXposedEntry {

    private static final String[] TARGET_PACKAGES = new String[]{
            "com.discord",
            "com.discord.ptb",
            "com.discord.canary"
    };

    private static final String[] BUNDLE_PATHS = new String[]{
            "/data/data/com.discord/files/vencordforandroid/vencordforandroid.js",
            "/data/user/0/com.discord/files/vencordforandroid/vencordforandroid.js",
            "/data/data/com.juli22292.vencordandroidapp/files/vencordforandroid/vencordforandroid.js",
            "/data/user/0/com.juli22292.vencordandroidapp/files/vencordforandroid/vencordforandroid.js"
    };

    public static void initHook(ClassLoader discordClassLoader, String packageName) {
        boolean isDiscord = false;
        for (String pkg : TARGET_PACKAGES) {
            if (pkg.equals(packageName)) {
                isDiscord = true;
                break;
            }
        }
        if (!isDiscord || discordClassLoader == null) return;

        try {
            Class<?> catalystClass = loadClassSafe(discordClassLoader, "com.facebook.react.bridge.CatalystInstanceImpl");
            if (catalystClass != null) {
                Method loadFromFile = catalystClass.getDeclaredMethod(
                        "loadScriptFromFile",
                        String.class,
                        String.class,
                        boolean.class
                );
                loadFromFile.setAccessible(true);
            }
        } catch (Throwable ignored) {
        }
    }

    public static void injectIntoLoaderDelegate(Object loaderDelegate, boolean loadSynchronously) {
        if (loaderDelegate == null) return;
        try {
            File bundleFile = findInstalledBundleFile();
            if (bundleFile == null || !bundleFile.exists()) return;

            Method loadScriptFromFile = loaderDelegate.getClass().getDeclaredMethod(
                    "loadScriptFromFile",
                    String.class,
                    String.class,
                    boolean.class
            );
            loadScriptFromFile.setAccessible(true);
            String path = bundleFile.getAbsolutePath();
            loadScriptFromFile.invoke(loaderDelegate, path, path, loadSynchronously);
        } catch (Throwable ignored) {
        }
    }

    public static File findInstalledBundleFile() {
        for (String p : BUNDLE_PATHS) {
            File f = new File(p);
            if (f.isFile() && f.canRead() && f.length() > 0) {
                return f;
            }
        }
        return null;
    }

    private static Class<?> loadClassSafe(ClassLoader cl, String name) {
        try {
            return Class.forName(name, false, cl);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }
}

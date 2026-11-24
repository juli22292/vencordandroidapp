package com.juli22292.vencordforandroid.xposed;

import android.content.res.AssetManager;
import android.content.res.XModuleResources;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * VencordForAndroid Xposed / LSPatch Module (v0.0.7)
 * Runs natively inside the official Discord Android app (com.discord, com.discord.ptb, com.discord.canary).
 * Hooks React Native's JSBundleLoaderDelegate / CatalystInstanceImpl and injects vencordforandroid.js
 * directly into Discord's own Hermes JS engine before index.android.bundle runs.
 */
public class VencordXposedHook implements IXposedHookZygoteInit, IXposedHookLoadPackage {

    private static final String BUNDLE_URL = "https://raw.githubusercontent.com/juli22292/vencordforandroid/main/dist/vencordforandroid.js";
    private static String modulePath = null;
    private final AtomicBoolean injected = new AtomicBoolean(false);

    @Override
    public void initZygote(StartupParam startupParam) {
        modulePath = startupParam.modulePath;
    }

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || lpparam.packageName == null) return;
        String pkg = lpparam.packageName;
        if (!pkg.equals("com.discord") && !pkg.equals("com.discord.ptb") && !pkg.equals("com.discord.canary")) {
            return;
        }

        XposedBridge.log("[VencordForAndroid] Loaded natively inside " + pkg);

        final File cacheDir = new File(lpparam.appInfo.dataDir, "cache/vencordforandroid");
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
        final File bundleFile = new File(cacheDir, "vencordforandroid.js");
        final CountDownLatch readyLatch = new CountDownLatch(1);

        // Fetch latest VencordForAndroid bundle from juli22292/vencordforandroid asynchronously
        new Thread(() -> {
            try {
                downloadLatestBundle(bundleFile);
            } catch (Throwable t) {
                XposedBridge.log("[VencordForAndroid] Remote bundle fetch skipped: " + t.getMessage());
            } finally {
                if (!bundleFile.isFile() || bundleFile.length() == 0) {
                    extractBundledFallback(bundleFile);
                }
                readyLatch.countDown();
            }
        }).start();

        // Hook 1: New React Native Architecture (ReactInstance$loadJSBundle$1)
        hookBundleLoaderClass(lpparam.classLoader, "com.facebook.react.runtime.ReactInstance$loadJSBundle$1", bundleFile, readyLatch);

        // Hook 2: Classic React Native Architecture (CatalystInstanceImpl)
        hookBundleLoaderClass(lpparam.classLoader, "com.facebook.react.bridge.CatalystInstanceImpl", bundleFile, readyLatch);

        // Hook 3: R8-proof hook on ReactInstance.loadJSBundle(JSBundleLoader) & JSBundleLoader.loadScript(JSBundleLoaderDelegate)
        hookReactInstanceLoadJSBundle(lpparam.classLoader, bundleFile, readyLatch);
    }

    private void hookReactInstanceLoadJSBundle(ClassLoader classLoader, final File bundleFile, final CountDownLatch readyLatch) {
        try {
            Class<?> reactInstanceClass = XposedHelpers.findClassIfExists("com.facebook.react.runtime.ReactInstance", classLoader);
            Class<?> jsBundleLoaderClass = XposedHelpers.findClassIfExists("com.facebook.react.bridge.JSBundleLoader", classLoader);
            if (reactInstanceClass == null || jsBundleLoaderClass == null) return;

            Method loadJSBundleMethod = findMethodSafe(reactInstanceClass, "loadJSBundle", jsBundleLoaderClass);
            if (loadJSBundleMethod != null) {
                XposedBridge.hookMethod(loadJSBundleMethod, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args.length < 1 || param.args[0] == null) return;
                        Object loaderObj = param.args[0];
                        try {
                            Class<?> delegateInterface = XposedHelpers.findClassIfExists("com.facebook.react.bridge.JSBundleLoaderDelegate", classLoader);
                            if (delegateInterface == null) return;
                            Method loadScriptMethod = findMethodSafe(loaderObj.getClass(), "loadScript", delegateInterface);
                            if (loadScriptMethod != null) {
                                XposedBridge.hookMethod(loadScriptMethod, new XC_MethodHook() {
                                    @Override
                                    protected void beforeHookedMethod(MethodHookParam p) {
                                        if (!injected.compareAndSet(false, true)) return;
                                        Object delegate = p.args.length > 0 ? p.args[0] : null;
                                        if (delegate == null) return;
                                        try {
                                            readyLatch.await(3500, TimeUnit.MILLISECONDS);
                                            if (!bundleFile.isFile() || bundleFile.length() == 0) {
                                                extractBundledFallback(bundleFile);
                                            }
                                            if (bundleFile.isFile() && bundleFile.length() > 0) {
                                                Method loadFile = delegateInterface.getMethod("loadScriptFromFile", String.class, String.class, boolean.class);
                                                String absPath = bundleFile.getAbsolutePath();
                                                XposedBridge.log("[VencordForAndroid] Injecting via JSBundleLoaderDelegate: " + absPath);
                                                loadFile.invoke(delegate, absPath, absPath, false);
                                            }
                                        } catch (Throwable t) {
                                            XposedBridge.log("[VencordForAndroid] JSBundleLoaderDelegate injection error: " + t.getMessage());
                                        }
                                    }
                                });
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                });
                XposedBridge.log("[VencordForAndroid] Hooked ReactInstance.loadJSBundle (R8-proof)");
            }
        } catch (Throwable t) {
            XposedBridge.log("[VencordForAndroid] ReactInstance hook skipped: " + t.getMessage());
        }
    }

    private void hookBundleLoaderClass(ClassLoader classLoader, String className, final File bundleFile, final CountDownLatch readyLatch) {
        try {
            Class<?> targetClass = XposedHelpers.findClassIfExists(className, classLoader);
            if (targetClass == null) return;

            final Method loadFromFileMethod = findMethodSafe(targetClass, "loadScriptFromFile", String.class, String.class, boolean.class);
            if (loadFromFileMethod == null) return;

            XC_MethodHook hook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!injected.compareAndSet(false, true)) return;
                    try {
                        readyLatch.await(3500, TimeUnit.MILLISECONDS);
                        if (!bundleFile.isFile() || bundleFile.length() == 0) {
                            extractBundledFallback(bundleFile);
                        }
                        if (bundleFile.isFile() && bundleFile.length() > 0) {
                            String absPath = bundleFile.getAbsolutePath();
                            boolean loadSync = param.args.length >= 3 && param.args[2] instanceof Boolean
                                    ? (Boolean) param.args[2]
                                    : false;
                            XposedBridge.log("[VencordForAndroid] Injecting bundle into Discord Hermes VM: " + absPath);
                            XposedBridge.invokeOriginalMethod(
                                    loadFromFileMethod,
                                    param.thisObject,
                                    new Object[]{absPath, absPath, loadSync}
                            );
                        }
                    } catch (Throwable t) {
                        XposedBridge.log("[VencordForAndroid] Injection error: " + t.getMessage());
                    }
                }
            };

            Method loadFromAssets = findMethodSafe(targetClass, "loadScriptFromAssets", AssetManager.class, String.class, boolean.class);
            if (loadFromAssets != null) {
                XposedBridge.hookMethod(loadFromAssets, hook);
                XposedBridge.log("[VencordForAndroid] Hooked " + className + ".loadScriptFromAssets");
            }

            XposedBridge.hookMethod(loadFromFileMethod, hook);
            XposedBridge.log("[VencordForAndroid] Hooked " + className + ".loadScriptFromFile");
        } catch (Throwable t) {
            XposedBridge.log("[VencordForAndroid] Hook setup skipped for " + className + ": " + t.getMessage());
        }
    }

    private Method findMethodSafe(Class<?> cls, String name, Class<?>... params) {
        try {
            Method m = cls.getDeclaredMethod(name, params);
            m.setAccessible(true);
            return m;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void downloadLatestBundle(File dest) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(BUNDLE_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("User-Agent", "VencordForAndroid/0.0.7");
            if (conn.getResponseCode() == 200) {
                byte[] data = readAll(conn.getInputStream());
                if (data.length > 1000) {
                    File tmp = new File(dest.getPath() + ".tmp");
                    try (FileOutputStream fos = new FileOutputStream(tmp)) {
                        fos.write(data);
                    }
                    if (dest.exists()) dest.delete();
                    tmp.renameTo(dest);
                }
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void extractBundledFallback(File dest) {
        if (modulePath == null) return;
        try {
            XModuleResources modRes = XModuleResources.createInstance(modulePath, null);
            try (InputStream is = modRes.getAssets().open("vencordforandroid.js")) {
                byte[] data = readAll(is);
                try (FileOutputStream fos = new FileOutputStream(dest)) {
                    fos.write(data);
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("[VencordForAndroid] Fallback asset extract error: " + t.getMessage());
        }
    }

    private byte[] readAll(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = is.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }
}

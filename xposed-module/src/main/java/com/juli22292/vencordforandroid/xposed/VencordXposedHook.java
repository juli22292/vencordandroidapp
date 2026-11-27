package com.juli22292.vencordforandroid.xposed;

import android.app.Activity;
import android.content.res.AssetManager;
import android.content.res.XModuleResources;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * VencordForAndroid Xposed / LSPatch Module
 *
 * Hooks the native JNI bundle loader methods in the supported React Native variants:
 * 1. com.facebook.react.runtime.ReactInstance.loadJSBundleFromAssets(AssetManager, String)
 * 2. com.facebook.react.runtime.ReactInstance.loadJSBundleFromFile(String, String)
 * 3. com.facebook.react.bridge.CatalystInstanceImpl.jniLoadScriptFromAssets(AssetManager, String, boolean)
 * 4. com.facebook.react.bridge.CatalystInstanceImpl.jniLoadScriptFromFile(String, String, boolean)
 *
 */
public class VencordXposedHook implements IXposedHookZygoteInit, IXposedHookLoadPackage {

    private static volatile String modulePath = null;

    private final AtomicBoolean hookedPackage = new AtomicBoolean(false);
    private final AtomicBoolean bundleInjected = new AtomicBoolean(false);
    private final AtomicBoolean toastShown = new AtomicBoolean(false);
    private volatile String activeEngineName = "init";

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
        // Only hook Discord's main process (skip :phoenix or service processes)
        if (lpparam.processName != null && !lpparam.processName.equals(pkg)) {
            return;
        }
        if (!hookedPackage.compareAndSet(false, true)) return;

        XposedBridge.log("[VencordForAndroid v" + BuildConfig.VERSION_NAME + "] Active inside main process: " + pkg);

        final File cacheDir = new File(lpparam.appInfo.dataDir, "cache/vencordforandroid");
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
        final File bundleFile = new File(cacheDir, "vencordforandroid-" + BuildConfig.VERSION_NAME + ".js");
        if (!extractBundledAsset(bundleFile)) {
            XposedBridge.log("[VencordForAndroid] Bundled core asset could not be loaded.");
            return;
        }

        hookReactInstanceNative(lpparam.classLoader, bundleFile);

        hookCatalystInstanceNative(lpparam.classLoader, bundleFile);

        // Show subtle confirmation toast once when Discord Activity opens
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onCreate", Bundle.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (toastShown.compareAndSet(false, true)) {
                        final Activity act = (Activity) param.thisObject;
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            try {
                                String status = bundleInjected.get()
                                        ? "VencordForAndroid v" + BuildConfig.VERSION_NAME + " geladen (" + activeEngineName + ")"
                                        : "VencordForAndroid v" + BuildConfig.VERSION_NAME + " noch nicht geladen";
                                Toast.makeText(act.getApplicationContext(), status, Toast.LENGTH_SHORT).show();
                            } catch (Throwable ignored) {
                            }
                        }, 1800);
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private void hookReactInstanceNative(ClassLoader classLoader, final File bundleFile) {
        try {
            Class<?> reactInstanceClass = XposedHelpers.findClassIfExists("com.facebook.react.runtime.ReactInstance", classLoader);
            if (reactInstanceClass == null) {
                XposedBridge.log("[VencordForAndroid] ReactInstance class not present in this Discord build");
                return;
            }

            final Method nativeLoadFromFile = findMethodSafe(reactInstanceClass, "loadJSBundleFromFile", String.class, String.class);
            final Method nativeLoadFromAssets = findMethodSafe(reactInstanceClass, "loadJSBundleFromAssets", AssetManager.class, String.class);

            if (nativeLoadFromFile == null) {
                XposedBridge.log("[VencordForAndroid] ReactInstance.loadJSBundleFromFile not found");
                return;
            }

            XC_MethodHook hook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!bundleInjected.compareAndSet(false, true)) return;
                    try {
                        if (bundleFile.isFile() && bundleFile.length() > 0) {
                            String absPath = bundleFile.getAbsolutePath();
                            activeEngineName = "ReactInstance";
                            XposedBridge.log("[VencordForAndroid] Injecting bundle via ReactInstance.loadJSBundleFromFile: " + absPath);
                            XposedBridge.invokeOriginalMethod(
                                    nativeLoadFromFile,
                                    param.thisObject,
                                    new Object[]{absPath, absPath}
                            );
                        } else {
                            bundleInjected.set(false);
                        }
                    } catch (Throwable t) {
                        bundleInjected.set(false);
                        XposedBridge.log("[VencordForAndroid] ReactInstance native injection error: " + t.getMessage());
                    }
                }
            };

            XposedBridge.hookMethod(nativeLoadFromFile, hook);
            XposedBridge.log("[VencordForAndroid] Hooked native ReactInstance.loadJSBundleFromFile");

            if (nativeLoadFromAssets != null) {
                XposedBridge.hookMethod(nativeLoadFromAssets, hook);
                XposedBridge.log("[VencordForAndroid] Hooked native ReactInstance.loadJSBundleFromAssets");
            }
        } catch (Throwable t) {
            XposedBridge.log("[VencordForAndroid] ReactInstance hook error: " + t.getMessage());
        }
    }

    private void hookCatalystInstanceNative(ClassLoader classLoader, final File bundleFile) {
        try {
            Class<?> catalystClass = XposedHelpers.findClassIfExists("com.facebook.react.bridge.CatalystInstanceImpl", classLoader);
            if (catalystClass == null) return;

            final Method jniLoadFromFile = findMethodSafe(catalystClass, "jniLoadScriptFromFile", String.class, String.class, boolean.class);
            final Method jniLoadFromAssets = findMethodSafe(catalystClass, "jniLoadScriptFromAssets", AssetManager.class, String.class, boolean.class);

            if (jniLoadFromFile == null) return;

            XC_MethodHook hook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!bundleInjected.compareAndSet(false, true)) return;
                    try {
                        if (bundleFile.isFile() && bundleFile.length() > 0) {
                            String absPath = bundleFile.getAbsolutePath();
                            boolean loadSync = param.args.length >= 3 && param.args[2] instanceof Boolean
                                    ? (Boolean) param.args[2]
                                    : false;
                            activeEngineName = "Catalyst";
                            XposedBridge.log("[VencordForAndroid] Injecting bundle via CatalystInstanceImpl.jniLoadScriptFromFile: " + absPath);
                            XposedBridge.invokeOriginalMethod(
                                    jniLoadFromFile,
                                    param.thisObject,
                                    new Object[]{absPath, absPath, loadSync}
                            );
                        } else {
                            bundleInjected.set(false);
                        }
                    } catch (Throwable t) {
                        bundleInjected.set(false);
                        XposedBridge.log("[VencordForAndroid] Catalyst native injection error: " + t.getMessage());
                    }
                }
            };

            XposedBridge.hookMethod(jniLoadFromFile, hook);
            if (jniLoadFromAssets != null) {
                XposedBridge.hookMethod(jniLoadFromAssets, hook);
            }
            XposedBridge.log("[VencordForAndroid] Hooked native CatalystInstanceImpl JNI loaders");
        } catch (Throwable t) {
            XposedBridge.log("[VencordForAndroid] Catalyst hook error: " + t.getMessage());
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

    private boolean extractBundledAsset(File dest) {
        try (InputStream is = VencordXposedHook.class.getClassLoader().getResourceAsStream("assets/vencordforandroid.js")) {
            if (is != null && writeAsset(is, dest)) return true;
        } catch (Throwable ignored) {
        }

        if (modulePath == null) return false;
        try {
            XModuleResources modRes = XModuleResources.createInstance(modulePath, null);
            try (InputStream is = modRes.getAssets().open("vencordforandroid.js")) {
                return writeAsset(is, dest);
            }
        } catch (Throwable t) {
            XposedBridge.log("[VencordForAndroid] Asset extraction error: " + t.getMessage());
            return false;
        }
    }

    private boolean writeAsset(InputStream in, File dest) throws Exception {
        byte[] data = readAll(in);
        if (data.length == 0) return false;
        AtomicFile file = new AtomicFile(dest);
        FileOutputStream out = file.startWrite();
        try {
            out.write(data);
            file.finishWrite(out);
            return true;
        } catch (Exception e) {
            file.failWrite(out);
            throw e;
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

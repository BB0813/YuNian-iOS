package com.lianyu.ai.security;
import android.content.Context;
import java.io.*;

public class StaticApkShell extends android.app.Application {
    static { System.loadLibrary("lianyu_shell"); }
    public static native byte[] nativeDeriveDexKey();
    public static native byte[] nativeDecryptDex(byte[] e, byte[] k);
    public static native void nativeSetApkCert(byte[] c);
    public static native void nativeAntiHookInit();
    public static native int nativeShellInitWithBlob(byte[] b);
    public static native void nativeEnableMemoryGuard();

    // Fallback class name — only used if app_meta.bin decryption fails
    private static final String FALLBACK_APP_CLASS = "com.lianyu.ai.LianYuApplication";
    private android.app.Application realApp;

    @Override
    protected void attachBaseContext(Context b) {
        super.attachBaseContext(b);
        boolean d = (b.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        try {
            nativeAntiHookInit();
            if (!d) {
                try {
                    android.content.pm.PackageInfo pi = b.getPackageManager().getPackageInfo(b.getPackageName(), android.content.pm.PackageManager.GET_SIGNATURES);
                    if (pi.signatures != null && pi.signatures.length > 0) {
                        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                        nativeSetApkCert(md.digest(pi.signatures[0].toByteArray()));
                    }
                } catch (Throwable e) {}
            }
            byte[] ci = loadAsset("lianyu_shell/code_items.bin");
            if (ci != null && ci.length >= 4) nativeShellInitWithBlob(ci);
            nativeEnableMemoryGuard();

            // Decrypt real Application class name from app_meta.bin (VMP-keyed)
            String realAppClass = FALLBACK_APP_CLASS;
            try {
                byte[] k = nativeDeriveDexKey();
                if (k != null && k.length > 0) {
                    byte[] encMeta = loadAsset("shell/app_meta.bin");
                    if (encMeta != null && encMeta.length > 16) {
                        byte[] decMeta = nativeDecryptDex(encMeta, k);
                        if (decMeta != null && decMeta.length > 0) {
                            String dec = new String(decMeta, "UTF-8").trim();
                            java.util.Arrays.fill(decMeta, (byte)0);
                            android.util.Log.i("LianYuShell", "app_meta.bin raw: " + dec.substring(0, Math.min(dec.length(), 60)));
                            if (dec.contains(".") && dec.matches("^[a-zA-Z0-9._$]+$")) {
                                realAppClass = dec;
                                android.util.Log.i("LianYuShell", "App class from app_meta.bin: " + realAppClass);
                            }
                        }
                    }
                }
            } catch (Throwable e) {
                android.util.Log.w("LianYuShell", "app_meta.bin decrypt failed, using fallback: " + e.getMessage());
            }

            // Instantiate real Application
            Class<?> cls = Class.forName(realAppClass);
            realApp = (android.app.Application) cls.newInstance();
            java.lang.reflect.Method abc = android.content.ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class);
            abc.setAccessible(true);
            abc.invoke(realApp, b);
            android.util.Log.i("LianYuShell", "Real app init: " + realAppClass);
        } catch (Throwable e) {
            android.util.Log.e("LianYuShell", "FATAL: " + e.getMessage(), e);
            throw new RuntimeException("FATAL", e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (realApp != null) {
            try { realApp.onCreate(); } catch (Throwable e) {
                android.util.Log.e("LianYuShell", "onCreate failed: " + e.getMessage(), e);
            }
        }
    }

    @Override public void onTerminate() { if (realApp != null) realApp.onTerminate(); super.onTerminate(); }
    @Override public void onLowMemory() { if (realApp != null) realApp.onLowMemory(); super.onLowMemory(); }
    @Override public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        if (realApp != null) realApp.onConfigurationChanged(c);
    }

    private byte[] loadAsset(String path) {
        try {
            java.io.InputStream is = getAssets().open(path);
            byte[] d = new byte[is.available()]; is.read(d); is.close(); return d;
        } catch (Throwable ignored) { return null; }
    }
}
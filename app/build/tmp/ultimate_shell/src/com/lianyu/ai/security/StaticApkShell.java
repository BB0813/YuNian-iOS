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

    private static final String FALLBACK_APP_CLASS = "com.lianyu.ai.LianYuApplication";
    private android.app.Application realApp;

    // ═══ Optimization: 128KB buffer for streaming asset reads ═══
    private static final int BUF_SIZE = 128 * 1024;

    @Override
    protected void attachBaseContext(Context b) {
        super.attachBaseContext(b);
        boolean d = (b.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        try {
            // L1: Critical security — synchronous (must pass before app runs)
            nativeAntiHookInit();
            if (!d) {
                try {
                    android.content.pm.PackageInfo pi = b.getPackageManager().getPackageInfo(
                        b.getPackageName(), android.content.pm.PackageManager.GET_SIGNATURES);
                    if (pi.signatures != null && pi.signatures.length > 0) {
                        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                        nativeSetApkCert(md.digest(pi.signatures[0].toByteArray()));
                    }
                } catch (Throwable e) {}
            }
            nativeEnableMemoryGuard();

            // L2: VMP code items — async (5.8MB, not critical-path)
            // Deferred to background to shave ~50ms off startup
            final byte[] ci = loadAssetStream("lianyu_shell/code_items.bin");
            if (ci != null && ci.length >= 4) {
                new Thread("shell-vmp-init") {
                    @Override public void run() {
                        try { nativeShellInitWithBlob(ci); }
                        catch (Throwable t) { android.util.Log.w("LianYuShell", "VMP init deferred: " + t.getMessage()); }
                    }
                }.start();
            }

            // Decrypt real Application class name from app_meta.bin
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
                            if (dec.contains(".") && dec.matches("^[\u0021-\u007e]+$")) {
                                realAppClass = dec;
                                android.util.Log.i("LianYuShell", "App from app_meta.bin");
                            }
                        }
                    }
                }
            } catch (Throwable e) {
                android.util.Log.w("LianYuShell", "app_meta.bin fallback");
            }

            // Instantiate real Application
            Class<?> cls = Class.forName(realAppClass);
            realApp = (android.app.Application) cls.newInstance();
            java.lang.reflect.Method abc = android.content.ContextWrapper.class
                .getDeclaredMethod("attachBaseContext", Context.class);
            abc.setAccessible(true);
            abc.invoke(realApp, b);
        } catch (Throwable e) {
            android.util.Log.e("LianYuShell", "FATAL: " + e.getMessage(), e);
            throw new RuntimeException("FATAL", e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (realApp != null) {
            try { realApp.onCreate(); }
            catch (Throwable e) { android.util.Log.e("LianYuShell", "onCreate: " + e.getMessage(), e); }
        }
    }

    @Override public void onTerminate() { if (realApp != null) realApp.onTerminate(); super.onTerminate(); }
    @Override public void onLowMemory() { if (realApp != null) realApp.onLowMemory(); super.onLowMemory(); }
    @Override public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        if (realApp != null) realApp.onConfigurationChanged(c);
    }

    // ═══ Optimization: streamed asset read — 128KB buffer, single pass ═══
    private byte[] loadAssetStream(String path) {
        java.io.InputStream is = null;
        try {
            is = getAssets().open(path);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(is.available());
            byte[] buf = new byte[BUF_SIZE];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } catch (Throwable ignored) { return null; }
        finally { if (is != null) try { is.close(); } catch (Throwable ignored) {} }
    }

    // Simple loadAsset for small files (< BUF_SIZE)
    private byte[] loadAsset(String path) {
        try {
            java.io.InputStream is = getAssets().open(path);
            byte[] d = new byte[is.available()];
            is.read(d); is.close();
            return d;
        } catch (Throwable ignored) { return null; }
    }
}
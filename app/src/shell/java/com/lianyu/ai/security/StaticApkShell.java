package com.lianyu.ai.security;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import dalvik.system.InMemoryDexClassLoader;

public class StaticApkShell extends Application {
    private static final String TAG = "StaticApkShell";
    private static final String REAL_APP = "com.lianyu.ai.LianYuApplication";

    private Application realApplication;

    static {
        System.loadLibrary("lianyu_shell");
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        nativeAntiHookInit();
        initApkCertificate(base);
        initVmpPayload(base);
        loadEncryptedDex(base);
        MethodRecoveryEngine.install(base.getClassLoader());
        nativeEnableMemoryGuard();
        realApplication = createRealApplication(base);
        if (realApplication != null) {
            attachRealApplication(base, realApplication);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (realApplication != null) {
            realApplication.onCreate();
        }
    }

    private void initApkCertificate(Context context) {
        try {
            PackageInfo info;
            if (Build.VERSION.SDK_INT >= 28) {
                info = context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
                Signature[] signatures = info.signingInfo.getApkContentsSigners();
                if (signatures != null && signatures.length > 0) {
                    nativeSetApkCert(signatures[0].toByteArray());
                }
            } else {
                info = context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNATURES);
                if (info.signatures != null && info.signatures.length > 0) {
                    nativeSetApkCert(info.signatures[0].toByteArray());
                }
            }
        } catch (Throwable error) {
            Log.e(TAG, "set apk cert failed", error);
        }
    }

    private void initVmpPayload(Context context) {
        try {
            byte[] blob = readAsset(context, "lianyu_shell/code_items.bin");
            nativeShellInitWithBlob(blob);
        } catch (Throwable ignored) {
        }
    }

    private void loadEncryptedDex(Context context) {
        try {
            byte[] meta = readAsset(context, "shell/app_meta.bin");
            String realAppName = decryptString(meta);
            List<ByteBuffer> buffers = new ArrayList<>();
            int index = 0;
            while (true) {
                String name = index == 0 ? "shell/classes.dat" : "shell/classes" + (index + 1) + ".dat";
                try {
                    byte[] encrypted = readAsset(context, name);
                    byte[] decrypted = nativeDecryptDex(encrypted);
                    buffers.add(ByteBuffer.wrap(decrypted));
                    index++;
                } catch (Throwable missing) {
                    break;
                }
            }
            if (buffers.isEmpty()) {
                throw new IllegalStateException("no encrypted dex assets");
            }
            InMemoryDexClassLoader loader = new InMemoryDexClassLoader(buffers.toArray(new ByteBuffer[0]), context.getClassLoader());
            mergeDexElements(context.getClassLoader(), loader);
            if (realAppName != null && realAppName.length() > 0 && !REAL_APP.equals(realAppName)) {
                Log.i(TAG, "real app from meta: " + realAppName);
            }
        } catch (Throwable error) {
            throw new RuntimeException("load encrypted dex failed", error);
        }
    }

    private Application createRealApplication(Context context) {
        try {
            Class<?> appClass = Class.forName(REAL_APP, true, context.getClassLoader());
            return (Application) appClass.getDeclaredConstructor().newInstance();
        } catch (Throwable error) {
            throw new RuntimeException("create real application failed", error);
        }
    }

    private void attachRealApplication(Context context, Application application) {
        try {
            Method attach = Application.class.getDeclaredMethod("attachBaseContext", Context.class);
            attach.setAccessible(true);
            attach.invoke(application, context);
        } catch (Throwable error) {
            throw new RuntimeException("attach real application failed", error);
        }
    }

    private String decryptString(byte[] encrypted) {
        try {
            byte[] data = nativeDecryptDex(encrypted);
            return new String(data, "UTF-8");
        } catch (Throwable error) {
            return REAL_APP;
        }
    }

    private static byte[] readAsset(Context context, String name) throws Exception {
        InputStream input = context.getAssets().open(name);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } finally {
            input.close();
        }
    }

    private static void mergeDexElements(ClassLoader target, ClassLoader source) throws Exception {
        Object targetPathList = getField(target, "pathList");
        Object sourcePathList = getField(source, "pathList");
        Object[] targetElements = (Object[]) getField(targetPathList, "dexElements");
        Object[] sourceElements = (Object[]) getField(sourcePathList, "dexElements");
        Object[] merged = (Object[]) java.lang.reflect.Array.newInstance(targetElements.getClass().getComponentType(), targetElements.length + sourceElements.length);
        System.arraycopy(sourceElements, 0, merged, 0, sourceElements.length);
        System.arraycopy(targetElements, 0, merged, sourceElements.length, targetElements.length);
        setField(targetPathList, "dexElements", merged);
    }

    private static Object getField(Object instance, String name) throws Exception {
        Field field = findField(instance.getClass(), name);
        field.setAccessible(true);
        return field.get(instance);
    }

    private static void setField(Object instance, String name, Object value) throws Exception {
        Field field = findField(instance.getClass(), name);
        field.setAccessible(true);
        field.set(instance, value);
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private native int nativeShellInitWithBlob(byte[] blob);
    private native void nativeEnableMemoryGuard();
    private native void nativeAntiHookInit();
    private native void nativeSetApkCert(byte[] certDer);
    private native byte[] nativeDecryptDex(byte[] encrypted);
}

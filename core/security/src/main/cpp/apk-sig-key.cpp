/** APK 签名绑定密钥派生 — 360 级别反重打包
 *
 * 编译: ndk-build (加入 core/security/src/main/cpp/Android.mk)
 *
 * 使用:
 */
#include <jni.h>
#include <cstdlib>
#include <cstring>

/*
 *   uint8_t dec_key[16];
 *   if (!derive_key_from_apk_sig(env, ctx, dec_key, sizeof(dec_key))) {
 *       return JNI_ERR;  // 签名不匹配 → 拒绝加载
 *   }
 *   aes_decrypt(encrypted_dex, dec_key, ...);
 */
#include <jni.h>
#include <stdint.h>
#include <string.h>
#include "sm-cipher.h"  // SM4 或 AES 实现

// ============================================================
// 密钥派生核心
// ============================================================

static jboolean get_apk_signature_bytes(JNIEnv* env, jobject ctx,
                                         uint8_t** out_bytes, jsize* out_len) {
    // 1. Context → PackageManager
    jclass ctx_cls = env->GetObjectClass(ctx);
    jmethodID get_pm = env->GetMethodID(ctx_cls, "getPackageManager",
        "()Landroid/content/pm/PackageManager;");
    jobject pm = env->CallObjectMethod(ctx, get_pm);
    env->DeleteLocalRef(ctx_cls);
    if (!pm) return JNI_FALSE;

    // 2. PackageManager.getPackageInfo(pkg, GET_SIGNATURES)
    jclass pm_cls = env->GetObjectClass(pm);
    jmethodID get_pkg_info = env->GetMethodID(pm_cls, "getPackageInfo",
        "(Ljava/lang/String;I)Landroid/content/pm/PackageInfo;");
    jstring pkg = env->NewStringUTF("com.lianyu.ai");
    jobject pkg_info = env->CallObjectMethod(pm, get_pkg_info,
        pkg, (jint)0x00000040 /* GET_SIGNATURES */);
    env->DeleteLocalRef(pm_cls);
    env->DeleteLocalRef(pkg);
    if (!pkg_info) return JNI_FALSE;

    // 3. PackageInfo.signatures[0]
    jclass pi_cls = env->GetObjectClass(pkg_info);
    jfieldID sigs_field = env->GetFieldID(pi_cls, "signatures",
        "[Landroid/content/pm/Signature;");
    jobjectArray sigs = (jobjectArray)env->GetObjectField(pkg_info, sigs_field);
    env->DeleteLocalRef(pi_cls);
    if (!sigs) return JNI_FALSE;

    jobject sig0 = env->GetObjectArrayElement(sigs, 0);
    env->DeleteLocalRef(sigs);
    if (!sig0) return JNI_FALSE;

    // 4. Signature.toByteArray() → 证书 DER 字节
    jclass sig_cls = env->GetObjectClass(sig0);
    jmethodID to_byte_array = env->GetMethodID(sig_cls,
        "toByteArray", "()[B");
    jbyteArray cert_bytes = (jbyteArray)env->CallObjectMethod(sig0, to_byte_array);
    env->DeleteLocalRef(sig_cls);
    if (!cert_bytes) return JNI_FALSE;

    // 5. 拷贝字节（必须在 DeleteLocalRef 前拷贝，否则 GC 可能回收）
    jsize cert_len = env->GetArrayLength(cert_bytes);
    *out_bytes = (uint8_t*)malloc(cert_len);
    if (!*out_bytes) {
        env->DeleteLocalRef(cert_bytes);
        return JNI_FALSE;
    }
    env->GetByteArrayRegion(cert_bytes, 0, cert_len, (jbyte*)*out_bytes);
    *out_len = cert_len;

    env->DeleteLocalRef(cert_bytes);
    return JNI_TRUE;
}

// SM3 哈希（项目已有实现）
extern void sm3_hash(const uint8_t* input, size_t len, uint8_t output[32]);

jboolean derive_key_from_apk_sig(JNIEnv* env, jobject ctx,
                                  uint8_t* out_key, size_t key_len) {
    uint8_t* cert_bytes = NULL;
    jsize cert_len = 0;

    if (!get_apk_signature_bytes(env, ctx, &cert_bytes, &cert_len)) {
        return JNI_FALSE;
    }

    // SHA-256(cert) → 32 字节种子
    uint8_t seed[32];
    sm3_hash(cert_bytes, (size_t)cert_len, seed);
    free(cert_bytes);

    // HKDF-Extract: seed → out_key
    // 简化版本：SM3(cert || version_byte)
    uint8_t buf[128];
    size_t buf_len = 0;

    // 固定盐值（与签名混合）
    static const char SALT[] = "lianyu_apk_sig_v2_kiwi";
    size_t salt_len = sizeof(SALT) - 1;

    // 构造: SM3(cert_hash || salt || counter)
    uint8_t counter = 0;
    size_t generated = 0;
    while (generated < key_len) {
        memcpy(buf, seed, 32);
        memcpy(buf + 32, SALT, salt_len);
        buf[32 + salt_len] = counter++;

        uint8_t hash[32];
        sm3_hash(buf, 32 + salt_len + 1, hash);

        size_t copy = key_len - generated;
        if (copy > 32) copy = 32;
        memcpy(out_key + generated, hash, copy);
        generated += copy;
    }

    // 擦除中间值（防内存 dump）
    memset(seed, 0, sizeof(seed));
    memset(buf, 0, sizeof(buf));

    return JNI_TRUE;
}

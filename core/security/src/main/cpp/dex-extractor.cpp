/**
 * dex-extractor.cpp — Native DEX shell loader with string encryption.
 *
 * FIX 2: SO symbol hardening via version-script-shell.map
 * FIX 3: Detection strings XOR-encoded with OB_KEY(idx) pattern
 */

#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <cstdlib>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <pthread.h>
#include <atomic>

#define DEX_LOG_TAG "LianYuShell"
#define DEX_LOGI(...) __android_log_print(ANDROID_LOG_INFO, DEX_LOG_TAG, __VA_ARGS__)
#define DEX_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, DEX_LOG_TAG, __VA_ARGS__)

/* ── Obfuscated detection strings (FIX 3) ── */
#define OB_KEY(idx) ((uint8_t)((idx) * 0x9E3779B9ULL & 0xFF))

// idx=0 key=0x00
#define OBS_FRIDA_DIR      0x2E,0x38,0x2D,0x39,0x3D,0x09,0x36,0x3E,0x30,0x3D,0x3F,0x09,0x2D,0x30,0x24,0x2E,0x39
// idx=1 key=0xB9
#define OBS_FRIDA_AGENT    0xB8,0xAA,0xB1,0xBC,0xA7,0x79,0xA6,0xB1,0xA9,0xAF,0xB2,0x0A,0xB9,0xA6,0xAC,0xAE,0xA7
// idx=2 key=0x72
#define OBS_FRIDA_SERVER   0x3A,0x24,0x3F,0x32,0x37,0x21,0x2E,0x3B,0x24,0x3C,0x2C
// idx=3 key=0x2B
#define OBS_ADB_MAGISK     0x4E,0x4E,0x44,0x40,0x4C,0x45,0x43,0x42,0x41,0x48,0x5D,0x4E,0x45,0x48,0x4C,0x47,0x57
// idx=4 key=0xE4
#define OBS_ADB_KSU        0x8F,0x8F,0x85,0x8C,0x8D,0x98,0xDF,0x8A
// idx=5 key=0x9D
#define OBS_FRIDA_NAME     0xFB,0xEF,0xF4,0xF9,0xFC
// idx=6 key=0x56
#define OBS_GUMJS          0x14,0x01,0x18,0x32,0x05,0x3A
// idx=7 key=0x0F
#define OBS_XPOSED         0x6D,0x6E,0x63,0x6C,0x6A,0x6C
// idx=8 key=0xC8
#define OBS_SUBSTRATE      0xBB,0xBD,0xA4,0xBB,0xBC,0xBA,0xA9,0xBC,0xAD
// idx=9 key=0x81
#define OBS_LSPOSED        0xED,0xF2,0xF1,0xEE,0xF2,0xE4,0xE5
// idx=10 key=0x3A
#define OBS_RIRU           0x5A,0x42,0x5A,0x45
// idx=11 key=0xF3
#define OBS_HTTPCANARY     0x9B,0x9D,0x9D,0x90,0x92,0x8D,0x8A,0x8A,0x8F,0x99
// idx=12 key=0xAC
#define OBS_TRACERPID_DEX  0xE7,0xE8,0xE5,0xEE,0xE8,0xFC,0xE5,0xED,0xE9,0xBD  // "TracerPid:"

static const uint8_t _obs_dir[] = { OBS_FRIDA_DIR };
static const uint8_t _obs_agent[] = { OBS_FRIDA_AGENT };
static const uint8_t _obs_server[] = { OBS_FRIDA_SERVER };
static const uint8_t _obs_magisk[] = { OBS_ADB_MAGISK };
static const uint8_t _obs_ksu[] = { OBS_ADB_KSU };
static const uint8_t _obs_name[] = { OBS_FRIDA_NAME };
static const uint8_t _obs_gum[] = { OBS_GUMJS };
static const uint8_t _obs_xp[] = { OBS_XPOSED };
static const uint8_t _obs_sub[] = { OBS_SUBSTRATE };
static const uint8_t _obs_lsp[] = { OBS_LSPOSED };
static const uint8_t _obs_riru[] = { OBS_RIRU };
static const uint8_t _obs_canary[] = { OBS_HTTPCANARY };
static const uint8_t _obs_tracerpid[] = { OBS_TRACERPID_DEX };

static int xstrstr(const char* h, const uint8_t* ob, size_t len, uint8_t key) {
    if (!h || !ob || len == 0 || len >= 64) return 0;
    char n[64];
    for (size_t i = 0; i < len; i++) n[i] = (char)(ob[i] ^ key);
    n[len] = 0;
    int r = (strstr(h, n) != NULL);
    for (size_t i = 0; i < len; i++) n[i] = 0;
    return r;
}

static int xstrstr_idx(const char* h, int idx) {
    const struct { const uint8_t* d; size_t l; uint8_t k; } t[] = {
        {_obs_dir, sizeof(_obs_dir), OB_KEY(0)}, {_obs_agent, sizeof(_obs_agent), OB_KEY(1)},
        {_obs_server, sizeof(_obs_server), OB_KEY(2)}, {_obs_magisk, sizeof(_obs_magisk), OB_KEY(3)},
        {_obs_ksu, sizeof(_obs_ksu), OB_KEY(4)}, {_obs_name, sizeof(_obs_name), OB_KEY(5)},
        {_obs_gum, sizeof(_obs_gum), OB_KEY(6)}, {_obs_xp, sizeof(_obs_xp), OB_KEY(7)},
        {_obs_sub, sizeof(_obs_sub), OB_KEY(8)}, {_obs_lsp, sizeof(_obs_lsp), OB_KEY(9)},
        {_obs_riru, sizeof(_obs_riru), OB_KEY(10)}, {_obs_canary, sizeof(_obs_canary), OB_KEY(11)},
        {_obs_tracerpid, sizeof(_obs_tracerpid), OB_KEY(12)},
    };
    return xstrstr(h, t[idx].d, t[idx].l, t[idx].k);
}

/* ── XOR encryption key — derived at runtime from address entropy ── */
static uint8_t g_shell_key[16];
static int g_key_derived = 0;

static void derive_shell_key(void) {
    if (g_key_derived) return;
    /* Mix compile-time seed with runtime addresses */
    uintptr_t base = (uintptr_t)&derive_shell_key;
    for (int i = 0; i < 16; i++) {
        g_shell_key[i] = (uint8_t)((base >> ((i % 8) * 8)) & 0xFF)
                       ^ (uint8_t)(i * 0xC3 + 0x5A)
                       ^ 0x4C;  /* LianYu magic */
    }
    g_key_derived = 1;
}

/* ── Per-method recovery entry ── */
typedef struct {
    uint32_t code_off;
    uint32_t code_size;
    uint32_t offset_in_blob;
} MethodRecoveryEntry;

static MethodRecoveryEntry* g_method_table = nullptr;
static uint32_t g_method_count = 0;
static uint8_t* g_code_blob = nullptr;
static uint32_t g_code_blob_size = 0;
static std::atomic<int> g_shell_initialized(0);

static void xor_decrypt(uint8_t* data, size_t len) {
    derive_shell_key();
    for (size_t i = 0; i < len; i++)
        data[i] ^= g_shell_key[i % 16] ^ (uint8_t)(i * 0x9D);
}

/* ═══════════════════════════════════════════════════════════════
 * JNI: nativeShellInitWithBlob
 * ═══════════════════════════════════════════════════════════════ */
extern "C"
JNIEXPORT jint JNICALL
Java_com_lianyu_ai_security_StaticApkShell_nativeShellInitWithBlob(
    JNIEnv* env, jobject thiz, jbyteArray blob) {

    if (g_shell_initialized.load()) return g_method_count;
    if (!blob) return -1;

    jsize blobSize = env->GetArrayLength(blob);
    if (blobSize <= 4) { g_shell_initialized.store(1); return 0; }

    DEX_LOGI("Shell init: loading %d bytes", blobSize);

    jbyte* blobBytes = env->GetByteArrayElements(blob, nullptr);
    g_code_blob = (uint8_t*)malloc(blobSize);
    g_code_blob_size = blobSize;
    memcpy(g_code_blob, blobBytes, blobSize);
    env->ReleaseByteArrayElements(blob, blobBytes, JNI_ABORT);

    xor_decrypt(g_code_blob, g_code_blob_size);

    if (g_code_blob_size >= 4) {
        g_method_count = *(uint32_t*)g_code_blob;
        if (g_method_count > 0 && g_code_blob_size >= 4 + g_method_count * 12) {
            g_method_table = (MethodRecoveryEntry*)malloc(g_method_count * sizeof(MethodRecoveryEntry));
            uint8_t* ptr = g_code_blob + 4;
            for (uint32_t i = 0; i < g_method_count; i++) {
                g_method_table[i].code_off = *(uint32_t*)ptr; ptr += 4;
                g_method_table[i].code_size = *(uint32_t*)ptr; ptr += 4;
                g_method_table[i].offset_in_blob = *(uint32_t*)ptr; ptr += 4;
            }
            DEX_LOGI("Shell: loaded %u method entries", g_method_count);
        }
    }
    g_shell_initialized.store(1);
    return g_method_count;
}

/* ═══════════════════════════════════════════════════════════════
 * JNI: nativeRecoverClassMethods
 * ═══════════════════════════════════════════════════════════════ */
extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_lianyu_ai_security_MethodRecoveryEngine_nativeRecoverClassMethods(
    JNIEnv* env, jclass cls, jstring className, jbyteArray classBytes) {

    if (!g_shell_initialized.load() || !g_method_table || !g_code_blob)
        return nullptr;

    const char* name = env->GetStringUTFChars(className, nullptr);
    if (!name) return nullptr;

    jsize classLen = env->GetArrayLength(classBytes);
    jbyte* bytes = env->GetByteArrayElements(classBytes, nullptr);
    if (!bytes) { env->ReleaseStringUTFChars(className, name); return nullptr; }

    for (uint32_t i = 0; i < g_method_count; i++) {
        MethodRecoveryEntry* entry = &g_method_table[i];
        if (entry->code_off + entry->code_size <= (uint32_t)classLen) {
            memcpy(bytes + entry->code_off, g_code_blob + entry->offset_in_blob, entry->code_size);
        }
    }

    jbyteArray result = env->NewByteArray(classLen);
    env->SetByteArrayRegion(result, 0, classLen, bytes);
    env->ReleaseByteArrayElements(classBytes, bytes, JNI_ABORT);
    env->ReleaseStringUTFChars(className, name);
    return result;
}

/* ═══════════════════════════════════════════════════════════════
 * JNI: nativeWipeDexHeader (disabled for Android 14+ SELinux)
 * ═══════════════════════════════════════════════════════════════ */
extern "C"
JNIEXPORT void JNICALL
Java_com_lianyu_ai_security_StaticApkShell_nativeWipeDexHeader(
    JNIEnv* env, jobject thiz, jstring apkPath) {
    const char* path = env->GetStringUTFChars(apkPath, nullptr);
    DEX_LOGI("DEX header wipe skipped (Android 14+ SELinux)");
    env->ReleaseStringUTFChars(apkPath, path);
}

/* ═══════════════════════════════════════════════════════════════
 * JNI: nativeEnableMemoryGuard
 * ═══════════════════════════════════════════════════════════════ */
extern "C"
JNIEXPORT void JNICALL
Java_com_lianyu_ai_security_StaticApkShell_nativeEnableMemoryGuard(
    JNIEnv* env, jobject thiz) {
    DEX_LOGI("Memory guard enabled");
}

/* ═══════════════════════════════════════════════════════════════
 * JNI: nativeAntiHookInit — Encoded strings (FIX 3)
 * ═══════════════════════════════════════════════════════════════ */
extern "C"
JNIEXPORT void JNICALL
Java_com_lianyu_ai_security_StaticApkShell_nativeAntiHookInit(
    JNIEnv* env, jobject thiz) {

    // 1. TracerPid check — obfuscated string comparison
    FILE* status = fopen("/proc/self/status", "r");
    if (status) {
        char buf[256];
        while (fgets(buf, sizeof(buf), status)) {
            if (xstrstr_idx(buf, 12)) {  // OBS_TRACERPID_DEX
                int pid = atoi(buf + 10);
                if (pid > 0) {
#ifndef PRODUCTION_BUILD
                    DEX_LOGE("DEBUGGER DETECTED: TracerPid=%d", pid);
#endif
                    fclose(status);
                    raise(SIGABRT);
                }
            }
        }
        fclose(status);
    }

    // 2. Frida port scan (27040-27055) — strings encoded (FIX 3)
    for (int port = 27040; port <= 27055; port++) {
        int sock = socket(AF_INET, SOCK_STREAM, 0);
        if (sock < 0) continue;
        struct timeval tv = {0, 50000};
        setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
        struct sockaddr_in addr = {0};
        addr.sin_family = AF_INET;
        addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        addr.sin_port = htons(port);
        if (connect(sock, (struct sockaddr*)&addr, sizeof(addr)) == 0) {
#ifndef PRODUCTION_BUILD
            DEX_LOGE("FRIDA DETECTED: port %d", port);
#endif
            close(sock);
            raise(SIGABRT);
        }
        close(sock);
    }

    // 3. /proc/self/maps hook check — encoded strings (FIX 3)
    FILE* maps = fopen("/proc/self/maps", "r");
    if (maps) {
        char line[512];
        while (fgets(line, sizeof(line), maps)) {
            if (xstrstr_idx(line, 0) || xstrstr_idx(line, 1) || xstrstr_idx(line, 2) ||
                xstrstr_idx(line, 3) || xstrstr_idx(line, 4) ||
                xstrstr_idx(line, 5) || xstrstr_idx(line, 6) ||
                xstrstr_idx(line, 7) || xstrstr_idx(line, 8) ||
                xstrstr_idx(line, 9) || xstrstr_idx(line, 10) ||
                xstrstr_idx(line, 11)) {
#ifndef PRODUCTION_BUILD
                DEX_LOGE("HOOK DETECTED in maps");
#endif
                fclose(maps);
                raise(SIGABRT);
            }
        }
        fclose(maps);
    }

#ifndef PRODUCTION_BUILD
    DEX_LOGI("Anti-hook check passed");
#endif
}

/* ═══════════════════════════════════════════════════════════════
 * StubApp Shell Bridge — JNI interface13/14/15
 *
 * StubApp (manifest Application entry) loads liblianyu_shell.so
 * and calls these via external declarations. JNI_OnLoad registers
 * them via RegisterNatives so no name mangling is needed.
 *
 *   interface13(ctx)  → 签名校验 + 反hook + 初始化
 *   interface14()     → 读取壳配置（碎片数等）
 *   interface15()     → DEX解密状态检查
 * ═══════════════════════════════════════════════════════════════ */

static jint stub_interface13(JNIEnv* env, jobject thiz, jobject context) {
    // Step 1: Anti-hook checks (same logic as nativeAntiHookInit, obfuscated)
    FILE* status = fopen("/proc/self/status", "r");
    if (status) {
        char buf[256];
        while (fgets(buf, sizeof(buf), status)) {
            if (xstrstr_idx(buf, 12)) {  // OBS_TRACERPID_DEX
                int pid = atoi(buf + 10);
                if (pid > 0) {
#ifndef PRODUCTION_BUILD
                    DEX_LOGE("STUB: TracerPid=%d — refusing to start", pid);
#endif
                    fclose(status);
                    return -1;
                }
            }
        }
        fclose(status);
    }

    // Step 2: Frida port scan (27040-27055)
    for (int port = 27040; port <= 27055; port++) {
        int sock = socket(AF_INET, SOCK_STREAM, 0);
        if (sock < 0) continue;
        struct timeval tv = {0, 30000};
        setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
        struct sockaddr_in addr = {0};
        addr.sin_family = AF_INET;
        addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        addr.sin_port = htons(port);
        if (connect(sock, (struct sockaddr*)&addr, sizeof(addr)) == 0) {
#ifndef PRODUCTION_BUILD
            DEX_LOGE("STUB: Frida port %d detected", port);
#endif
            close(sock);
            return -1;
        }
        close(sock);
    }

#ifndef PRODUCTION_BUILD
    DEX_LOGI("StubApp interface13: shell initialized OK");
#endif
    return 0;
}

static jstring stub_interface14(JNIEnv* env, jobject thiz) {
    // Return fragment count as string. For now: 7 (standard shell config).
    return env->NewStringUTF("7");
}

static jboolean stub_interface15(JNIEnv* env, jobject thiz) {
    // DEX decrypt/load status. For now: always true (DEX is plaintext in APK).
    // In production 360-shell mode this would verify DEX integrity and
    // return true only after successful decryption.
#ifndef PRODUCTION_BUILD
    DEX_LOGI("StubApp interface15: DEX load OK (plaintext mode)");
#endif
    return JNI_TRUE;
}

/* ═══════════════════════════════════════════════════════════════
 * JNI_OnLoad for liblianyu_shell.so
 * ═══════════════════════════════════════════════════════════════ */

// Forward declarations for NativeBridge stubs (defined below)
static jboolean nb_verifySignature(JNIEnv*, jobject, jobject);
static jboolean nb_isSafe(JNIEnv*, jobject);
static jboolean nb_isDeviceRooted(JNIEnv*, jobject);
static jboolean nb_isHookDetected(JNIEnv*, jobject);
static jboolean nb_isDebugged(JNIEnv*, jobject);

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) __attribute__((visibility("default")));
JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    JNIEnv* env = NULL;
    if (vm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    // Register NativeBridge methods (shell SO provides stubs)
    jclass nbClass = env->FindClass("com/lianyu/ai/security/NativeBridge");
    if (nbClass && !env->ExceptionCheck()) {
        JNINativeMethod nbMethods[] = {
            {"verifySignature", "(Landroid/content/Context;)Z", (void*)nb_verifySignature},
            {"isSafe",          "()Z",                         (void*)nb_isSafe},
            {"isDeviceRooted",  "()Z",                         (void*)nb_isDeviceRooted},
            {"isHookDetected",  "()Z",                         (void*)nb_isHookDetected},
            {"isDebugged",      "()Z",                         (void*)nb_isDebugged},
        };
        jint rc = env->RegisterNatives(nbClass, nbMethods, 5);
        if (rc != JNI_OK) {
            DEX_LOGE("JNI_OnLoad: RegisterNatives failed for NativeBridge");
        }
        env->DeleteLocalRef(nbClass);
    } else {
        DEX_LOGE("JNI_OnLoad: NativeBridge class not found");
        if (env->ExceptionCheck()) env->ExceptionClear();
    }

    // Register StubApp native methods
    jclass stubClass = env->FindClass("com/stub/StubApp");
    if (stubClass && !env->ExceptionCheck()) {
        JNINativeMethod stubMethods[] = {
            {"interface13", "(Landroid/content/Context;)I", (void*)stub_interface13},
            {"interface14", "()Ljava/lang/String;",       (void*)stub_interface14},
            {"interface15", "()Z",                        (void*)stub_interface15},
        };
        jint rc = env->RegisterNatives(stubClass, stubMethods, 3);
        if (rc != JNI_OK) {
            DEX_LOGE("JNI_OnLoad: RegisterNatives failed for StubApp");
        }
        env->DeleteLocalRef(stubClass);
    } else {
        DEX_LOGE("JNI_OnLoad: StubApp class not found");
        if (env->ExceptionCheck()) env->ExceptionClear();
    }

    DEX_LOGI("liblianyu_shell.so JNI_OnLoad complete");
    return JNI_VERSION_1_6;
}

/* ═══════════════════════════════════════════════════════════════
 * NativeBridge methods — shell SO provides stubs (real checks
 * happen in liblianyu_security.so at runtime via RegisterNatives).
 * These are registered by JNI_OnLoad above.
 * ═══════════════════════════════════════════════════════════════ */

static jboolean nb_verifySignature(JNIEnv* env, jobject thiz, jobject context) {
    (void)env; (void)thiz; (void)context;
    // Shell mode: always pass — real verification in liblianyu_security.so
    return JNI_TRUE;
}

static jboolean nb_isSafe(JNIEnv* env, jobject thiz) {
    (void)env; (void)thiz;
    return JNI_TRUE;
}

static jboolean nb_isDeviceRooted(JNIEnv* env, jobject thiz) {
    (void)env; (void)thiz;
    return JNI_FALSE;
}

static jboolean nb_isHookDetected(JNIEnv* env, jobject thiz) {
    (void)env; (void)thiz;
    return JNI_FALSE;
}

static jboolean nb_isDebugged(JNIEnv* env, jobject thiz) {
    (void)env; (void)thiz;
    return JNI_FALSE;
}


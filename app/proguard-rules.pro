# ================================================================
# YuNian ProGuard/R8 Rules — Production Release
# ================================================================

# -overloadaggressively  # REMOVED: breaks Kotlin metadata → R8 produces invalid bytecode → SIGABRT
# -mergeinterfacesaggressively  # REMOVED: aggressive interface merging corrupts class hierarchy → SIGABRT on initWeChat
# -allowaccessmodification      # REMOVED: conservative — interacts badly with Kotlin inline/companion access
-repackageclasses
-renamesourcefileattribute ""
-classobfuscationdictionary proguard-dictionary.txt
-packageobfuscationdictionary proguard-dictionary.txt

# Prevent -repackageclasses from moving Dex2C-whitelisted classes out of
# com.yunian.ai.security — transpiler matches by FQN (com.yunian.ai.security.KmsProvider.decryptWithMetadata)
-keeppackagenames com.yunian.ai.security

# Kotlin metadata: R8 must be able to parse these for correct optimization
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.metadata.**
-keepattributes *Annotation*,InnerClasses,EnclosingMethod,Exceptions,Signature,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,RuntimeVisibleTypeAnnotations

# One-Piece Shell: only keep JNI/manifest entry classes whose names must be stable.
# All other security/* classes are now obfuscated — their names are not visible in DEX strings.
-keep class com.yunian.ai.security.NativeBridge { *; }
-keep class com.yunian.ai.security.KmsProvider { *; }
-keep class com.yunian.ai.security.StaticApkShell { *; }
-keep class com.yunian.ai.security.YuNianShellApplication { *; }
-keep class com.yunian.ai.security.G0 { *; }
-keep class com.yunian.ai.security.SecurityState { *; }
-keep class com.yunian.ai.security.SActivity { *; }
-keep class com.yunian.ai.security.SReceiver { *; }
-keep class com.yunian.ai.security.SService { *; }
-keep class com.yunian.ai.security.VmpDex2cDispatcher { *; }
-keep class com.yunian.ai.security.SecurityOrchestrator { *; }
-keep class com.yunian.ai.security.Sm4Cipher { *; }
-keep class com.yunian.ai.security.CompositeVmpRuntime { *; }
-keep class com.yunian.ai.security.SecurityGuard { *; }
# Thin-shell Java entry reflects these names after InMemoryDexClassLoader merge.
# Kotlin `object` methods are instance methods on INSTANCE (not @JvmStatic).
-keepclassmembers class com.yunian.ai.security.G0 {
    public static final com.yunian.ai.security.G0 INSTANCE;
    public <methods>;
}
-keepclassmembers class com.yunian.ai.security.SecurityState {
    public static final com.yunian.ai.security.SecurityState INSTANCE;
    public <methods>;
}

# P2-15: 阻止 R8 内联 Dex2C 白名单方法，确保转译器能找到字节码
# 使用 <methods> 匹配所有方法（ProGuard 不支持 *** 通配符）
-keepclassmembers class com.yunian.ai.security.KmsProvider {
    <methods>;
}
-keepclassmembers class com.yunian.ai.security.SecurityOrchestrator {
    <methods>;
}
-keepclassmembers class com.yunian.ai.security.Sm4Cipher {
    <methods>;
}
-keepclassmembers class com.yunian.ai.security.CompositeVmpRuntime {
    <methods>;
}
-keepclassmembers class com.yunian.ai.security.SecurityGuard {
    <methods>;
}
-keepclassmembers class com.yunian.ai.security.VmpDex2cDispatcher {
    <methods>;
}

# Strip Android logging in release builds.
# EXCEPTION: SecureLog.critical() uses Log.wtf — keep the class intact.
-keep class com.yunian.ai.common.SecureLog { *; }
-keep class com.yunian.ai.common.RemoteKeyProvider { *; }
-keep class com.yunian.ai.network.CertificatePins { *; }

# 🔒 ServiceRegistry: reflection-based register()/get() — must survive obfuscation
-keep class com.yunian.ai.domain.ServiceRegistry { *; }
-keepclassmembers class com.yunian.ai.domain.ServiceRegistry {
    public static <methods>;
}

# 🔒 feature:notification: CompanionKeepAliveService + BootReceiver declared in AndroidManifest
-keep class com.yunian.ai.feature.notification.** { *; }

# 🔒 Push: PushManager imported in YuNianApplication, routes to vendor Push SDKs
-keep class com.yunian.ai.push.PushManager { *; }

# kotlinx.serialization: preserve serializers for reflection-based adapter lookup
-keepattributes *Annotation*, InnerClasses, EnclosingMethod
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.yunian.ai.**$$serializer { *; }
-keepclassmembers class com.yunian.ai.** {
    *** Companion;
}
-keepclasseswithmembers class com.yunian.ai.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int w(...);
    public static int e(...);
}

# Android manifest entry points must stay loadable by class name.
# R8 updates manifest class references when obfuscation renames them.
-keep class com.yunian.ai.YuNianApplication { *; }
-keep class com.yunian.ai.MainActivity { *; }
-keep class com.yunian.ai.security.NativeBridge { *; }
-keep,allowobfuscation class * extends android.app.Service { *; }
-keep,allowobfuscation class * extends android.content.BroadcastReceiver { *; }
-keep,allowobfuscation class * extends androidx.work.Worker { *; }
-keep,allowobfuscation class * extends androidx.work.CoroutineWorker { *; }

# JNI entry points: NativeBridge is registered by native code via
# FindClass("com/yunian/ai/security/NativeBridge") + RegisterNatives, and
# KmsProvider uses Java_com_yunian_ai_security_KmsProvider_* exported JNI
# symbols. Keep class names, member names, and descriptors intact.
-keep,includedescriptorclasses class com.yunian.ai.security.NativeBridge { *; }
-keep,includedescriptorclasses class com.yunian.ai.security.KmsProvider { *; }

# 说明：Google AI Edge LiteRT-LM 相关 keep 规则已随 feature:localmodel 一并删除
# （D4 本地推理统一由 core:agent 的 Rust Cordis Agent / liblianyu_agent.so 承担）。

# Prevent R8 from stripping System.loadLibrary calls in static initializers.
# Even with -keep, R8 may optimize away side-effect-free-looking code paths
# that include loadLibrary. This directive preserves all native method stubs.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# ================================================================
# sherpa-onnx (com.k2fsa.sherpa.onnx) — CRITICAL JNI FIX
# ================================================================
# AAR ships an EMPTY proguard.txt (no consumer rules), so R8 was renaming
# config data classes (EndpointConfig/FeatureConfig/OnlineModelConfig/...),
# but libsherpa-onnx-jni.so resolves them by HARD-CODED string:
#   FindClass("com/k2fsa/sherpa/onnx/OnlineModelConfig") + GetFieldID("rule1",...)
# Class/member renaming breaks the JNI handshake -> UnsatisfiedLinkError /
# NoSuchFieldError -> voice recognition silently disabled in release builds.
# Keep ALL sherpa-onnx classes, members, and native signatures intact.
-keep,includedescriptorclasses class com.k2fsa.sherpa.onnx.** { *; }

# Room: keep annotations and generated metadata, but allow class/interface names to be obfuscated.
-keep,allowobfuscation class com.yunian.ai.database.AppDatabase { *; }
-keep,allowobfuscation @androidx.room.Entity class com.yunian.ai.database.model.** { *; }
-keep,allowobfuscation interface com.yunian.ai.database.dao.** { *; }
-keep class androidx.room.** { *; }
-keep @androidx.room.Dao interface *
-keepclassmembers,allowobfuscation class * {
    @androidx.room.* <fields>;
    @androidx.room.* <methods>;
}
-dontwarn androidx.room.paging.**

# Retrofit interfaces and HTTP annotations.
-keep,allowobfuscation interface com.yunian.ai.network.OpenAiApi { *; }
-keep,allowobfuscation interface com.yunian.ai.network.AnthropicApi { *; }
-keep,allowobfuscation interface com.yunian.ai.network.GeminiApi { *; }
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit
-dontwarn retrofit2.KotlinExtensions
-dontwarn retrofit2.KotlinExtensions$*

# Kotlin serialization: DTO obfuscation is safe because:
# - Custom serializers use string-based key lookups (no reflection on field names)
# - Auto-generated serializers respect @SerialName annotations for wire names
# - R8 preserves annotations via -keepattributes
# Class names, property names, and method names may all be obfuscated freely.

# Compose runtime needs annotations, not app composable names.
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}

# Third-party SDKs that use reflection/native loading.
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**
-dontwarn org.slf4j.**
-dontwarn ch.qos.logback.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn coil.**
-dontwarn androidx.work.**
-dontwarn kotlinx.coroutines.**
-dontwarn androidx.security.crypto.**

# Crypto: keep JCA classes intact for AES-256-GCM encrypted API calls
-keep class javax.crypto.** { *; }
-keep class javax.crypto.spec.** { *; }
-dontwarn javax.crypto.**

# ViewModel constructors are created reflectively by AndroidX factories; allow
# class/method obfuscation while preserving constructors.
-keepclassmembers,allowobfuscation class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
-keepclassmembers,allowobfuscation class * extends androidx.lifecycle.AndroidViewModel {
    <init>(...);
}

# WeChat module — keep initialization path intact; R8 aggressive optimizations
# (mergeinterfacesaggressively/overloadaggressively) corrupt static initializers
# in these classes, causing SIGABRT on startup (YuNianApplication.initWeChat).
-keep class com.yunian.ai.feature.wechat.** { *; }
-keep class com.yunian.ai.feature.wechat.data.** { *; }
-keep class com.yunian.ai.feature.wechat.service.** { *; }

# DEX padding — must survive R8 to push method count past 64K, forcing multi-dex
-keep class com.yunian.ai.internal.DexPadding { *; }

# Android framework conventions.
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.yunian.ai.R$* { *; }

# Enum converters need value lookup; names are persisted in Room values.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ================================================================
# Vendor Push SDKs (OPPO / vivo / Xiaomi / Huawei)
# ================================================================
# OPPO / vivo / Xiaomi SDKs are bundled as local aars and accessed via reflection.
# Keep their class names and members so Class.forName / getMethod work in release builds.
-keep class com.heytap.msp.push.** { *; }
-keep class com.coloros.mcs.** { *; }
-keep class com.vivo.push.** { *; }
-keep class com.xiaomi.mipush.sdk.** { *; }
-keep class com.xiaomi.push.** { *; }
-keep class com.huawei.hms.push.** { *; }
-dontwarn com.heytap.msp.push.**
-dontwarn com.coloros.mcs.**
-dontwarn com.vivo.push.**
-dontwarn com.xiaomi.mipush.sdk.**
-dontwarn com.xiaomi.push.**
# ═══════════════════════════════════════════════════════════════
# Ultimate Shell — protect shell DEX + assets from R8/strip
# ═══════════════════════════════════════════════════════════════

# Keep shell bootstrapping classes (in shell DEX, referenced by manifest)
-keep class com.yunian.ai.security.StaticApkShell { *; }
-keep class com.yunian.ai.security.SActivity { *; }
-keep class com.yunian.ai.security.MethodRecoveryEngine { *; }
-keep class com.yunian.ai.security.G0 { *; }

# Keep MainActivity (manifest LAUNCHER → must be resolvable by ClassLoader)
-keep class com.yunian.ai.MainActivity { *; }
-keep class com.yunian.ai.YuNianApplication { *; }

# Keep all native method declarations
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep AndroidX runtime (for WorkManager initialization in shell)
-keep class androidx.work.WorkManager { *; }
-keep class androidx.work.Configuration { *; }
-keep class androidx.work.Configuration$Builder { *; }
-keep class androidx.work.impl.WorkManagerInitializer { *; }

# Keep InMemoryDexClassLoader (used by shell at runtime)
-keep class dalvik.system.InMemoryDexClassLoader { *; }
-keep class dalvik.system.BaseDexClassLoader { *; }
-keep class dalvik.system.DexPathList { *; }
-keep class dalvik.system.DexPathList$Element { *; }

# ═══════════════════════════════════════════════════════════════
# WorkManager — preserve reflection-based initialization
# ═══════════════════════════════════════════════════════════════
-keep class * extends androidx.work.Worker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keepattributes *Annotation*
-keep class androidx.work.impl.** { *; }
-keep class androidx.work.WorkManager { *; }
-keep class androidx.work.Configuration { *; }
-keep class androidx.work.Configuration$Builder { *; }
-keep class androidx.work.impl.WorkManagerInitializer { *; }

# ═══════════════════════════════════════════════════════════════
# Agent Native 桥接（UniFFI + JNA）—— release 专有故障的根因所在
#
# UniFFI 生成的绑定完全依赖 JNA：native 函数按「接口方法名」解析符号，
# RustBuffer.ByValue 等结构体的字段布局靠反射计算，回调（ToolHost /
# StreamSink / SkillStore / MemoryStore / …）经 JNA CallbackProxy 反射调用。
# 一旦 R8 改名或删除这些类/方法/字段，release 包会在运行期抛
# UnsatisfiedLinkError / NoSuchMethodError，并因 SecureLog 在非 debug 静默
# 而表现为「聊天界面 AI 不回复」——debug 包不复现。
#
# 事故证据（2026-09-28 release mapping.txt）：
#   com.sun.jna.CallbackProxy            -> ug0
#   com.sun.jna.CallbackReference        -> yg0
#   com.sun.jna.Library                  -> cd4
#   com.yunian.ai.agent.host.AgentToolHost -> d8
#   com.yunian.ai.agent.uniffi.AgentTurnRequest -> f8
#   com.yunian.ai.agent.uniffi.FfiConverterRustBuffer -> R8$$REMOVED$$CLASS$$
# ═══════════════════════════════════════════════════════════════
-keep class com.sun.jna.** { *; }
-keepclassmembers class com.sun.jna.** { *; }
-dontwarn com.sun.jna.**
-dontwarn java.awt.**
-keep class * implements com.sun.jna.Library { *; }
-keep class * implements com.sun.jna.Callback { *; }
-keep class * extends com.sun.jna.Structure { *; }
-keep class * extends com.sun.jna.PointerType { *; }
-keepclassmembers class * extends com.sun.jna.Structure {
    <fields>;
    <init>();
}
-keepclassmembers class * implements com.sun.jna.Callback {
    <methods>;
}
-keep,allowoptimization class * implements com.sun.jna.Callback {
    <methods>;
}

# UniFFI 生成的 Kotlin 绑定（包名见 agent-native/uniffi.toml）
-keep class com.yunian.ai.agent.uniffi.** { *; }
-keepclassmembers class com.yunian.ai.agent.uniffi.** { *; }
-keep interface com.yunian.ai.agent.uniffi.** { *; }
-keep enum com.yunian.ai.agent.uniffi.** { *; }
-keep,includedescriptorclasses class com.yunian.ai.agent.uniffi.** { *; }

# 回调实现类（Kotlin 实现，经 JNA 被 Rust 反调）
-keep class com.yunian.ai.agent.AgentFacade { *; }
-keep class com.yunian.ai.agent.host.** { *; }
-keep class com.yunian.ai.agent.skill.** { *; }
-keepclassmembers class * implements com.yunian.ai.agent.uniffi.ToolHost { *; }
-keepclassmembers class * implements com.yunian.ai.agent.uniffi.StreamSink { *; }
-keepclassmembers class * implements com.yunian.ai.agent.uniffi.SkillStore { *; }
-keepclassmembers class * implements com.yunian.ai.agent.uniffi.MemoryStore { *; }
-keepclassmembers class * implements com.yunian.ai.agent.uniffi.RequestSignatureProvider { *; }
-keepclassmembers class * implements com.yunian.ai.agent.uniffi.StickerPreferenceStore { *; }
-keepclassmembers class * implements com.yunian.ai.agent.uniffi.TurnStateController { *; }

# JNI 声明方法名必须与 .so 符号一致
-keepclasseswithmembernames class * {
    native <methods>;
}

-dontwarn com.huawei.hms.**
-dontwarn com.huawei.android.os.**
-dontwarn com.huawei.hianalytics.**
-dontwarn com.huawei.libcore.io.**
-dontwarn org.apache.commons.codec.**
# ================================================================
# assists-base 3.5.9（:feature:skills 无障碍自动化）— R8 依赖排除后的补偿规则
# ================================================================
# 背景：在 feature/skills/build.gradle.kts 中对 io.github.ven-coder:assists-base
# 排除了三个经字节码取证确认不可达的传递依赖分组：
#   com.google.mlkit / com.tencent.mmkv / androidx.databinding
# assists-base 内部仍有类的常量池/方法描述符指向这些包（虽然那些类在 jar 内零入边），
# R8 在 release 全程序分析时会对其报 Missing class，因此必须补 -dontwarn。
#
# 证据（build/assists-probe/javap-c/，javap -p -c -constants 全 203 个 class 的指令级取证）：
#   · com/google/mlkit/**     仅被 com.ven.assists.text.TextRecognitionChineseLocator
#                             及其 2 个 lambda 类引用；这 4 个类在全 jar 内零入边。
#   · com/tencent/mmkv/**     全 203 个 class 零引用。
#   · androidx/databinding/** 全 203 个 class 零引用；AAR 自带 4 个 ViewBinding 生成类
#                             实现的是 androidx.viewbinding.ViewBinding（AGP 自带，不受影响）。
# -dontwarn 只抑制缺类警告，不生成/保留任何代码；被排除类的唯一后果是
# 「运行时才可能 NoClassDefFoundError」——而它们本就不可达。
-dontwarn com.google.mlkit.**
-dontwarn com.tencent.mmkv.**
-dontwarn androidx.databinding.**
# 上述被排除依赖的传递闭包（被我们一并排除，故此处同样免警）：
-dontwarn com.google.android.gms.internal.mlkit_vision_text_chinese.**
-dontwarn com.google.android.gms.internal.mlkit_vision_common.**
-dontwarn com.google.android.gms.internal.mlkit_vision_text_common.**
-dontwarn com.google.android.odml.**
-dontwarn com.google.android.datatransport.**
-dontwarn com.google.firebase.encoders.**

# keep 规则：本轮「默认不加」。
# 明确不采用全量保留（-keep class com.ven.assists.** { *; }）：那会把 203 个类全部保留，
# 与本次瘦身目标直接冲突，且无证据表明需要 —— assists 的对外接触面全部是我们自己的
# Kotlin 源码直接引用（AssistsService / AssistsCore / AssistsServiceListener 等），
# 由 R8 的正常可达性分析覆盖；AAR 自带 proguard.txt 为 0 字节，即上游未声明任何必须保留项；
# AAR manifest 声明的 AssistsFileProvider / ClipboardActivity 由 manifest 引用，
# R8 会通过 manifest keep 规则自动保留其类名。
#
# R8 已实测（2026-10-03 :app:assembleRelease 成功，逐类核对 mapping.txt/seeds.txt/usage.txt）——
# 并因此抓到 1 个 release 专有缺陷，修复规则见下：
#   其余结论均经 R8 实际运行验证：AssistsService / AssistsFileProvider / ClipboardActivity 因
#   manifest 引用被自动保名（seeds.txt 可见）；gson 序列化路径存活（com.google.gson.Gson -> i83）；
#   反序列化路径被正确裁掉（usage.txt 里的 readField 等）；被排除的 mlkit/mmkv/viewbinding 未进 dex。
#
# ── 修复：assists 节点树数据类的 gson 反射字段名（release 专有缺陷）──
# AssistsCore.getRootNodeTreeJson 用 gson **反射**序列化 NodeTree / NodeBounds，JSON 键名取自字段名。
# R8 默认会混淆这两个类的字段名。修复前 mapping.txt 实测：
#   NodeTree -> d20（packageName->a、text->b、des->c、className->e、isClickable->g、boundsInScreen->i）
#   NodeBounds -> b20（centerX->g、centerY->h、left->a、top->b …）
# 后果：release 包里 screen_dump_ui 返回的节点树 JSON 键名全是 a/b/c…——不会崩（gson 在），
# 但内容对模型无意义，等于该工具在 release 下不可用。
# 修复：用 -keepclassmembers 保住**成员名**（类名仍可混淆，不破坏瘦身）。
#   ⚠ 首版误写成 -keepclassmembers,allowobfuscation —— 该修饰符的含义恰是「允许改名」，
#     实测复验：字段仍被改成 a/b/c（mapping.txt 16:27 版），故必须去掉 allowobfuscation。
#   注意区分：allowobfuscation 适合 @SerializedName 那类「注解值才是 JSON 键、字段名无所谓」的场景；
#   本例 JSON 键直接取自字段名，因此字段名必须原样保留。
-keepclassmembers class com.ven.assists.AssistsCore$NodeTree { <fields>; }
-keepclassmembers class com.ven.assists.AssistsCore$NodeBounds { <fields>; }


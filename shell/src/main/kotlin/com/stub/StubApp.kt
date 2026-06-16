package com.stub

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import java.io.File
import java.lang.reflect.Method

/**
 * 360-style shell Application — 壳入口。
 * 
 * 加载链:
 *   StubApp.attachBaseContext()
 *     → loadBridge()          加载 liblianyu_shell.so
 *     → verifySignature()     签名校验 (0x96c0 → sub_23a0 → JNI_OnLoad)
 *     → decryptDex()          解密并加载业务 DEX
 *     → attachRealApp()       反射启动真实 Application
 */
class StubApp : Application() {

    // === 壳状态 ===
    private var realApp: Application? = null
    private var decryptOk: Boolean = false
    private var signatureOk: Boolean = false

    // === JNI 桥接 (通过 RegisterNatives 注册) ===
    external fun interface13(ctx: Context): Int
    external fun interface14(): String
    external fun interface15(): Boolean

    companion object {
        init {
            System.loadLibrary("lianyu_shell")
        }

        var appContext: Context? = null
            private set

        var realApplication: Application? = null
            private set

        @JvmStatic
        fun getAppContext(): Context? = appContext

        @JvmStatic
        fun getRealApplication(): Application? = realApplication
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        appContext = base

        // Step 1: 初始化壳
        val config = loadConfig(base)

        // Step 2: JNI 桥接 (触发 native 层签名校验 + DEX 解密)
        try {
            interface13(base)
            signatureOk = true
        } catch (e: Exception) {
            // 签名校验失败 → native 层已处理
            return
        }

        // Step 3: 解密 DEX 碎片 → 加载
        decryptOk = decryptAndLoad(base, config)

        // Step 4: 反射启动真实 Application
        if (decryptOk) {
            attachRealApp(base)
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (realApp != null) {
            realApp?.onCreate()
        }
    }

    // ============================================================
    // 私有方法
    // ============================================================

    data class Config(
        val fragmentCount: Int,
        val usePngCarriers: Boolean,
        val realAppClass: String
    )

    private fun loadConfig(ctx: Context): Config {
        // 碎片数量从 native 层获取
        val count = try { interface14().toIntOrNull() ?: 7 } catch (_: Exception) { 7 }
        return Config(
            fragmentCount = count,
            usePngCarriers = true,
            realAppClass = "com.lianyu.ai.LianYuApplication"
        )
    }

    private fun decryptAndLoad(ctx: Context, config: Config): Boolean {
        return try {
            // native 层完成 DEX 解密并返回 ClassLoader
            // interface15 触发解密 + InMemoryDexClassLoader 创建
            interface15()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun attachRealApp(ctx: Context) {
        try {
            val appClass = Class.forName("com.lianyu.ai.LianYuApplication")
            val app = appClass.newInstance() as Application

            // 反射调用 Application.attach()
            val attachMethod: Method = Application::class.java.getDeclaredMethod(
                "attach", Context::class.java
            )
            attachMethod.isAccessible = true
            attachMethod.invoke(app, ctx)

            realApp = app
            realApplication = app
            app.onCreate()
        } catch (e: Exception) {
            // 加载失败，壳自毁
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }
}

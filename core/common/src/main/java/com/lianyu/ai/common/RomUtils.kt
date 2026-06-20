package com.lianyu.ai.common

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File
import java.io.FileInputStream
import java.util.Locale
import java.util.Properties

/**
 * ROM / 厂商识别工具。
 *
 * 用于区分 ColorOS / OriginOS / FuntouchOS / MIUI / HyperOS / EMUI / HarmonyOS /
 * OnePlus / Samsung 等常见国产 ROM，以便做针对性的系统设置跳转和权限引导。
 */
object RomUtils {

    enum class RomType {
        COLOR_OS,       // OPPO / Realme
        ORIGIN_OS,      // vivo / iQOO 新系统
        FUNTOUCH_OS,    // vivo / iQOO 旧系统
        MIUI,           // 小米 / Redmi
        HYPER_OS,       // 小米澎湃 OS
        EMUI,           // 华为旧系统
        HARMONY_OS,     // 华为鸿蒙
        ONEPLUS,        // 一加氧 OS / ColorOS for OnePlus
        SAMSUNG,
        OTHER
    }

    private const val KEY_VERSION_OPPO = "ro.build.version.opporom"
    private const val KEY_VERSION_VIVO = "ro.vivo.os.version"
    private const val KEY_VERSION_MIUI = "ro.miui.ui.version.name"
    private const val KEY_VERSION_EMUI = "ro.build.version.emui"
    private const val KEY_VERSION_HARMONY = "hw_sc.build.platform.version"
    private const val KEY_VERSION_ONEPLUS = "ro.rom.version"

    private var cachedType: RomType? = null
    private var cachedVersion: String? = null

    val romType: RomType
        get() {
            if (cachedType == null) {
                cachedType = detectRomType()
            }
            return cachedType!!
        }

    val romVersion: String
        get() {
            if (cachedVersion == null) {
                cachedVersion = detectRomVersion()
            }
            return cachedVersion ?: ""
        }

    val isOppo: Boolean
        get() = romType == RomType.COLOR_OS

    val isVivo: Boolean
        get() = romType == RomType.ORIGIN_OS || romType == RomType.FUNTOUCH_OS

    val isXiaomi: Boolean
        get() = romType == RomType.MIUI || romType == RomType.HYPER_OS

    val isHuawei: Boolean
        get() = romType == RomType.EMUI || romType == RomType.HARMONY_OS

    fun isOppoOrVivo(): Boolean = isOppo || isVivo

    /**
     * 判断当前 ROM 是否属于 ColorOS 12+（后台限制更严格）。
     */
    fun isColorOS12OrAbove(): Boolean {
        if (!isOppo) return false
        val ver = romVersion
        return try {
            val major = ver.substringBefore(".").toIntOrNull() ?: 0
            major >= 12
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 判断当前 ROM 是否属于 OriginOS 3+（后台限制更严格）。
     */
    fun isOriginOS3OrAbove(): Boolean {
        if (romType != RomType.ORIGIN_OS) return false
        val ver = romVersion
        return try {
            val major = ver.substringBefore(".").toIntOrNull() ?: 0
            major >= 3
        } catch (_: Exception) {
            false
        }
    }

    private fun detectRomType(): RomType {
        val props = readBuildProps()

        return when {
            !props.getProperty(KEY_VERSION_OPPO).isNullOrBlank() -> RomType.COLOR_OS
            !props.getProperty(KEY_VERSION_ONEPLUS).isNullOrBlank() -> RomType.ONEPLUS
            !props.getProperty(KEY_VERSION_HARMONY).isNullOrBlank() -> RomType.HARMONY_OS
            !props.getProperty(KEY_VERSION_EMUI).isNullOrBlank() -> RomType.EMUI
            !props.getProperty(KEY_VERSION_MIUI).isNullOrBlank() -> RomType.MIUI
            !props.getProperty(KEY_VERSION_VIVO).isNullOrBlank() -> {
                val vivoVer = props.getProperty(KEY_VERSION_VIVO, "")
                if (vivoVer.startsWith("OriginOS", ignoreCase = true) ||
                    vivoVer.startsWith("origin", ignoreCase = true)
                ) {
                    RomType.ORIGIN_OS
                } else {
                    RomType.FUNTOUCH_OS
                }
            }
            else -> matchByManufacturer()
        }
    }

    private fun matchByManufacturer(): RomType {
        val manufacturer = Build.MANUFACTURER.lowercase(Locale.getDefault())
        return when {
            manufacturer.contains("oppo") || manufacturer.contains("realme") -> RomType.COLOR_OS
            manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> RomType.ORIGIN_OS
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") -> RomType.MIUI
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> RomType.EMUI
            manufacturer.contains("oneplus") -> RomType.ONEPLUS
            manufacturer.contains("samsung") -> RomType.SAMSUNG
            else -> RomType.OTHER
        }
    }

    private fun detectRomVersion(): String {
        val props = readBuildProps()
        return when (romType) {
            RomType.COLOR_OS -> props.getProperty(KEY_VERSION_OPPO, "")
            RomType.ORIGIN_OS, RomType.FUNTOUCH_OS -> props.getProperty(KEY_VERSION_VIVO, "")
            RomType.MIUI, RomType.HYPER_OS -> props.getProperty(KEY_VERSION_MIUI, "")
            RomType.EMUI -> props.getProperty(KEY_VERSION_EMUI, "")
            RomType.HARMONY_OS -> props.getProperty(KEY_VERSION_HARMONY, "")
            RomType.ONEPLUS -> props.getProperty(KEY_VERSION_ONEPLUS, "")
            else -> ""
        }
    }

    /**
     * 读取 build.prop 中的部分字段。优先读 /system/build.prop，失败则通过反射 SystemProperty。
     */
    private fun readBuildProps(): Properties {
        val props = Properties()
        try {
            val buildProp = File(Environment.getRootDirectory(), "build.prop")
            if (buildProp.canRead()) {
                FileInputStream(buildProp).use { props.load(it) }
            }
        } catch (_: Exception) {
            // ignore
        }

        // 反射兜底：android.os.SystemProperties
        try {
            val clazz = Class.forName("android.os.SystemProperties")
            val getMethod = clazz.getMethod("get", String::class.java, String::class.java)
            arrayOf(
                KEY_VERSION_OPPO,
                KEY_VERSION_VIVO,
                KEY_VERSION_MIUI,
                KEY_VERSION_EMUI,
                KEY_VERSION_HARMONY,
                KEY_VERSION_ONEPLUS
            ).forEach { key ->
                if (props.getProperty(key).isNullOrBlank()) {
                    val value = getMethod.invoke(null, key, "") as? String
                    if (!value.isNullOrBlank()) {
                        props.setProperty(key, value)
                    }
                }
            }
        } catch (_: Exception) {
            // ignore
        }

        return props
    }

    /**
     * 判断某个 Intent 目标 Activity 是否存在，用于 ROM 设置页跳转前的可用性检查。
     */
    fun isComponentAvailable(context: Context, packageName: String, className: String): Boolean {
        return try {
            val intent = android.content.Intent().setClassName(packageName, className)
            context.packageManager.resolveActivity(intent, 0) != null
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 尝试按候选列表找到第一个可用的 ComponentName。
     */
    fun findAvailableComponent(
        context: Context,
        candidates: List<Pair<String, String>>
    ): android.content.ComponentName? {
        for ((pkg, cls) in candidates) {
            if (isComponentAvailable(context, pkg, cls)) {
                return android.content.ComponentName(pkg, cls)
            }
        }
        return null
    }

    /**
     * 获取一个可读的 ROM 名称，用于埋点和用户提示。
     */
    fun getRomDisplayName(): String {
        return when (romType) {
            RomType.COLOR_OS -> "ColorOS"
            RomType.ORIGIN_OS -> "OriginOS"
            RomType.FUNTOUCH_OS -> "FuntouchOS"
            RomType.MIUI -> "MIUI"
            RomType.HYPER_OS -> "HyperOS"
            RomType.EMUI -> "EMUI"
            RomType.HARMONY_OS -> "HarmonyOS"
            RomType.ONEPLUS -> "OxygenOS/ColorOS"
            RomType.SAMSUNG -> "OneUI"
            RomType.OTHER -> Build.MANUFACTURER
        }
    }
}

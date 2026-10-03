plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.yunian.ai.feature.skills"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    kotlin {
        jvmToolchain(17)
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:database"))
    implementation(project(":core:network"))
    implementation(project(":core:ui-common"))
    implementation(project(":core:domain"))
    // Agent 核心门面（SkillStoreAdapter 桥接 Rust SkillSelector）
    implementation(project(":core:agent"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.okhttp)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    // assists-base 3.5.9（无障碍自动化）。以下 exclude 逐条有字节码证据，详见 build/assists-probe/REPORT.md 与本次 A1 取证：
    //  · com.google.mlkit : 仅 com.ven.assists.text.TextRecognitionChineseLocator(+其 2 个 lambda) 引用；
    //    该 4 个类在全 203 个 class 中零入边（javap -c 指令级取证），继承路径/使用入口均不可达 → 排除。
    //  · com.tencent.mmkv : 全 203 个 class 零引用（.module 里是 runtime 依赖，实际未被使用）→ 排除。
    //  · androidx.databinding : 全 203 个 class 零引用；AAR 自带 binding 类实现的是
    //    androidx.viewbinding.ViewBinding（由 AGP 自带，非本依赖），实测确认 → 排除。
    implementation(libs.assists.base) {
        // 注意：本配置下「仅 group」的 exclude 对 com.tencent:mmkv 实测不生效（dependencies 复核），
        // 故三条统一使用 group + module 全坐标形式。
        exclude(group = "com.google.mlkit", module = "text-recognition-chinese")
        exclude(group = "com.tencent", module = "mmkv")
        exclude(group = "androidx.databinding", module = "viewbinding")
    }

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
    // 本模块源码走 org.json（SkillStoreAdapter）。Android 的 org.json 只是抛 Stub! 的空壳，
    // JVM 单测必须挂真实实现，否则 JSONObject/JSONArray 一调用就崩（同 :core:agent 处理方式）。
    testImplementation(libs.org.json)
}
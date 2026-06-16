plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.devtools.ksp)
}

android {
    namespace = "com.lianyu.ai"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lianyu.ai"
        minSdk = 26
        targetSdk = 35
        versionCode = 12
        versionName = "1.8"

        buildConfigField("String", "HARDENING_LEVEL", "\"VMPv2.0+Keystore+AES256GCM\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }
    }

    ndkVersion = "30.0.14904198"

    packagingOptions {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("../release.keystore")
            storePassword = System.getenv("LIANYU_STORE_PASSWORD") ?: project.findProperty("LIANYU_STORE_PASSWORD") as String? ?: "debug_password_placeholder"
            keyAlias = System.getenv("LIANYU_KEY_ALIAS") ?: project.findProperty("LIANYU_KEY_ALIAS") as String? ?: "your_alias"
            keyPassword = System.getenv("LIANYU_KEY_PASSWORD") ?: project.findProperty("LIANYU_KEY_PASSWORD") as String? ?: "debug_password_placeholder"
            enableV3Signing = true
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            isDebuggable = true
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            resValue("string", "app_name", "LianYu")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }

    // jniLibs are picked up automatically from src/main/jniLibs/
}

val isWindows = System.getProperty("os.name").lowercase().contains("windows")
val pythonExecutable = if (isWindows) "python" else "python3"

val shellPayloadAssetsDir = layout.projectDirectory.dir("src/main/assets/lianyu_shell")
val unsignedReleaseApk = layout.buildDirectory.file("outputs/apk/release/app-release-unsigned.apk")

tasks.register<Exec>("packageShellPayload") {
    group = "security"
    description = "Encrypt release classes*.dex into in-repo one-piece shell payload assets. Requires LIANYU_SHELL_PAYLOAD_KEY for CI smoke packaging; production should use native KMS-compatible exporter."
    dependsOn("assembleRelease")
    onlyIf { providers.environmentVariable("LIANYU_SHELL_PAYLOAD_KEY").orNull != null }
    inputs.file(unsignedReleaseApk)
    outputs.dir(shellPayloadAssetsDir)
    commandLine(
        pythonExecutable,
        "${rootProject.projectDir}/tools/package_shell_payload.py",
        "--apk",
        unsignedReleaseApk.get().asFile.absolutePath,
        "--out",
        shellPayloadAssetsDir.asFile.absolutePath
    )
}


// FIX 1: Strip plaintext classes*.dex from release APK
//
// The encrypted shell payload (assets/lianyu_shell/shell_payload.bin
// and classes.bin) contains the full DEX. The plaintext classes.dex
// in the APK root is a reverse-engineering weakness — it must be
// removed after packaging and before signing.
// ══════════════════════════════════════════════════════════════




// Strip plaintext classes.dex from the unsigned release APK after packaging.
// The encrypted shell payload (assets/lianyu_shell/) contains the full DEX.
// Uses tools/strip_dex.py to operate on the output APK.
tasks.register<Exec>("removePlaintextDex") {
    val unsignedApk = layout.buildDirectory.file("outputs/apk/release/app-release-unsigned.apk")
    inputs.file(unsignedApk)
    onlyIf { unsignedApk.get().asFile.exists() }

    commandLine(
        pythonExecutable,
        "${rootProject.projectDir}/tools/strip_dex.py",
        unsignedApk.get().asFile.absolutePath
    )
}

// Auto-attach: run removePlaintextDex AFTER packageRelease completes
afterEvaluate {
    tasks.named("packageRelease") {
        finalizedBy("removePlaintextDex")
    }
}


dependencies {
    // Core modules
    implementation(project(":core:common"))
    implementation(project(":core:database"))
    implementation(project(":core:domain"))
    implementation(project(":core:network"))
    implementation(project(":core:security"))
    implementation(project(":core:ui-common"))

    // Feature modules
    implementation(project(":feature:companion"))
    implementation(project(":feature:chat"))
    implementation(project(":feature:groupchat"))
    implementation(project(":feature:memory"))
    implementation(project(":feature:notification"))
    implementation(project(":feature:profile"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:localmodel"))
    implementation(project(":feature:wechat"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.accompanist.systemuicontroller)
    implementation(libs.androidx.animation)
    implementation(libs.androidx.animation.core)
    implementation(libs.androidx.animation.graphics)
    implementation(libs.lottie.compose)
    implementation(libs.androidx.app.update.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.tracing)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
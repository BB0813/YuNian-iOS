plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.lianyu.ai.network"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }
}

dependencies {
    api(project(":core:domain"))
    implementation(project(":core:database"))
    implementation(project(":core:common"))
    implementation(project(":core:security"))

    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)
    // Kotlin coroutines
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}

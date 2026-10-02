plugins {
    alias(libs.plugins.android.library)
}

dependencies {
    // core:domain 核心只依赖 kotlinx-coroutines（语言级基础设施），无其他业务依赖
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    // AGP 9.2.1 不再自动注入 junit，需手写（与全仓其余 16 个有测试的模块写法一致）
    testImplementation(libs.junit)
}

android {
    namespace = "com.yunian.ai.domain"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
}

plugins {
    alias(libs.plugins.android.library)
}

dependencies {
    // core:domain 必须保持零依赖，仅定义接口和数据类
}

android {
    namespace = "com.lianyu.ai.domain"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
}

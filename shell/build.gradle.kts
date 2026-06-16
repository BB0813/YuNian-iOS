plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    compileOnly(files("${System.getenv("ANDROID_HOME") ?: "C:/Users/27194/AppData/Local/Android/Sdk"}/platforms/android-35/android.jar"))
}

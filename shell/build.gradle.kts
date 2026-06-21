// Shell module — build with plain Kotlin (JVM target)
// Not an Android library; used only for class structure reference.
plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(kotlin("stdlib"))
}

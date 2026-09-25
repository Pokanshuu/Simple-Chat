// AGP 9 内置 Kotlin，其默认绑定的是 KGP 2.2.10。
// 本项目依赖的 mikepenz markdown 库要求 kotlin-stdlib 2.4.10，
// 而低版本 Kotlin 编译器无法读取高版本元数据，因此必须抬高 KGP（AGP 官方推荐做法）。
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
        classpath("com.google.devtools.ksp:symbol-processing-gradle-plugin:${libs.versions.ksp.get()}")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}

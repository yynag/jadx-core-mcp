/*
 * JADX Headless Server (jadx-core-mcp) subproject Gradle build configuration.
 * Includes Kotlin JVM setup, JADX core SDK dependencies, and Shadow Fat-JAR packaging task.
 */

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    // Kotlin JVM plugin
    alias(libs.plugins.kotlin.jvm)

    // Application plugin to build Java/Kotlin CLI application
    application
    
    // Shadow plugin to build standalone executable Fat-JAR
    id("com.gradleup.shadow") version "8.3.6"
}

repositories {
    // Aliyun Maven repository mirrors for faster dependency downloads
    maven { url = uri("https://maven.aliyun.com/repository/public") }
    maven { url = uri("https://maven.aliyun.com/repository/google") }
    maven { url = uri("https://maven.aliyun.com/repository/jcenter") }
    // Maven Central & Google fallback repositories
    mavenCentral()
    google()
}

dependencies {
    // Kotlin Test integration
    testImplementation("org.jetbrains.kotlin:kotlin-test")

    // JUnit 5 test engine integration
    testImplementation(libs.junit.jupiter.engine)

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Guava utilities
    implementation(libs.guava)
    
    // JADX Core SDK and input plugin dependencies
    implementation(libs.jadx.core)
    implementation(libs.jadx.dex.input)
    implementation(libs.jadx.java.input)
    implementation(libs.jadx.smali.input)
    
    // Gson JSON library
    implementation(libs.gson)
    
    // Logback logging framework (file appender & rotation)
    implementation(libs.logback.classic)
    
    // Model Context Protocol official Kotlin SDK
    implementation(libs.mcp.kotlin.sdk)
}

// Configure JVM Toolchain for JDK 21 environment
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

application {
    // Specify CLI application main class entrypoint
    mainClass = "com.yyang.jadx_server.MainKt"
}

tasks.named<Test>("test") {
    // Use JUnit Platform for unit tests
    useJUnitPlatform()
    
    // Increase heap size for test runner to prevent OOM when decompiling large APKs
    maxHeapSize = "4G"
}

tasks.withType<ShadowJar> {
    // Merge ServiceLoader SPI descriptor files (critical for loading jadx plugins)
    mergeServiceFiles()
}

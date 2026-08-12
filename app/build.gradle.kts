/*
 * JADX Headless Server Gradle build.
 */

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
    id("com.gradleup.shadow") version "8.3.6"
}

repositories {
    maven { url = uri("https://maven.aliyun.com/repository/public") }
    maven { url = uri("https://maven.aliyun.com/repository/google") }
    mavenCentral()
    google()
}

dependencies {
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation(libs.junit.jupiter.engine)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    implementation(libs.jadx.core)
    implementation(libs.jadx.dex.input)
    implementation(libs.jadx.java.input)
    implementation(libs.jadx.smali.input)
    implementation(libs.gson)
    implementation(libs.logback.classic)
    implementation(libs.mcp.kotlin.sdk)

    // WHY: OpenCode type=remote 需要 MCP Streamable HTTP；kotlin-sdk 的 mcpStreamableHttp 依赖 Ktor
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.sse)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.cors)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

application {
    mainClass.set("com.yyang.jadx_server.MainKt")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    maxHeapSize = "4G"
}

tasks.withType<ShadowJar> {
    mergeServiceFiles()
}

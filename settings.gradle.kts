/*
 * JADX Headless Server (jadx-core-mcp) root settings configuration file.
 * Defines multi-module inclusion rules and global Gradle plugin repository mirrors.
 */

pluginManagement {
    repositories {
        // Aliyun Gradle plugin mirror repositories for faster downloading
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

plugins {
    // Apply Foojay Resolver plugin to support automatic JDK toolchain resolution
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "jadx-core-mcp"
include("app")

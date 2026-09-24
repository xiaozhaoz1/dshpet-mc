pluginManagement {
    repositories {
        mavenLocal()
        maven("https://maven.kaf.sh") { name = "Kaf Maven" }
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("dev.kikugie.stonecutter") version "0.7.10"
    id("com.iamkaf.multiloader.settings") version providers.gradleProperty("project.plugins").get()
}

// neoforge 节点启用 Parchment — 与 TLM 1.21.1 完全同映射 (TLM gradle.properties: parchment 2024.11.17 / mc 1.21.1)
// multiloader 内置目录该 alias 为空 (纯 Mojang), 覆写后 neoForge.parchment 自动生效
dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            version("parchment", "2024.11.17")
            version("parchment-minecraft", "1.21.1")
            // v73: 升级 neoforge 到 21.1.247 (multiloader 内置目录默认 21.1.62 过低 —
            // create 生态要求 ≥21.1.228; 用户游戏实例为最新 247)
            version("neoforge", "21.1.247")
        }
    }
}

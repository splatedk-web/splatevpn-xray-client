pluginManagement {
    repositories {
        val dependencyCache = gradle.gradleUserHomeDir.resolve("caches/splate-maven")
        if (dependencyCache.isDirectory) {
            maven {
                name = "SplateDependencyCache"
                url = uri(dependencyCache)
            }
        }
        google()
        maven {
            name = "GoogleMavenFallback"
            url = uri("https://maven.aliyun.com/repository/google")
            content {
                includeGroupByRegex("androidx\\..*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google\\.android.*")
                includeGroupByRegex("com\\.google\\.testing.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        val dependencyCache = gradle.gradleUserHomeDir.resolve("caches/splate-maven")
        if (dependencyCache.isDirectory) {
            maven {
                name = "SplateDependencyCache"
                url = uri(dependencyCache)
            }
        }
        google()
        maven {
            name = "GoogleMavenFallback"
            url = uri("https://maven.aliyun.com/repository/google")
            content {
                includeGroupByRegex("androidx\\..*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google\\.android.*")
                includeGroupByRegex("com\\.google\\.testing.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "SplateClient"
include(":app")
include(":tor-android")

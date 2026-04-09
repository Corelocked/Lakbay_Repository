import org.gradle.authentication.http.BasicAuthentication

pluginManagement {
    val mapboxDownloadsToken = providers.gradleProperty("MAPBOX_DOWNLOADS_TOKEN").orNull
        ?: providers.gradleProperty("mapbox.downloads.token").orNull
        ?: System.getenv("MAPBOX_DOWNLOADS_TOKEN")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
        if (!mapboxDownloadsToken.isNullOrBlank()) {
            maven("https://api.mapbox.com/downloads/v2/releases/maven") {
                credentials {
                    username = "mapbox"
                    password = mapboxDownloadsToken
                }
                authentication {
                    create<BasicAuthentication>("basic")
                }
            }
        }
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    val mapboxDownloadsToken = providers.gradleProperty("MAPBOX_DOWNLOADS_TOKEN").orNull
        ?: providers.gradleProperty("mapbox.downloads.token").orNull
        ?: System.getenv("MAPBOX_DOWNLOADS_TOKEN")
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        if (!mapboxDownloadsToken.isNullOrBlank()) {
            maven("https://api.mapbox.com/downloads/v2/releases/maven") {
                credentials {
                    username = "mapbox"
                    password = mapboxDownloadsToken
                }
                authentication {
                    create<BasicAuthentication>("basic")
                }
            }
        }
    }
}

rootProject.name = "scenic_navigation"
include(":app")

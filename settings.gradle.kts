pluginManagement {
    repositories {
        gradlePluginPortal()
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://api.mapbox.com/downloads/v2/releases/maven")
            authentication {
                create<BasicAuthentication>("basic")
            }
            credentials {
                username = "mapbox"
                password = providers.gradleProperty("MAPBOX_DOWNLOADS_TOKEN")
                    .orElse(providers.environmentVariable("ORG_GRADLE_PROJECT_MAPBOX_DOWNLOADS_TOKEN"))
                    .orElse(
                        providers.provider {
                            val props = java.util.Properties()
                            val localPropsFile = File(settingsDir, "local.properties")
                            if (localPropsFile.exists()) {
                                localPropsFile.inputStream().use { props.load(it) }
                                props.getProperty("MAPBOX_DOWNLOADS_TOKEN", "")
                            } else {
                                ""
                            }
                        },
                    )
                    .getOrElse("")
            }
        }
    }
}

rootProject.name = "dfn-meteorite-drone-android-app"
include(":app")
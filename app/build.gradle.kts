import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "au.edu.fireballs.stage4"
    compileSdk = 37

    defaultConfig {
        applicationId = "au.edu.fireballs.stage4"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName =
            libs
                .versions
                .app
                .version
                .name
                .get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val localProps =
            Properties().apply {
                val file = rootProject.file("local.properties")
                if (file.exists()) {
                    file.inputStream().use { load(it) }
                }
            }
        val mapboxToken =
            providers
                .gradleProperty("MAPBOX_DOWNLOADS_TOKEN")
                .orElse(providers.environmentVariable("ORG_GRADLE_PROJECT_MAPBOX_DOWNLOADS_TOKEN"))
                .orElse(providers.provider { localProps.getProperty("MAPBOX_DOWNLOADS_TOKEN", "") })
                .getOrElse("")
        val productionServerUrl =
            providers
                .provider {
                    localProps.getProperty("PRODUCTION_SERVER_URL", "")
                }.getOrElse("")
        val devServerUrl =
            providers
                .provider {
                    localProps.getProperty(
                        "DEV_SERVER_URL",
                        "",
                    )
                }.getOrElse("")

        buildConfigField("String", "MAPBOX_TOKEN", "\"$mapboxToken\"")
        buildConfigField("String", "PRODUCTION_SERVER_URL", "\"$productionServerUrl\"")
        buildConfigField("String", "DEV_SERVER_URL", "\"$devServerUrl\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    baseline = file("$rootDir/config/detekt/baseline.xml")
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.moshi)
    implementation(libs.moshi)
    implementation(libs.moshi.kotlin)
    ksp(libs.moshi.codegen)
    implementation(libs.coil.compose)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.play.services.location)
    implementation(libs.androidx.core.ktx)
    implementation(libs.mapbox.maps.android)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
}

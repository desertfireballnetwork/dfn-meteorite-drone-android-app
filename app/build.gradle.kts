import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
}

ksp {
    arg("room.schemaLocation", file("schemas").absolutePath)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
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
        val mapboxAccessToken =
            providers
                .gradleProperty("MAPBOX_ACCESS_TOKEN")
                .orElse(providers.environmentVariable("ORG_GRADLE_PROJECT_MAPBOX_ACCESS_TOKEN"))
                .orElse(providers.provider { localProps.getProperty("MAPBOX_ACCESS_TOKEN", "") })
                .getOrElse("")
        if (mapboxAccessToken.isBlank()) {
            throw GradleException(
                "MAPBOX_ACCESS_TOKEN is required: set it in local.properties, as gradle property " +
                    "MAPBOX_ACCESS_TOKEN, or env ORG_GRADLE_PROJECT_MAPBOX_ACCESS_TOKEN",
            )
        }
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

        buildConfigField("String", "MAPBOX_TOKEN", "\"$mapboxAccessToken\"")
        buildConfigField("String", "PRODUCTION_SERVER_URL", "\"$productionServerUrl\"")
        buildConfigField("String", "DEV_SERVER_URL", "\"$devServerUrl\"")
        buildConfigField("int", "MAPBOX_MAX_TILES_PER_REGION", "50000")
    }

    sourceSets {
        getByName("androidTest").assets.directories.add("$projectDir/schemas")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
        }
    }
}

tasks.withType<Test>().configureEach {
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(17))
        },
    )
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
    implementation(libs.hilt.work)
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
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.exifinterface)
    implementation(libs.mapbox.maps.android)
    implementation(libs.maps.compose)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.okhttp.mockwebserver)

    testImplementation(libs.androidx.test.core)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)

    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.cash.turbine)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.mockk)
}

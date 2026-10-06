import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ar.chamullo.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "ar.chamullo.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 25
        versionName = "0.5.5"
    }

    // The release key lives outside the repo (GRADLE_USER_HOME/gradle.properties: CHAMULLO_STORE_FILE and friends).
    // It is used only with CHAMULLO_RELEASE_KEY=true: switching keys forces a reinstall, so it is a deliberate step.
    val storePath = providers.gradleProperty("CHAMULLO_STORE_FILE").orNull
    val useReleaseKey = providers.gradleProperty("CHAMULLO_RELEASE_KEY").orNull == "true" && storePath != null
    signingConfigs {
        create("chamullo") {
            if (storePath != null) {
                storeFile = file(storePath)
                storePassword = providers.gradleProperty("CHAMULLO_STORE_PASSWORD").get()
                keyAlias = providers.gradleProperty("CHAMULLO_KEY_ALIAS").get()
                keyPassword = providers.gradleProperty("CHAMULLO_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (useReleaseKey) "chamullo" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.brucezhang1993.ohealthdevicebridge"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.brucezhang1993.ohealthdevicebridge"
        minSdk = 26
        targetSdk = 36
        versionCode = providers.gradleProperty("versionCode").orElse("24").get().toInt()
        versionName = providers.gradleProperty("versionName").orElse("0.10.0-mi1").get()
    }

    testOptions.unitTests.isReturnDefaultValues = true

    buildFeatures {
        buildConfig = true
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/AL2.0",
            "META-INF/LGPL2.1"
        )
    }
}

dependencies {
    // Modern Xposed entry / module metadata. targetApiVersion=101 intentionally keeps
    // compatibility with the existing legacy XposedBridge/XposedHelpers hook layer.
    compileOnly("io.github.libxposed:api:101.0.1")
    compileOnly("de.robv.android.xposed:api:82")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}

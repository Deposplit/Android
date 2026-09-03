import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.deposplit"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.deposplit"
        minSdk = 35
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            val skipBiometric = localProps.getProperty("SKIP_BIOMETRIC")?.toBooleanStrictOrNull() ?: false
            buildConfigField("Boolean", "SKIP_BIOMETRIC", "$skipBiometric")
            val fakePremium = localProps.getProperty("FAKE_PREMIUM")?.toBooleanStrictOrNull() ?: false
            buildConfigField("Boolean", "FAKE_PREMIUM", "$fakePremium")
        }
        release {
            buildConfigField("Boolean", "SKIP_BIOMETRIC", "false")
            buildConfigField("Boolean", "FAKE_PREMIUM", "false")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    lint {
        // Only these run, so an AGP bump cannot redden a pull request with a check nobody opted
        // into — which is what kept a blanket lintDebug out of CI. Unlike a baseline file, this
        // grants no exceptions to the checks that are in the set.
        checkOnly += setOf(
            "MissingTranslation",
            "NewApi",
            "ObsoleteSdkInt",
            "PermissionImpliesUnsupportedChromeOsHardware",
        )
        warningsAsErrors = true
        abortOnError = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":hexagon"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.biometric)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.zxing.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.compose.material.icons.extended)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

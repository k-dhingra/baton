plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.rooms"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.rooms"
        minSdk = 28
        targetSdk = 37
        versionCode = 21
        versionName = "0.5.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint { disable += "NewerVersionAvailable" }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("roborazzi.test.record", "true")
                it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
                it.maxHeapSize = "3g"
                it.jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED", "--add-opens=java.base/java.lang=ALL-UNNAMED")
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("org.jetbrains.compose.runtime:runtime:1.11.1")
    implementation("org.jetbrains.compose.foundation:foundation:1.11.1")
    implementation("org.jetbrains.compose.ui:ui:1.11.1")
    implementation("org.jetbrains.compose.ui:ui-tooling-preview:1.11.1")
    implementation("org.jetbrains.compose.material3:material3:1.11.0-alpha07")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    debugImplementation("org.jetbrains.compose.ui:ui-tooling:1.11.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.75.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.75.0")
    testImplementation("androidx.compose.ui:ui-test-junit4:1.11.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.11.2")
}

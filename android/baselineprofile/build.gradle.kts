// Generates the app's baseline profile and measures cold start with and without it (docs/08_test_plan.md §2).
// Runs on a connected device or emulator, API 28+: `gradlew :app:generateReleaseBaselineProfile`,
// `gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest`.
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "com.justtracker.app.baselineprofile"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    targetProjectPath = ":app"
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

baselineProfile {
    useConnectedDevices = true
}

// The journeys address the app by the package of the APK under test (".benchmark" suffix, see app/build.gradle.kts).
androidComponents {
    onVariants { v ->
        val loader = v.artifacts.getBuiltArtifactsLoader()
        v.instrumentationRunnerArguments.put("targetAppId", v.testedApks.map { loader.load(it)?.applicationId })
    }
}

dependencies {
    implementation(libs.androidx.junit)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}

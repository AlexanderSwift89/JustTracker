import java.security.KeyStore
import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    alias(libs.plugins.baselineprofile)
}

// Release signing is read from keystore.properties (git-ignored). Without it the release build is
// left unsigned (app-release-unsigned.apk: cannot be installed or uploaded) — a silent fallback to the
// debug key is how 1.0.0 reached RuStore (D-24, SEC-03). For a local R8 check on a device the debug key
// can be asked for explicitly: -PallowDebugSignedRelease=true (a warning is printed; never upload it).
// RuStore does not re-sign uploads: the same release key must be used for every version.
val allowDebugSignedRelease = providers.gradleProperty("allowDebugSignedRelease").orNull?.toBoolean() == true
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

// SHA-256 of the release certificate (CN=JustTracker, O=JustTracker, C=RU) — the key JustTracker is
// published with in RuStore since 1.0.2. `verifyReleaseKey` fails a release build when the key in
// keystore.properties has another certificate.
val releaseCertSha256 = "bcb70f4ee27dce1ae28c92465ae10d7c4cedb3eaddff46596de71bf806ef8709"

android {
    namespace = "com.justtracker.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.justtracker.app"
        minSdk = 26
        targetSdk = 36
        // Overridable from the command line for CI builds: -PversionCode=12 -PversionName=1.2.0
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 5
        versionName = (project.findProperty("versionName") as String?) ?: "1.1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = when {
                keystoreProps.isNotEmpty() -> signingConfigs.getByName("release")
                allowDebugSignedRelease -> {
                    logger.warn("JustTracker: release is signed with the DEBUG key (-PallowDebugSignedRelease) — never upload it (D-24)")
                    signingConfigs.getByName("debug")
                }
                else -> null
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        localeFilters += listOf("en", "ru")
    }

    bundle {
        // The user switches language inside the app, so both locales must be present in every install.
        language { enableSplit = false }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // mapsforge-themes ships two render themes; offline regions are drawn with OSMARENDER (OfflineRenderer).
            excludes += "/assets/mapsforge/default.xml"
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = false
    }

    lint {
        // Adaptive icons must live in -v26 even though minSdk is 26 (AAPT2 requirement);
        // version nags are handled by Renovate-style manual updates, not lint.
        disable += listOf("ObsoleteSdkInt", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
        abortOnError = true
        // Any new warning fails the build; "a newer dependency exists" stays visible in the report without
        // breaking CI on the day a library ships (dependency updates are a release task, docs/09_release_rustore.md).
        warningsAsErrors = true
        informational += "GradleDependency"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

// The profile is generated on a device on demand (`gradlew :app:generateReleaseBaselineProfile`, docs/08_test_plan.md
// section 2) and committed under src/release/generated; release builds then ship it.
baselineProfile {
    automaticGenerationDuringBuild = false
    dexLayoutOptimization = true
}

// Profile generation and the startup benchmark install release-like builds: a package of their own keeps them apart
// from an installed store version (another signature) on the test device.
android.buildTypes.configureEach {
    if (name == "nonMinifiedRelease" || name == "benchmarkRelease") applicationIdSuffix = ".benchmark"
}

// Fails a release build whose key from keystore.properties is not the published one (releaseCertSha256):
// RuStore refuses such an upload as a signature change.
abstract class VerifyReleaseKey : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val storeFile: RegularFileProperty

    @get:Internal
    abstract val storePassword: Property<String>

    @get:Input
    abstract val keyAlias: Property<String>

    @get:Input
    abstract val expectedSha256: Property<String>

    @TaskAction
    fun verify() {
        val file = storeFile.get().asFile
        val cert = KeyStore.getInstance(file, storePassword.get().toCharArray()).getCertificate(keyAlias.get())
            ?: throw GradleException("No key '${keyAlias.get()}' in $file")
        val actual = MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }
        if (actual != expectedSha256.get()) {
            throw GradleException(
                "Release key $file (${keyAlias.get()}) has certificate SHA-256 $actual, but JustTracker is " +
                    "published in RuStore with ${expectedSha256.get()}. Point android/keystore.properties " +
                    "(or the CI signing secrets) at the release key — docs/09_release_rustore.md §1.1.",
            )
        }
    }
}

if (keystoreProps.isNotEmpty()) {
    val verifyReleaseKey = tasks.register<VerifyReleaseKey>("verifyReleaseKey") {
        storeFile.set(rootProject.file(keystoreProps.getProperty("storeFile")))
        storePassword.set(keystoreProps.getProperty("storePassword"))
        keyAlias.set(keystoreProps.getProperty("keyAlias"))
        expectedSha256.set(releaseCertSha256)
    }
    // pre<Variant>Build runs before anything else of the variant: a wrong key fails in seconds, not after R8.
    tasks.named { it == "preReleaseBuild" }.configureEach { dependsOn(verifyReleaseKey) }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.osmdroid.android)
    implementation(libs.mapsforge.map.android)
    implementation(libs.mapsforge.map)
    implementation(libs.mapsforge.themes)
    implementation(libs.kotlinx.coroutines.android)
    // Aligns kotlinx-serialization with what room-migration 2.8 was built against (1.8); MigrationTestHelper
    // failed with AbstractMethodError on the 1.7 that navigation pulled in.
    implementation(platform(libs.kotlinx.serialization.bom))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.org.json)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room.testing)

    debugImplementation(libs.androidx.compose.ui.tooling)
}

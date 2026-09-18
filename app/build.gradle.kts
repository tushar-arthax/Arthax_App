import com.android.build.api.variant.HostTestBuilder
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/**
 * Release signing, read from keystore.properties so no password is ever committed.
 *
 * If the file is absent the release build still succeeds, just unsigned - so a fresh
 * checkout or a CI machine without the secrets is not blocked, it simply cannot produce an
 * installable APK.
 */
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val hasSigningConfig = keystoreProperties.getProperty("storeFile") != null

android {
    namespace = "ai.arthax.app"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        // The successor to the previous call-recorder app: same applicationId and the same
        // signing key, so Play and the phones treat it as an update and every installed
        // copy migrates in place. The source package (ai.arthax.app, the namespace above) is
        // deliberately different; only the applicationId is identity to Android.
        applicationId = "com.callrecorder.app"
        minSdk = 26
        targetSdk = 36
        // Must always exceed the last build of the previous app (30).
        versionCode = 31
        versionName = "3.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasSigningConfig) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                // v1 as well as v2/v3: some OEM sideload installers still check it.
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        debug {
            // Left unminified on purpose: fast incremental builds and readable stack
            // traces. Release carries the shrinking; the rules were smoke-tested by
            // temporarily enabling them here.
            isMinifyEnabled = false
            // Overridden per-build-type, so the two environments can never be confused at
            // runtime. Point this back at https://staging-api.arthax.ai/ to keep debug
            // builds off the live database while developing.
            buildConfigField("String", "API_BASE_URL", "\"https://api.arthax.ai/\"")
            buildConfigField("String", "API_ENVIRONMENT", "\"production\"")
        }
        release {
            // Strips unused Compose, Hilt, Retrofit and coroutines code. Rules live in
            // proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasSigningConfig) signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Production. The label rides along with the URL on purpose: it is shown on the
            // Settings screen so a rep can tell support which backend they are on, and a
            // build that says "staging" while talking to the live database is worse than
            // no label at all.
            buildConfigField("String", "API_BASE_URL", "\"https://api.arthax.ai/\"")
            buildConfigField("String", "API_ENVIRONMENT", "\"production\"")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            // android.util.Log is stubbed in unit tests; without this every call throws.
            isReturnDefaultValues = true
        }
    }
    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/INDEX.LIST",
                "/META-INF/DEPENDENCIES"
            )
        }
    }
}

androidComponents {
    // AGP 9 only registers unit tests for the debug build type. The suite is compiled and
    // run against the release variant as well, because that is the variant that ships:
    // BuildConfig constants and the minified classpath are what the tests should see.
    beforeVariants(selector().withBuildType("release")) { variant ->
        variant.hostTests[HostTestBuilder.UNIT_TEST_TYPE]?.enable = true
    }
}

kotlin {
    compilerOptions {
        // Kotlin 2.3 will start applying constructor-parameter annotations to the
        // generated property as well. Opting in now keeps Hilt qualifiers and Moshi
        // @Json working identically after that change instead of silently shifting.
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
}

dependencies {
    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.navigation.compose)

    // Core / lifecycle
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.kotlinx.coroutines.android)

    // DI
    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.hilt.compiler)
    ksp(libs.androidx.hilt.compiler)

    // Persistence
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    // Background work
    implementation(libs.androidx.work.runtime.ktx)

    // Networking
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.moshi)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.moshi)
    ksp(libs.moshi.kotlin.codegen)

    // Test
    testImplementation(libs.junit)
    // The real org.json, which shadows the empty android.jar stub so error-body
    // parsing can actually be tested on the JVM.
    testImplementation(libs.org.json)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.work.testing)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

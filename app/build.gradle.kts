import java.net.URI
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Explicit A/B build for diagnosing a native-core regression.
val diagnosticXrayDir = providers.gradleProperty("splateXrayDir").orNull
val diagnosticXrayVersion = providers.gradleProperty("splateXrayVersion").orNull
require((diagnosticXrayDir == null) == (diagnosticXrayVersion == null)) {
    "splateXrayDir and splateXrayVersion must be supplied together"
}

val privateProperties = Properties().apply {
    val propertiesFile = rootProject.file("splate-private.properties")
    if (propertiesFile.isFile) propertiesFile.inputStream().use { load(it) }
}
val signingKeys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
val releaseSigning = signingKeys.associateWith { privateProperties.getProperty(it) }
val hasReleaseSigning = releaseSigning.values.any { it != null }
require(!hasReleaseSigning || releaseSigning.values.all { !it.isNullOrBlank() }) {
    "Set all four release signing properties in splate-private.properties."
}

val privateSubscriptionUrl = privateProperties.getProperty("privateSubscriptionUrl", "").trim()
require(privateSubscriptionUrl.isEmpty() || URI(privateSubscriptionUrl).let {
    it.scheme == "https" && it.host != null && it.userInfo == null
}) { "The private subscription must have an HTTPS URL without user information." }

android {
    namespace = "com.danieldk.splatevpn"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.danieldk.splatevpn"
        minSdk = 26
        targetSdk = 35
        versionCode = 16
        versionName = if (diagnosticXrayDir == null) "1.3.1" else "1.3.1-core-compare"
        buildConfigField("String", "XRAY_VERSION", "\"${diagnosticXrayVersion ?: "26.11.9"}\"")

        buildConfigField("String", "PRIVATE_SUBSCRIPTION_URL", "\"$privateSubscriptionUrl\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a", "x86_64"))
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
    if (diagnosticXrayDir != null) {
        val coreDir = file(diagnosticXrayDir)
        listOf("arm64-v8a", "armeabi-v7a", "x86_64").forEach { abi ->
            listOf("libxray.so", "libhev-socks5-tunnel.so").forEach { library ->
                require(coreDir.resolve("$abi/$library").isFile) { "Missing diagnostic native library: $abi/$library" }
            }
        }
        sourceSets.getByName("main").jniLibs.setSrcDirs(listOf(coreDir))
    }

    if (hasReleaseSigning) {
        signingConfigs.create("release") {
            storeFile = file(requireNotNull(releaseSigning["storeFile"]))
            storePassword = releaseSigning["storePassword"]
            keyAlias = releaseSigning["keyAlias"]
            keyPassword = releaseSigning["keyPassword"]

            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    bundle { language { enableSplit = false } }
    packaging {
        jniLibs {
            // HEV runs through JNI. Its standalone PIE executable is not a shared
            // library and must not be scanned as one by Android's page-size checker.
            excludes.add("**/libhevsockstun.so")
            useLegacyPackaging = true
            keepDebugSymbols.add("**/*.so")
        }
    }

    applicationVariants.all {
        val variant = this
        outputs.all {
            val output = this as? com.android.build.gradle.internal.api.BaseVariantOutputImpl
            val abi = output?.getFilter("ABI") ?: "universal"
            output?.outputFileName = "SplateVPN-${variant.versionName}-$abi.apk"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    // Navigation Compose
    implementation(libs.androidx.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Gson
    implementation(libs.gson)
    implementation("com.google.mlkit:translate:17.0.3")

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)

    // Offline QR scanner (no Play Services required).
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    // Embedded Tor and pluggable transports; all processing stays in this app.
    implementation(project(":tor-android"))
    implementation("com.netzarchitekten:IPtProxy:5.7.0")
    implementation("com.google.re2j:re2j:1.8")

    // QR Code Generator (ZXing Core)
    implementation(libs.zxing.core)

    // OkHttp for robust HTTP/HTTPS redirects and subscription downloading
    implementation(libs.okhttp)
    // Proxy override is confined to the separate SmartDNS verification process.
    implementation("androidx.webkit:webkit:1.12.1")

    testImplementation(libs.junit)
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    androidTestImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(platform(libs.androidx.compose.bom))
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

// Keep versioned schemas for migration validation and release review.
ksp { arg("room.schemaLocation", "$projectDir/schemas") }

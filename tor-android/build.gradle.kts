plugins { id("com.android.library") }

android {
    namespace = "org.torproject.jni"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
        ndk { abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a", "x86_64")) }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api("info.guardianproject:jtorctl:0.4.5.7")
}

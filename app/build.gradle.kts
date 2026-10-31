plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.robd.robvoice"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.robd.robvoice"
        minSdk = 29
        targetSdk = 35
        versionCode = 7
        versionName = "0.3.0"
        ndk { abiFilters += (project.findProperty("abis") as String? ?: "arm64-v8a").split(",") } // 64-bit ARM Android phones
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs { useLegacyPackaging = true } }
}
dependencies {
    // sherpa-onnx (Apache-2.0), prebuilt AAR from github.com/k2-fsa/sherpa-onnx/releases v2.0.1 (bundles onnxruntime, MIT)
    implementation(files("libs/sherpa-onnx-2.0.1.aar"))
}
dependencies { testImplementation("junit:junit:4.13.2") }

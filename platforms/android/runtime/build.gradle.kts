plugins { id("com.android.library") }

android {
    namespace = "dev.podjs.runtime"
    compileSdk = 36
    defaultConfig {
        minSdk = 30
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
        externalNativeBuild { cmake { cppFlags += listOf("-std=c++20", "-fno-exceptions", "-fno-rtti") } }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    buildFeatures { prefab = true }
}

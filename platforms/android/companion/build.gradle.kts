plugins { id("com.android.library") }

android {
    namespace = "dev.podjs.companion"
    compileSdk = 36
    defaultConfig {
        minSdk = 30
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }
}

dependencies {
    api(project(":runtime"))
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("junit:junit:4.13.2")
}

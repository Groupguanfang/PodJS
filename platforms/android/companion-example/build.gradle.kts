plugins { id("com.android.application") }
android {
    namespace = "dev.podjs.companion.example"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.podjs.companion.example"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}
dependencies {
    implementation(project(":companion"))
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("junit:junit:4.13.2")
}

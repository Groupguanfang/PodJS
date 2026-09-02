plugins { id("com.android.application") }
android {
    namespace = "dev.podjs.androidwatch"; compileSdk = 36
    defaultConfig { applicationId = "dev.podjs.androidwatch"; minSdk = 30; targetSdk = 36; versionCode = 1; versionName = "0.1.0"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    sourceSets["main"].assets.srcDir("../../../dist/android-watch")
}
dependencies {
    implementation(project(":runtime"))
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}

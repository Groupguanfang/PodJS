plugins { id("com.android.application") }
android {
    namespace = "dev.podjs.androidwatch"; compileSdk = 36
    defaultConfig { applicationId = "dev.podjs.androidwatch"; minSdk = 30; targetSdk = 36; versionCode = 1; versionName = "0.1.0" }
    sourceSets["main"].assets.srcDir("../../../dist/android-watch")
}
dependencies { implementation(project(":runtime")) }

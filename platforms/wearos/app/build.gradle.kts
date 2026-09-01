plugins { id("com.android.application") }
android {
    namespace = "dev.podjs.wear"; compileSdk = 36
    defaultConfig { applicationId = "dev.podjs.wear"; minSdk = 30; targetSdk = 36; versionCode = 1; versionName = "0.1.0" }
    sourceSets["main"].assets.srcDir("../../../dist/wearos-watch")
}
dependencies { implementation(project(":runtime")) }

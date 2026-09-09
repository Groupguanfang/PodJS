plugins { id("com.android.library") }

// Test package facts come from the same checkout as the JNI host. A stale
// prebuilt Rust revision fails package validation rather than bypassing it.
val semanticTestAssets = layout.buildDirectory.dir("generated/semanticTestAssets")
val semanticTestRevision = providers.exec {
    workingDir(rootProject.file("../../vendor/pocketjs"))
    commandLine("git", "rev-parse", "HEAD")
}.standardOutput.asText.map { it.trim() }
val generateSemanticTestAssets = tasks.register("generateSemanticTestAssets") {
    val bridge = file("src/main/cpp/jni_bridge.cpp")
    inputs.file(bridge)
    inputs.property("revision", semanticTestRevision)
    outputs.dir(semanticTestAssets)
    doLast {
        val literal = Regex("const char\\* capabilities = \"([^\\n]+)\";").find(bridge.readText())?.groupValues?.get(1)
            ?: error("Missing native host capability list")
        val capabilities = literal.replace("\\\"", "\"")
        val directory = semanticTestAssets.get().asFile
        directory.mkdirs()
        directory.resolve("semantic-host.json").writeText("{\"pocketjsRevision\":\"${semanticTestRevision.get()}\",\"capabilities\":$capabilities}")
    }
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("AndroidTestAssets") }.configureEach {
    dependsOn(generateSemanticTestAssets)
}

android {
    namespace = "dev.podjs.runtime"
    compileSdk = 36
    defaultConfig {
        minSdk = 30
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
        externalNativeBuild { cmake { cppFlags += listOf("-std=c++20", "-fno-exceptions", "-fno-rtti") } }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    buildFeatures { prefab = true }
    sourceSets.getByName("androidTest").assets.srcDir(semanticTestAssets)
}

dependencies {
    implementation("androidx.work:work-runtime:2.11.2")
    implementation("com.google.zxing:core:3.5.2")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.5.1")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("junit:junit:4.13.2")
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    buildFeatures { buildConfig = true }
    namespace = "io.bbui.assistant"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.bbui.assistant"
        minSdk = 30
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.0-preview.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/chat-assets"))
    sourceSets["test"].resources.srcDir(rootProject.file("../pi/android/fixtures"))
}

val syncChatAssets by tasks.registering(Sync::class) {
    from(rootProject.file("chat-ui/dist"))
    into(layout.buildDirectory.dir("generated/chat-assets/chat"))
    doFirst {
        check(rootProject.file("chat-ui/dist/index.html").isFile) {
            "Build local chat assets first: npm --prefix android/chat-ui ci --ignore-scripts && npm --prefix android/chat-ui run build"
        }
    }
}
tasks.named("preBuild").configure { dependsOn(syncChatAssets) }

dependencies {
    implementation(project(":core"))
    implementation(project(":runtime"))
    implementation(project(":device"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

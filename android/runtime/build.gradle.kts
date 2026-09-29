plugins {
  id("com.android.library")
  id("org.jetbrains.kotlin.android")
}

android {
  namespace = "io.bbui.runtime"
  compileSdk = 36
  ndkVersion = "28.2.13676358"
  defaultConfig {
    minSdk = 30
    ndk { abiFilters += "arm64-v8a" }
    externalNativeBuild {
      cmake {
        cppFlags += "-std=c++20"
        arguments += "-DANDROID_STL=c++_shared"
      }
    }
  }
  externalNativeBuild {
    cmake {
      path = file("src/main/cpp/CMakeLists.txt")
      version = "3.22.1"
    }
  }
  sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/runtime-assets"))
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlinOptions { jvmTarget = "17" }
  packaging { jniLibs.useLegacyPackaging = true }
}

dependencies {
  implementation(project(":core"))
  testImplementation("junit:junit:4.13.2")
}

// Run scripts/android-runtime/prepare.mjs once before Gradle. Keeping this an
// explicit host step avoids npm/network activity on the user's phone or during IDE sync.
tasks.register("verifyRuntimeAssets") {
  doLast {
    check(file("src/main/jniLibs/arm64-v8a/libnode.so").isFile) {
      "Missing pinned libnode: run node scripts/android-runtime/prepare.mjs at repository root"
    }
    check(layout.buildDirectory.file("generated/runtime-assets/pi-runtime.zip").get().asFile.isFile) {
      "Missing Pi payload: run node scripts/android-runtime/prepare.mjs at repository root"
    }
  }
}
tasks.named("preBuild").configure { dependsOn("verifyRuntimeAssets") }

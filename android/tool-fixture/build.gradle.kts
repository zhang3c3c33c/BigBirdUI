plugins { id("com.android.application") }

android {
    namespace = "io.bbui.toolfixture"
    compileSdk = 36
    defaultConfig {
        applicationId = providers.gradleProperty("fixtureId").orElse("io.bbui.toolfixture").get()
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "test"
    }
}

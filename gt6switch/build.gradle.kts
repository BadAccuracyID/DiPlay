plugins { alias(libs.plugins.android.application) }
android {
    namespace = "com.shilapi.xcertplay.gt6switch"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.efran.carplayswitch"
        minSdk = 33
        targetSdk = 37
        versionCode = 5
        versionName = "0.5.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
dependencies {
    implementation(project(":shared"))
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.r3dzf.reader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.r3dzf.reader"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "0.7.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
    }
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/readerFonts/assets"))
    sourceSets.getByName("androidTest").assets.srcDir("src/test/resources/fixtures")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.02.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.02.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Fonts are packaged inside the APK. Runtime reading never downloads anything.
val prepareReaderFonts by tasks.registering(Exec::class) {
    inputs.files(rootProject.file("tools/prepare_fonts.py"), rootProject.file("tools/fonts-manifest.json"))
    outputs.dir(layout.buildDirectory.dir("generated/readerFonts/assets"))
    commandLine("python3", rootProject.file("tools/prepare_fonts.py").absolutePath,
        "--output", layout.buildDirectory.dir("generated/readerFonts/assets").get().asFile.absolutePath)
}
tasks.named("preBuild").configure { dependsOn(prepareReaderFonts) }

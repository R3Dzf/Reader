import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val officeRendererVersion = "0.80.2"
val officeRendererArchive = configurations.create("officeRendererArchive") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

android {
    namespace = "com.r3dzf.reader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.r3dzf.reader"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    add(officeRendererArchive.name, "org.webjars.npm:silurus__ooxml:$officeRendererVersion")
    implementation(platform("androidx.compose:compose-bom:2025.02.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.webkit:webkit:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("com.microsoft.identity.client:msal:8.4.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}

val officeRendererAssets = layout.buildDirectory.dir("generated/officeRenderer/assets")
android.sourceSets.getByName("main").assets.srcDir(officeRendererAssets)

val packageOfficeRenderer = tasks.register("packageOfficeRenderer") {
    val rendererJar = officeRendererArchive.elements.map { it.single().asFile }
    inputs.files(officeRendererArchive)
    outputs.dir(officeRendererAssets)

    doLast {
        val jarFile = rendererJar.get()
        val outputDirectory = officeRendererAssets.get().asFile.apply { mkdirs() }
        ZipFile(jarFile).use { source ->
            val marker = source.entries().asSequence()
                .firstOrNull { !it.isDirectory && it.name.endsWith("dist/docx.mjs") }
                ?: error("${jarFile.name} does not contain the expected OOXML renderer files")
            val distRoot = marker.name.removeSuffix("docx.mjs")
            val bundledZip = File(outputDirectory, "office-renderer.zip")
            ZipOutputStream(bundledZip.outputStream().buffered()).use { target ->
                for (entry in source.entries()) {
                    if (entry.isDirectory || !entry.name.startsWith(distRoot)) continue
                    val name = entry.name.removePrefix(distRoot)
                    if (name.isEmpty() || name.startsWith("types/") || name.endsWith(".d.ts") || name == "node.mjs") continue
                    target.putNextEntry(ZipEntry(name))
                    source.getInputStream(entry).use { it.copyTo(target) }
                    target.closeEntry()
                }

                val legalFiles = source.entries().asSequence().filter { entry ->
                    !entry.isDirectory && entry.name.substringAfterLast('/') in setOf("LICENSE", "THIRD_PARTY_NOTICES.md")
                }.toList()
                for (entry in legalFiles) {
                    val name = "licenses/${entry.name.substringAfterLast('/')}-${legalFiles.indexOf(entry)}"
                    target.putNextEntry(ZipEntry(name))
                    source.getInputStream(entry).use { it.copyTo(target) }
                    target.closeEntry()
                }
            }
        }
        File(outputDirectory, "office-renderer-version.txt").writeText(officeRendererVersion)
    }
}

tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("Assets")) dependsOn(packageOfficeRenderer)
}

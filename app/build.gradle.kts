import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import org.gradle.api.attributes.Usage

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Keep the upstream extractor, replacing only HevcConfig with the source below.
// Media3 1.6.1 reads layerInfos[0] for 3D SEI even when a normal HEVC VPS has no layers.
val extractorOriginal by configurations.creating {
    isTransitive = false
    attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
}
val patchedExtractorJar = layout.buildDirectory.file("compat/media3-extractor-1.6.1.jar")
val prepareExtractorCompat by tasks.registering {
    inputs.files(extractorOriginal)
    outputs.file(patchedExtractorJar)
    doLast {
        val target = patchedExtractorJar.get().asFile
        target.parentFile.mkdirs()
        ZipFile(extractorOriginal.singleFile).use { aar ->
            ZipInputStream(aar.getInputStream(aar.getEntry("classes.jar"))).use { source ->
                ZipOutputStream(target.outputStream()).use { output ->
                    var entry = source.nextEntry
                    while (entry != null) {
                        if (entry.name != "androidx/media3/extractor/HevcConfig.class") {
                            output.putNextEntry(ZipEntry(entry.name).apply { time = 0 })
                            source.copyTo(output); output.closeEntry()
                        }
                        entry = source.nextEntry
                    }
                }
            }
        }
    }
}
configurations.configureEach {
    if (name != extractorOriginal.name) exclude(group = "androidx.media3", module = "media3-extractor")
}
android {
    namespace = "app.biliflow"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.biliflow"
        minSdk = 26
        targetSdk = 35
        versionCode = 24
        versionName = "0.15.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            // Preserve update compatibility with previous local installations.
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro", "compat/media3-extractor-consumer-rules.pro")
        }
    }
}
dependencies {
    extractorOriginal("androidx.media3:media3-extractor:1.6.1")
    implementation(files(patchedExtractorJar).builtBy(prepareExtractorCompat))
    implementation("androidx.media3:media3-container:1.6.1")
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-gif:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.media3:media3-exoplayer:1.6.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.6.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.6.1")
    implementation("androidx.media3:media3-session:1.6.1")
    implementation("androidx.media3:media3-ui:1.6.1")
    implementation("dev.chrisbanes.haze:haze:1.6.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

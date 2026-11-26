import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun propOrEnv(name: String): String? =
    (project.findProperty(name) as String?)?.takeIf { it.isNotBlank() } ?: System.getenv(name)?.takeIf { it.isNotBlank() }

android {
    namespace = "app.kinostash.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.kinostash.android"
        minSdk = 28
        targetSdk = 35
        versionCode = propOrEnv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = propOrEnv("VERSION_NAME") ?: "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // Release signing comes from a private keystore provided via environment / Gradle properties
    // (CI secrets). It is never committed. Without it, release builds are unsigned.
    val keystorePath = propOrEnv("KINOSTASH_KEYSTORE")
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = propOrEnv("KINOSTASH_KEYSTORE_PASSWORD")
                keyAlias = propOrEnv("KINOSTASH_KEY_ALIAS")
                keyPassword = propOrEnv("KINOSTASH_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8 shrinking is off until the release build has been verified on devices (a minified
            // 0.1.0 crashed on launch while the identical debug build runs).
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        // Extract native libs: the Node launcher (libstremionode.so) is executed as a process,
        // which Android only allows from the app's native library directory.
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.jakewharton.timber:timber:5.0.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// ---------------------------------------------------------------------------------------------
// nodejs-mobile runtime (libnode.so, MIT) that runs Stremio's streaming server. Too large for git
// (~60 MB per ABI), so it is downloaded at build time and checksum-verified.
// ---------------------------------------------------------------------------------------------
val nodejsMobileVersion = "v18.20.4"
val nodejsMobileSha256 = "bd7321eaa1a7602fbe0bb87302df2d79d87835cf4363fbdd17c350dbb485c2af"
val nodejsMobileJniDir = layout.buildDirectory.dir("generated/nodejs-mobile/jniLibs")

val fetchNodejsMobile = tasks.register("fetchNodejsMobile") {
    val outDir = nodejsMobileJniDir.get().asFile
    val zipFile = layout.buildDirectory.file("tmp/nodejs-mobile-$nodejsMobileVersion-android.zip").get().asFile
    val url = "https://github.com/nodejs-mobile/nodejs-mobile/releases/download/$nodejsMobileVersion/nodejs-mobile-$nodejsMobileVersion-android.zip"
    val expected = nodejsMobileSha256
    outputs.dir(outDir)
    doLast {
        fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
        if (!zipFile.exists() || sha256(zipFile) != expected) {
            zipFile.parentFile.mkdirs()
            URI(url).toURL().openStream().use { input -> zipFile.outputStream().use { input.copyTo(it) } }
        }
        check(sha256(zipFile) == expected) { "nodejs-mobile checksum mismatch" }
        ZipFile(zipFile).use { zip ->
            for (abi in listOf("arm64-v8a", "armeabi-v7a")) {
                val entry = zip.getEntry("bin/$abi/libnode.so") ?: error("libnode.so missing for $abi")
                val dest = File(outDir, "$abi/libnode.so")
                dest.parentFile.mkdirs()
                zip.getInputStream(entry).use { input -> dest.outputStream().use { input.copyTo(it) } }
            }
        }
    }
}
android.sourceSets.getByName("main").jniLibs.srcDir(nodejsMobileJniDir.get().asFile)
tasks.named("preBuild") { dependsOn(fetchNodejsMobile) }

tasks.withType<Test>().configureEach {
    // Robolectric downloads Android system images itself; use Google's Maven Central mirror.
    systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2/")
    testLogging { exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL; events("failed") }
}

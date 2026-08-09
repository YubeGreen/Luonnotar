import java.util.Properties
import org.gradle.api.tasks.Copy

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseSigningProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.isFile) file.inputStream().use(::load)
}
val hasReleaseSigning = listOf(
    "storeFile",
    "storePassword",
    "keyAlias",
    "keyPassword"
).all { !releaseSigningProperties.getProperty(it).isNullOrBlank() }

android {
    namespace = "com.yubegreen.luonnotar"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.yubegreen.luonnotar"
        minSdk = 26
        targetSdk = 36
        versionCode = 117
        versionName = "2.6.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "DEVELOPER_NAME", "\"YubeGreen\"")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseSigningProperties.getProperty("storeFile"))
                storePassword = releaseSigningProperties.getProperty("storePassword")
                keyAlias = releaseSigningProperties.getProperty("keyAlias")
                keyPassword = releaseSigningProperties.getProperty("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        getByName("debug") {
            isDebuggable = true
        }
        getByName("release") {
            isDebuggable = false
            isJniDebuggable = false
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    buildFeatures {
        buildConfig = true
        aidl = true
    }

    packaging {
        resources {
            excludes += "META-INF/DEPENDENCIES"
        }
    }
}

android.applicationVariants.all {
    val apkVersionName = versionName ?: "unknown"
    val apkBuildType = buildType.name

    outputs.all {
        (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl)
            .outputFileName =
            "Luonnotar-$apkVersionName-YubeGreen-$apkBuildType.apk"
    }
}

val copyReleaseApkToProjectRoot = tasks.register("copyReleaseApkToProjectRoot") {
    doLast {
        val releaseDir = layout.buildDirectory
            .dir("outputs/apk/release")
            .get()
            .asFile

        val sourceApk = releaseDir
            .listFiles()
            ?.singleOrNull {
                it.isFile &&
                    it.name.startsWith("Luonnotar-") &&
                    it.name.endsWith("-YubeGreen-release.apk")
            }
            ?: error("Expected exactly one YubeGreen release APK in: $releaseDir")

        val destinationApk = rootProject.file(sourceApk.name)

        sourceApk.copyTo(
            target = destinationApk,
            overwrite = true
        )

        println("Copied release APK to: ${destinationApk.absolutePath}")
    }
}

tasks.matching { it.name == "assembleRelease" }.configureEach {
    finalizedBy(copyReleaseApkToProjectRoot)
}

dependencies {
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("com.google.android.gms:play-services-base:18.10.0")
    implementation("com.google.android.gms:play-services-location:21.4.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("com.flyfishxu:kadb-android:1.3.0")
    implementation("org.apache.sshd:sshd-core:2.19.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.84")
    implementation("org.slf4j:slf4j-nop:2.0.18")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core:1.6.1")
}

// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

@file:Suppress("UnstableApiUsage")

import com.android.build.api.variant.HasHostTestsBuilder
import com.android.build.api.variant.HostTestBuilder
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val libclashLocalAar: String? = providers.gradleProperty("asterisk.libclash.localAar").orNull?.takeIf(String::isNotBlank)
//noinspection UseTomlInstead
val libclashDependency: Any = libclashLocalAar?.let { files(it) }
    ?: "com.github.Asterisk4Magisk:libclash:${ProjectConfig.ANDROID_LIB_CLASH_LITE_VERSION}@aar"

val generatedSrcDir: Provider<Directory> = layout.buildDirectory.dir("generated/projectInfo")

android {
    namespace = "app"
    compileSdk {
        version = release(ProjectConfig.TARGET_SDK) {
            minorApiLevel = ProjectConfig.TARGET_SDK_MINOR
        }
    }

    // Built-in Kotlin inherits this JVM target.
    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(ProjectConfig.JVM_VERSION)
        targetCompatibility = JavaVersion.toVersion(ProjectConfig.JVM_VERSION)
    }

    defaultConfig {
        applicationId = ProjectConfig.PACKAGE_NAME
        minSdk = ProjectConfig.MIN_SDK
        targetSdk = ProjectConfig.TARGET_SDK
        versionCode = getGitVersionCode()
        versionName = ProjectConfig.VERSION_NAME
    }

    androidResources {
        localeFilters += listOf("en", "zh-rCN")
    }

    bundle {
        language {
            enableSplit = false
        }
    }

    buildFeatures {
        compose = true
    }

    splits {
        abi {
            isEnable = true
            reset()
            include(*ProjectConfig.SUPPORTED_ANDROID_ABIS.toTypedArray())
            isUniversalApk = true
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }

        release {
            isDebuggable = false
            isJniDebuggable = false
            isPseudoLocalesEnabled = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "DebugProbesKt.bin",
                "META-INF/*.kotlin_module",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/LICENSE",
                "META-INF/LICENSE.md",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.md",
                "META-INF/NOTICE.txt",
                "META-INF/versions/**",
            )
        }
    }

    lint {
        disable += setOf(
            "ChromeOsAbiSupport",
            "IconLauncherShape",
        )
    }
}

tasks.named("preBuild") {
    dependsOn(rootProject.tasks.named("updateResourceFileAssets"))
}

dependencies {
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.material3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigationevent)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.activity.compose)
    implementation(libs.coil)
    implementation(libs.coil.compose)
    implementation(libs.dexlib2)
    implementation(libs.haze)
    implementation(libs.haze.blur)
    implementation(libclashDependency)
    implementation(dependencies.project(":asteriskd"))
    implementation(dependencies.project(":enhance"))
    implementation(dependencies.project(":bpfmatcher"))
    implementation(dependencies.project(":bpf2socks"))
    implementation(dependencies.project(":hevtun"))
    implementation(libs.ktor.http)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.libsu.core)
    implementation(libs.material.kolor)
    implementation(libs.quickjs.kt.android)
    implementation(libs.snakeyaml.engine) {
        exclude(group = "org.junit.jupiter", module = "junit-jupiter-api")
    }
    implementation(libs.sora.editor)
    implementation(libs.zxing.android.embedded)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// The released core AAR is pinned by tag and SHA-256; a local build replaces it for development.
val libclashArtifact: Configuration = configurations.create("libclashArtifact") {
    isCanBeConsumed = false
    isTransitive = false
}
dependencies {
    libclashArtifact(libclashDependency)
}
val verifyLibclashAar = tasks.register("verifyLibclashAar") {
    description = "Checks the SHA-256 of the AndroidLibClashLite AAR"
    val aar: FileCollection = libclashArtifact
    val expected = if (libclashLocalAar == null) ProjectConfig.ANDROID_LIB_CLASH_LITE_SHA256 else null
    inputs.files(aar)
    doLast {
        if (expected == null) return@doLast
        val digest = MessageDigest.getInstance("SHA-256")
        aar.singleFile.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(actual == expected) { "libclash.aar SHA-256 mismatch: expected $expected, got $actual" }
    }
}
tasks.named("preBuild") { dependsOn(verifyLibclashAar) }

val generateProjectInfo = tasks.register<GenerateProjectInfoTask>("generateProjectInfo") {
    description = "Generate ProjectInfo object for the app"
    packageName.set("app")
    projectName.set(ProjectConfig.PROJECT_NAME)
    versionName.set(ProjectConfig.VERSION_NAME)
    versionCode.set(getGitVersionCode())
    androidLibClashLiteVersion.set(ProjectConfig.ANDROID_LIB_CLASH_LITE_VERSION)
    hevSocks5TunnelVersion.set(ProjectConfig.HEV_SOCKS5_TUNNEL_VERSION)
    outputDirectory.set(generatedSrcDir.map { it.dir("kotlin") })
}

androidComponents {
    beforeVariants(selector().all()) { variant ->
        variant.enableAndroidTest = false
        (variant as? HasHostTestsBuilder)
            ?.hostTests
            ?.get(HostTestBuilder.UNIT_TEST_TYPE)
            // JVM unit tests cover side-effect-free policy code; keep them debug-only.
            ?.enable = variant.buildType == "debug"
    }

    onVariants { variant ->
        variant.sources.kotlin?.addGeneratedSourceDirectory(generateProjectInfo) { task ->
            task.outputDirectory
        }
        variant.sources.assets?.addStaticSourceDirectory("build/generated/resourceFileAssets")
    }
}

tasks.matching { it.name.startsWith("ksp") }.configureEach {
    dependsOn(generateProjectInfo)
}

val aboutLibrariesJsonFile = layout.projectDirectory.file("src/main/assets/aboutlibraries.json")

val updateAboutLibrariesJson = tasks.register<GenerateAboutLibrariesJsonTask>("updateAboutLibrariesJson") {
    group = "documentation"
    description = "Update files/aboutlibraries.json from current app dependency metadata."
    outputFile.set(aboutLibrariesJsonFile)
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    mustRunAfter(updateAboutLibrariesJson)
    if (!aboutLibrariesJsonFile.asFile.exists()) {
        dependsOn(updateAboutLibrariesJson)
    }
}

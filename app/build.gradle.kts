plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "chat.ratatosk.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "chat.ratatosk.android"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("x86_64", "arm64-v8a")
            isUniversalApk = true
        }
    }

    sourceSets.getByName("main") {
        java.srcDir("src/main/java")
    }
}

dependencies {
    implementation("net.java.dev.jna:jna:5.19.1@aar")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.commonmark)
    implementation(libs.commonmark.autolink)
    implementation(libs.commonmark.strikethrough)
    implementation(libs.commonmark.tables)
    implementation(libs.coil.compose)
    implementation(libs.qr.code.generator)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// Rust / UniFFI Integration
val rustProjectDir = file("../../ratatosk-core")
val generatedDir = file("src/main/java")
val jniLibsDir = file("src/main/jniLibs")

val buildRustCore = tasks.register<Exec>("buildRustCore") {
    workingDir = rustProjectDir
    // For emulator (x86_64) and modern devices (arm64-v8a)
    commandLine(
        "cargo", "ndk",
        "-t", "x86_64",
        "-t", "arm64-v8a",
        "-o", jniLibsDir.absolutePath,
        "build", "-p", "ratatosk-ffi", "--lib"
    )
    
    inputs.dir(rustProjectDir.resolve("crates"))
    outputs.dir(jniLibsDir)
}

val buildRustHost = tasks.register<Exec>("buildRustHost") {
    workingDir = rustProjectDir
    commandLine("cargo", "build", "-p", "ratatosk-ffi", "--lib")
    
    inputs.dir(rustProjectDir.resolve("crates"))
    outputs.file(rustProjectDir.resolve("target/debug/libratatosk_ffi.so"))
}

tasks.register<Exec>("generateUniFFIBindings") {
    dependsOn(buildRustHost)
    workingDir = rustProjectDir

    val hostLibPath = rustProjectDir.resolve("target/debug/libratatosk_ffi.so")

    commandLine(
        "cargo", "run", "-p", "ratatosk-bindgen", "--bin", "uniffi-bindgen",
        "generate", "--library", hostLibPath.absolutePath,
        "--language", "kotlin", "--out-dir", generatedDir.absolutePath
    )

    inputs.dir(rustProjectDir.resolve("crates/ffi"))
    outputs.dir(generatedDir)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn("generateUniFFIBindings")
}

tasks.configureEach {
    if (name.contains("merge") && (name.contains("NativeLibs") || name.contains("JniLibFolders"))) {
        dependsOn(buildRustCore)
    }
}

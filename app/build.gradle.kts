import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Ключ подписи описан в `keystore.properties` рядом с настройками проекта.
// В репозитории этого файла нет и быть не должно: в нём пароли. Образец —
// `keystore.properties.example`.
//
// Файла нет — release подписывается отладочным ключом, как и раньше: иначе
// собрать приложение не смог бы никто, кроме владельца ключа. Чем это
// плохо, сказано ниже у самой подписи.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val hasReleaseKey = keystorePropertiesFile.exists()
val keystoreProperties = Properties().apply {
    if (hasReleaseKey) keystorePropertiesFile.inputStream().use { load(it) }
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

    signingConfigs {
        // Конфигурация заводится, только когда есть чем её заполнить:
        // пустая `release`-подпись — это APK, который не поставится, а
        // узнаётся это в самом конце сборки.
        if (hasReleaseKey) {
            create("release") {
                // Недостающее поле — это остановка сборки, а не подстановка
                // умолчания. Пропущенный пароль иначе обернулся бы невнятным
                // отказом где-то в подписывальщике.
                fun required(name: String): String =
                    keystoreProperties.getProperty(name)?.takeIf { it.isNotBlank() }
                        ?: throw GradleException(
                            "keystore.properties: не задано `$name` " +
                                "(образец — keystore.properties.example)"
                        )

                val store = required("storeFile").let { path ->
                    val named = File(path)
                    // Относительный путь считаем от корня проекта, а не от
                    // текущего каталога: иначе сборка зависела бы от того,
                    // откуда её запустили.
                    if (named.isAbsolute) named else rootProject.file(path)
                }
                if (!store.isFile) {
                    throw GradleException("keystore.properties: хранилище не найдено — $store")
                }

                storeFile = store
                storePassword = required("storePassword")
                keyAlias = required("keyAlias")
                keyPassword = required("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Без своего ключа release подписывается **отладочным**, и это
            // не мелочь, а прямое ограничение на раздачу:
            //
            //  * отладочный ключ общий для всех: пароль (`android`) и
            //    псевдоним (`androiddebugkey`) записаны в документации
            //    Android, так что подделать такую подпись может кто угодно.
            //    Для мессенджера, где подпись — единственное, чем система
            //    отличает обновление от подмены, это значит: раздавать
            //    такой APK нельзя;
            //  * в Google Play он не уйдёт — подписанное отладочным ключом
            //    там не принимают;
            //  * лежит он в `~/.android/debug.keystore`, то есть у каждого
            //    свой: собранное на одной машине не обновится поверх
            //    собранного на другой.
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                logger.warn(
                    "Ratatosk: keystore.properties нет — release подписывается " +
                        "отладочным ключом. Такую сборку раздавать нельзя."
                )
                signingConfigs.getByName("debug")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    testOptions {
        unitTests {
            // Проверкам моделей нужен `android.util.Log`: без этого любой
            // вызов журнала в них падает «Method d in android.util.Log not
            // mocked». Ни одна проверка на журнал не смотрит.
            isReturnDefaultValues = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        // Нужен, чтобы отличать отладочную сборку от готовой: журнал ядра
        // включается только в первой.
        buildConfig = true
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
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.androidx.compose.material3.adaptive.layout)
    implementation(libs.androidx.compose.material3.adaptive.navigation)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
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
    implementation("androidx.documentfile:documentfile:1.0.1")
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
    implementation(libs.androidx.camera.video)
    testImplementation(libs.junit)
    // Проверки моделей без ядра: `Dispatchers.setMain` и управляемое время.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
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
        "build", "--profile", "release-android", "-p", "ratatosk-ffi", "--lib", "--features", "tor mail ygg-node nostr"
    )
    
    inputs.dir(rustProjectDir.resolve("crates"))
    outputs.dir(jniLibsDir)
}

val buildRustHost = tasks.register<Exec>("buildRustHost") {
    workingDir = rustProjectDir
    commandLine("cargo", "build", "-p", "ratatosk-ffi", "--lib", "--features", "tor mail ygg-node nostr")
    
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

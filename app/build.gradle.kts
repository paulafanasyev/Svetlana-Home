import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.svetlana.home"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.svetlana.home"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
        // llama.cpp runtime собран только под arm64-v8a (POCO X3 NFC = ARM64).
        // x86_64 добавлен специально для CI: hosted-раннер GitHub — x86_64
        // эмулятор, и без этого ABI instrumented-тесты физически не могут
        // установиться ("0 of which were compatible"). На x86_64 нативный
        // llama.cpp не загрузится — runtime честно сообщит об этом через
        // UnsatisfiedLinkError, а вся JVM-логика (launcher, registry,
        // permissions, owner, proof chain, translator) тестируется полноценно.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        // ТЗ §78: keystore никогда не хранится в репозитории.
        // Путь и пароли приходят через переменные окружения (CI/CD).
        create("release") {
            val storeFile = System.getenv("SVETLANA_STORE_FILE")
            val storePass = System.getenv("SVETLANA_STORE_PASSWORD")
            val keyAlias = System.getenv("SVETLANA_KEY_ALIAS")
            val keyPass = System.getenv("SVETLANA_KEY_PASSWORD")
            if (storeFile != null && File(storeFile).exists()) {
                this.storeFile = File(storeFile)
                this.storePassword = storePass
                this.keyAlias = keyAlias
                this.keyPassword = keyPass
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            val releaseConfig = signingConfigs.getByName("release")
            val envKeysProvided = System.getenv("SVETLANA_STORE_FILE") != null &&
                System.getenv("SVETLANA_STORE_PASSWORD") != null &&
                System.getenv("SVETLANA_KEY_ALIAS") != null &&
                System.getenv("SVETLANA_KEY_PASSWORD") != null
            if (envKeysProvided && releaseConfig.storeFile != null) {
                signingConfig = releaseConfig
            } else {
                // Без keystore в env — релиз не подписан (CI/CD должен задать секреты).
                signingConfig = null
            }
            isMinifyEnabled = true
            // R8: включаем оптимизацию и обфускацию для release.
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=kotlin.RequiresOptIn")
        // LiteRT-LM (аудит §10-12) публикуется с metadata Kotlin 2.4, а
        // проект собирается на Kotlin 1.9.22. Флаг подавляет фатальную
        // ошибку несовпадения версии метаданных; ABI стабилен, поэтому
        // чтение классов из Kotlin 1.9 корректно. Полный переход на
        // Kotlin 2.x — отдельная задача (требует обновления Compose
        // Compiler и KSP во всём проекте, аудит запрещает big-bang).
        freeCompilerArgs += listOf("-Xskip-metadata-version-check")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    lint {
        // Аудит §10-12: LiteRT-LM 0.17.1 тащит транзитивные kotlin 2.2/2.4
        // и kotlinx-coroutines 1.11 (metadata 2.2+), что ломает AGP 8.11
        // lint (kotlinx-metadata-jvm поддерживает ≤ 2.0) — краш на этапе
        // анализа. Исключения зависимостей выше решают проблему; этот
        // флаг оставлен как страховка для будущих транзитивных обновлений.
        // TODO: убрать после перехода проекта на Kotlin 2.x / AGP 8.13+.
        abortOnError = true
    }
}

/**
 * Юнит-тесты видят те же зависимости (включая LiteRT-LM с metadata
 * Kotlin 2.4), что и main — нужен тот же флаг.
 */
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions.freeCompilerArgs += listOf("-Xskip-metadata-version-check")
}

dependencies {
    // AndroidX base
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.9.0")
    // P0: Lifecycle 2.8.0 + Compose 1.6.x (BOM 2024.05.00) + R8 вызывают
    // IllegalStateException: CompositionLocal LocalLifecycleOwner not present.
    // Исправлено в Lifecycle 2.8.2 (CompositionLocal) и 2.8.3 (R8/back-compat).
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    // Выравниваем транзитивные lifecycle-модули (camera-lifecycle тянет 2.6.1
    // и вызывает DuplicateClass с viewmodel-android 2.8.3).
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-savedstate:2.8.3")
    implementation("androidx.lifecycle:lifecycle-process:2.8.3")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Security: шифрованное хранилище для API-ключей
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Biometric / владелец
    implementation("androidx.biometric:biometric:1.2.0-alpha05")

    // Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.05.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.foundation:foundation")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Сеть: внешние AI-провайдеры и personal server
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // CameraX для Vision / camera translation.
    // 1.4.x поставляет нативные библиотеки с 16 KB ELF-выравниванием
    // (libimage_processing_util_jni.so в 1.3.3 собрана с 4 KB p_align).
    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")

    // Локальный ИИ: llama.cpp (GGUF) — реальный on-device inference runtime.
    // MIT, arm64-v8a, CPU/NEON. Модель скачивается ТОЛЬКО по явному решению
    // пользователя (ТЗ §32, §33, §87) — библиотека сама ничего не качает.
    implementation("dev.ffmpegkit-maintained:llama-android:0.1.1")

    // Локальный ИИ: LiteRT-LM (аудит §10-12, P0-1) — Google AI Edge.
    // Multimodal (image/audio), tool use, CPU/GPU/NPU backends.
    // Apache-2.0. Основной multimodal-рантайм; llama.cpp остаётся
    // text-fallback. Модель (.litertlm) пользователь ставит сам.
    // Исключаем транзитивные kotlin-reflect/stdlib 2.4 — проект на
    // Kotlin 1.9.22, своя stdlib уже есть, а дубль с metadata 2.2+
    // ломает AGP 8.11 lint (kotlinx-metadata-jvm поддерживает ≤ 2.0).
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.17.1") {
        // Исключаем транзитивные kotlin 2.2/2.4-библиотеки: проект на
        // Kotlin 1.9.22, свои stdlib/coroutines уже есть, а их metadata
        // 2.2+ ломает AGP 8.11 lint (kotlinx-metadata-jvm ≤ 2.0).
        exclude(group = "org.jetbrains.kotlin")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-android")
    }

    // Тесты
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("com.google.truth:truth:1.4.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}

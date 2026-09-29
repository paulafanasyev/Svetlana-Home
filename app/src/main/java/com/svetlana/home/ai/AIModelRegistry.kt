package com.svetlana.home.ai

import kotlinx.serialization.Serializable

/**
 * Модель локального ИИ в реестре (ТЗ §30).
 * Все требования указаны явно, чтобы Compatibility Engine мог принять решение.
 */
@Serializable
data class AIModel(
    val id: String,
    val name: String,
    val architecture: String,
    val parameters: String,          // например "1.5B"
    val parameterCountB: Double,     // для расчётов
    val quantization: String,        // Q4_K_M, Q8_0, F16
    val sizeMb: Long,
    val ramRequirementMb: Int,
    val storageRequirementMb: Long,
    val backend: String,             // llama.cpp / onnx / mediapipe
    val cpuSupport: Boolean = true,
    val gpuSupport: Boolean = false,
    val npuSupport: Boolean = false,
    val minAndroidSdk: Int = 26,
    val context: Int,
    val license: String,
    val source: String,
    val downloadUrl: String,
    val description: String = "",
    /**
     * Доверенный SHA-256 для сверки скачанного файла (аудит §13).
     * null — реестр пока не знает хеша; проверка пропускается, но статус
     * модели остаётся INSTALLED, а не FORMAT_VERIFIED.
     */
    val expectedSha256: String? = null,
    /** capabilities модели: text/vision/audio/tools (аудит §13). */
    val capabilities: List<ModelCapability> = listOf(ModelCapability.TEXT),
    /**
     * Secure-by-default: only backends with a real in-app runtime are
     * considered installable. Future/unknown backends stay disabled until
     * explicitly implemented.
     */
    val runtimeImplemented: Boolean =
        backend.equals("llama.cpp", ignoreCase = true) ||
            backend.equals("litertlm", ignoreCase = true)
) {
    val supportsNnapi: Boolean get() = npuSupport

    /**
     * Manifest для сверки файла при установке (аудит §13).
     */
    fun manifest(): ModelManifest = ModelManifest(
        modelId = id,
        format = when (backend.lowercase()) {
            "llama.cpp" -> ModelFormat.GGUF
            "litertlm" -> ModelFormat.LITERT_LM
            else -> ModelFormat.UNKNOWN
        },
        sizeBytes = sizeMb * 1024L * 1024L,
        sha256 = expectedSha256 ?: "",
        capabilities = capabilities,
        backends = listOf(backend)
    )
}

/**
 * AIModelRegistry — каталог известных моделей.
 * Реестр содержит только метаданные — сами модели НЕ скачиваются.
 */
class AIModelRegistry {

    private val models: List<AIModel> = listOf(
        AIModel(
            id = "qwen2.5-1.5b-instruct-q4",
            name = "Qwen2.5 1.5B Instruct (Q4_K_M)",
            architecture = "Qwen2",
            parameters = "1.5B",
            parameterCountB = 1.5,
            quantization = "Q4_K_M",
            sizeMb = 1080,
            ramRequirementMb = 2048,
            storageRequirementMb = 1150,
            backend = "llama.cpp",
            cpuSupport = true, gpuSupport = false, npuSupport = false,
            context = 4096,
            license = "Apache 2.0",
            source = "Qwen (Alibaba)",
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/dd26da440ef0330c47919d1ecae0966d24022222/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            description = "Лёгкая русскоязычная модель для базовых диалогов и команд"
            expectedSha256 = "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e"
        ),
        AIModel(
            id = "qwen2.5-3b-instruct-q4",
            name = "Qwen2.5 3B Instruct (Q4_K_M)",
            architecture = "Qwen2",
            parameters = "3B",
            parameterCountB = 3.0,
            quantization = "Q4_K_M",
            sizeMb = 2000,
            ramRequirementMb = 3584,
            storageRequirementMb = 2100,
            backend = "llama.cpp",
            cpuSupport = true, gpuSupport = false, npuSupport = false,
            context = 4096,
            license = "Apache 2.0",
            source = "Qwen (Alibaba)",
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/cc1e68eea5f05f88f41a6de1fc73110178f23715/qwen2.5-3b-instruct-q4_k_m.gguf",
            description = "Сбалансированная модель: качество выше, требует больше памяти"
            expectedSha256 = "626b4a6678b86442240e33df819e00132d3ba7dddfe1cdc4fbb18e0a9615c62d"
        ),
        AIModel(
            id = "qwen2.5-7b-instruct-q4",
            name = "Qwen2.5 7B Instruct (Q4_K_M)",
            architecture = "Qwen2",
            parameters = "7B",
            parameterCountB = 7.0,
            quantization = "Q4_K_M",
            sizeMb = 4400,
            ramRequirementMb = 7168,
            storageRequirementMb = 4600,
            backend = "llama.cpp",
            cpuSupport = false, gpuSupport = false, npuSupport = false,
            context = 8192,
            license = "Apache 2.0",
            source = "Qwen (Alibaba)",
            downloadUrl = "https://huggingface.co/bartowski/Qwen2.5-7B-Instruct-GGUF/resolve/8c2fd26a844d07c5b88ba9b1fd61989effec8593/Qwen2.5-7B-Instruct-Q4_K_M.gguf",
            description = "Тяжёлая модель: только для мощных устройств или сервера"
            expectedSha256 = "65b8fcd92af6b4fefa935c625d1ac27ea29dcb6ee14589c55a8f115ceaaa1423"
        ),
        AIModel(
            id = "llama3.2-1b-instruct-q4",
            name = "Llama 3.2 1B Instruct (Q4_K_M)",
            architecture = "Llama3",
            parameters = "1B",
            parameterCountB = 1.0,
            quantization = "Q4_K_M",
            sizeMb = 820,
            ramRequirementMb = 1536,
            storageRequirementMb = 880,
            backend = "llama.cpp",
            cpuSupport = true, gpuSupport = false, npuSupport = false,
            context = 4096,
            license = "Llama 3.2 Community License",
            source = "Meta",
            downloadUrl = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/9971ce1bbba2f8b55de026a783323a808b3eeedb/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            description = "Компактная модель Meta для локального вывода"
            expectedSha256 = "6f85a640a97cf2bf5b8e764087b1e83da0fdb51d7c9fab7d0fece9385611df83"
        ),
        AIModel(
            id = "gemma2-2b-instruct-q4",
            name = "Gemma 2 2B Instruct (Q4_K_M)",
            architecture = "Gemma2",
            parameters = "2B",
            parameterCountB = 2.0,
            quantization = "Q4_K_M",
            sizeMb = 1600,
            ramRequirementMb = 3072,
            storageRequirementMb = 1700,
            backend = "llama.cpp",
            cpuSupport = true, gpuSupport = false, npuSupport = false,
            context = 4096,
            license = "Gemma Terms of Use",
            source = "Google",
            downloadUrl = "https://huggingface.co/bartowski/gemma-2-2b-it-GGUF/resolve/76b50500259581a0adb25b2d02a89351f0278ad5/gemma-2-2b-it-Q4_K_M.gguf",
            description = "Локальная модель Google; текущий встроенный runtime работает на CPU"
            expectedSha256 = "e0aee85060f168f0f2d8473d7ea41ce2f3230c1bc1374847505ea599288a7787"
        ),
        AIModel(
            id = "whisper-tiny-ru-stt",
            name = "Whisper tiny RU (GGML)",
            architecture = "Whisper",
            parameters = "0.039B",
            parameterCountB = 0.039,
            quantization = "F16",
            sizeMb = 75,
            ramRequirementMb = 512,
            storageRequirementMb = 90,
            backend = "onnxruntime",
            cpuSupport = true, gpuSupport = false, npuSupport = false,
            context = 0,
            license = "MIT",
            source = "whisper-tiny-russian-ggml",
            downloadUrl = "https://huggingface.co/wabisabisocial/whisper-tiny-russian-ggml/resolve/main/ggml-tiny-ru.bin",
            description = "Каталог offline-STT; встроенного ONNX/GGML runtime пока нет",
            runtimeImplemented = false
        ),
        AIModel(
            id = "piper-ru-irina-tts",
            name = "Piper RU TTS (Irina)",
            architecture = "Piper VITS",
            parameters = "0.02B",
            parameterCountB = 0.02,
            quantization = "F16",
            sizeMb = 62,
            ramRequirementMb = 512,
            storageRequirementMb = 80,
            backend = "onnxruntime",
            cpuSupport = true, gpuSupport = false, npuSupport = false,
            context = 0,
            license = "MIT",
            source = "vits-piper-ru_RU-irina-medium",
            downloadUrl = "https://huggingface.co/csukuangfj/vits-piper-ru_RU-irina-medium/resolve/main/ru_RU-irina-medium.onnx",
            description = "Каталог offline-TTS; встроенного ONNX runtime пока нет",
            runtimeImplemented = false
        ),
        AIModel(
            id = "rubert-embeddings-int8",
            name = "RuBERT embeddings (INT8)",
            architecture = "BERT",
            parameters = "0.18B",
            parameterCountB = 0.18,
            quantization = "INT8",
            sizeMb = 180,
            ramRequirementMb = 768,
            storageRequirementMb = 200,
            backend = "onnxruntime",
            cpuSupport = true, gpuSupport = false, npuSupport = false,
            context = 512,
            license = "Apache 2.0",
            source = "rion-rubert-nli-onnx",
            downloadUrl = "https://huggingface.co/VoKaP/rion-rubert-nli-onnx/resolve/main/onnx/model.onnx",
            description = "Каталог локальных эмбеддингов; встроенного ONNX runtime пока нет",
            runtimeImplemented = false
        ),
        AIModel(
            id = "aya-8b-q4",
            name = "Aya 8B (Q4_K_M)",
            architecture = "Cohere Aya",
            parameters = "8B",
            parameterCountB = 8.0,
            quantization = "Q4_K_M",
            sizeMb = 4800,
            ramRequirementMb = 8192,
            storageRequirementMb = 5000,
            backend = "llama.cpp",
            cpuSupport = false, gpuSupport = false, npuSupport = false,
            context = 8192,
            license = "CC BY-NC 4.0",
            source = "Cohere For AI",
            downloadUrl = "https://huggingface.co/mradermacher/aya-8B-GGUF/resolve/main/AYA-8B.Q4_K_M.gguf",
            description = "Мультиязычная модель с сильной поддержкой русского языка"
        ),
        // Аудит §10-12 (P0-1): мультимодальные LiteRT-модели —
        // единственный путь on-device vision в LOCAL_ONLY.
        // URL проверены по реальному списку файлов litert-community:
        // gemma3-1b-it.litertlm НЕ существует (репозиторий содержит
        // .task и device-specific сборки), как и gemma3-4b-it.litertlm.
        // gemma-4-E2B-it — реальная публичная мультимодальная модель.
        AIModel(
            id = "gemma-4-e2b-it-litertlm",
            name = "Gemma 4 E2B IT (LiteRT)",
            architecture = "litertlm",
            parameters = "2B",
            parameterCountB = 2.0,
            quantization = "litertlm",
            sizeMb = 2469,
            ramRequirementMb = 6144,
            storageRequirementMb = 2600,
            backend = "litertlm",
            cpuSupport = true, gpuSupport = false, npuSupport = false,
            context = 4096,
            license = "Gemma Terms of Use",
            source = "Google AI Edge (litert-community)",
            downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/6e5c4f1/gemma-4-E2B-it.litertlm",
            description = "Локальная мультимодальная модель: текст + изображения + аудио. Vision on-device",
            // Доверенный хеш вычислен по реальному скачанному файлу
            // (2588147712 байт) — сверки «вычислить SHA» недостаточно,
            // нужно сравнение с доверенным значением (аудит §13/§18).
            expectedSha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
            capabilities = listOf(ModelCapability.TEXT, ModelCapability.VISION)
        ),
        AIModel(
            id = "qwen2.5-1.5b-instruct-litertlm",
            name = "Qwen 2.5 1.5B Instruct (LiteRT)",
            architecture = "litertlm",
            parameters = "1.5B",
            parameterCountB = 1.5,
            quantization = "q8",
            sizeMb = 1523,
            ramRequirementMb = 3072,
            storageRequirementMb = 1600,
            backend = "litertlm",
            cpuSupport = true, gpuSupport = false, npuSupport = false,
            context = 4096,
            license = "Apache 2.0",
            source = "Qwen (litert-community)",
            downloadUrl = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/19edb84/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
            description = "Лёгкая локальная текстовая модель для слабых устройств",
            expectedSha256 = "faa60663b333290c1496c499828b21d3e3254a788cacd8cce917ce0f761a2dc9",
            capabilities = listOf(ModelCapability.TEXT)
        )
    )

    @Suppress("SpellCheckingInspection")
    fun all(): List<AIModel> = models

    fun byId(id: String): AIModel? = models.firstOrNull { it.id == id }

    fun llmModels(): List<AIModel> = models.filter { it.context > 0 && it.parameterCountB >= 1.0 }

    fun sttModels(): List<AIModel> = models.filter { it.id.contains("stt") }
    fun ttsModels(): List<AIModel> = models.filter { it.id.contains("tts") }
}

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
    val capabilities: List<ModelCapability> = listOf(ModelCapability.TEXT)
) {
    val supportsNnapi: Boolean get() = npuSupport

    /**
     * Manifest для сверки файла при установке (аудит §13).
     */
    fun manifest(): ModelManifest = ModelManifest(
        modelId = id,
        format = when (backend) {
            "llama.cpp" -> ModelFormat.GGUF
            else -> ModelFormat.LITERT_LM
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
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            description = "Лёгкая русскоязычная модель для базовых диалогов и команд"
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
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf",
            description = "Сбалансированная модель: качество выше, требует больше памяти"
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
            cpuSupport = false, gpuSupport = false, npuSupport = true,
            context = 8192,
            license = "Apache 2.0",
            source = "Qwen (Alibaba)",
            downloadUrl = "https://huggingface.co/bartowski/Qwen2.5-7B-Instruct-GGUF/resolve/main/Qwen2.5-7B-Instruct-Q4_K_M.gguf",
            description = "Тяжёлая модель: только для мощных устройств или сервера"
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
            downloadUrl = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            description = "Компактная модель Meta для локального вывода"
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
            cpuSupport = true, gpuSupport = false, npuSupport = true,
            context = 4096,
            license = "Gemma Terms of Use",
            source = "Google",
            downloadUrl = "https://huggingface.co/bartowski/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q4_K_M.gguf",
            description = "Локальная модель Google с поддержкой NNAPI"
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
            cpuSupport = true, gpuSupport = false, npuSupport = true,
            context = 0,
            license = "MIT",
            source = "whisper-tiny-russian-ggml",
            downloadUrl = "https://huggingface.co/wabisabisocial/whisper-tiny-russian-ggml/resolve/main/ggml-tiny-ru.bin",
            description = "Локальное распознавание русской речи"
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
            cpuSupport = true, gpuSupport = false, npuSupport = true,
            context = 0,
            license = "MIT",
            source = "vits-piper-ru_RU-irina-medium",
            downloadUrl = "https://huggingface.co/csukuangfj/vits-piper-ru_RU-irina-medium/resolve/main/ru_RU-irina-medium.onnx",
            description = "Локальный синтез русской речи"
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
            cpuSupport = true, gpuSupport = true, npuSupport = true,
            context = 512,
            license = "Apache 2.0",
            source = "rion-rubert-nli-onnx",
            downloadUrl = "https://huggingface.co/VoKaP/rion-rubert-nli-onnx/resolve/main/onnx/model.onnx",
            description = "Локальные эмбеддинги для памяти и поиска"
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
            cpuSupport = false, gpuSupport = false, npuSupport = true,
            context = 8192,
            license = "CC BY-NC 4.0",
            source = "Cohere For AI",
            downloadUrl = "https://huggingface.co/mradermacher/aya-8B-GGUF/resolve/main/AYA-8B.Q4_K_M.gguf",
            description = "Мультиязычная модель с сильной поддержкой русского языка"
        ),
        // Аудит §10-12 (P0-1): мультимодальные LiteRT-модели —
        // единственный путь on-device vision в LOCAL_ONLY.
        AIModel(
            id = "gemma3-1b-it-litertlm",
            name = "Gemma 3 1B IT (LiteRT)",
            architecture = "litertlm",
            parameters = "1B",
            parameterCountB = 1.0,
            quantization = "litertlm",
            sizeMb = 700,
            ramRequirementMb = 2048,
            storageRequirementMb = 800,
            backend = "litertlm",
            cpuSupport = true, gpuSupport = true, npuSupport = false,
            context = 4096,
            license = "Gemma Terms of Use",
            source = "Google AI Edge (litert-community)",
            downloadUrl = "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it.litertlm",
            description = "Локальная мультимодальная модель: текст + изображения. Vision on-device",
            capabilities = listOf(ModelCapability.TEXT, ModelCapability.VISION)
        ),
        AIModel(
            id = "gemma3-4b-it-litertlm",
            name = "Gemma 3 4B IT (LiteRT)",
            architecture = "litertlm",
            parameters = "4B",
            parameterCountB = 4.0,
            quantization = "litertlm",
            sizeMb = 2600,
            ramRequirementMb = 6144,
            storageRequirementMb = 2800,
            backend = "litertlm",
            cpuSupport = true, gpuSupport = true, npuSupport = true,
            context = 8192,
            license = "Gemma Terms of Use",
            source = "Google AI Edge (litert-community)",
            downloadUrl = "https://huggingface.co/litert-community/Gemma3-4B-IT/resolve/main/gemma3-4b-it.litertlm",
            description = "Локальная мультимодальная модель: текст + изображения, качество выше",
            capabilities = listOf(ModelCapability.TEXT, ModelCapability.VISION, ModelCapability.TOOLS)
        )
    )

    @Suppress("SpellCheckingInspection")
    fun all(): List<AIModel> = models

    fun byId(id: String): AIModel? = models.firstOrNull { it.id == id }

    fun llmModels(): List<AIModel> = models.filter { it.context > 0 && it.parameterCountB >= 1.0 }

    fun sttModels(): List<AIModel> = models.filter { it.id.contains("stt") }
    fun ttsModels(): List<AIModel> = models.filter { it.id.contains("tts") }
}

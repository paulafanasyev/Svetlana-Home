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
    val description: String = ""
) {
    val supportsNnapi: Boolean get() = npuSupport
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
            cpuSupport = true, gpuSupport = true, npuSupport = false,
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
            cpuSupport = true, gpuSupport = true, npuSupport = false,
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
            cpuSupport = false, gpuSupport = true, npuSupport = true,
            context = 8192,
            license = "Apache 2.0",
            source = "Qwen (Alibaba)",
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-7B-Instruct-GGUF/resolve/main/qwen2.5-7b-instruct-q4_k_m.gguf",
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
            cpuSupport = true, gpuSupport = true, npuSupport = false,
            context = 4096,
            license = "Llama 3.2 Community License",
            source = "Meta",
            downloadUrl = "https://huggingface.co/meta-llama/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
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
            cpuSupport = true, gpuSupport = true, npuSupport = true,
            context = 4096,
            license = "Gemma Terms of Use",
            source = "Google",
            downloadUrl = "https://huggingface.co/google/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-q4_k_m.gguf",
            description = "Локальная модель Google с поддержкой NNAPI"
        ),
        AIModel(
            id = "sherpa-onnx-ru-stt",
            name = "Sherpa-ONNX RU STT (Vosk-small)",
            architecture = "Sherpa-ONNX",
            parameters = "0.05B",
            parameterCountB = 0.05,
            quantization = "INT8",
            sizeMb = 42,
            ramRequirementMb = 512,
            storageRequirementMb = 60,
            backend = "onnxruntime",
            cpuSupport = true, gpuSupport = false, npuSupport = true,
            context = 0,
            license = "Apache 2.0",
            source = "Sherpa-ONNX",
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases",
            description = "Локальное распознавание русской речи"
        ),
        AIModel(
            id = "sherpa-onnx-ru-tts",
            name = "Sherpa-ONNX RU TTS",
            architecture = "Sherpa-ONNX",
            parameters = "0.08B",
            parameterCountB = 0.08,
            quantization = "INT8",
            sizeMb = 120,
            ramRequirementMb = 768,
            storageRequirementMb = 140,
            backend = "onnxruntime",
            cpuSupport = true, gpuSupport = false, npuSupport = true,
            context = 0,
            license = "Apache 2.0",
            source = "Sherpa-ONNX",
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases",
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
            source = "DeepPavlov",
            downloadUrl = "https://huggingface.co/DeepPavlov/rubert-base-cased",
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
            cpuSupport = false, gpuSupport = true, npuSupport = true,
            context = 8192,
            license = "CC BY-NC 4.0",
            source = "Cohere For AI",
            downloadUrl = "https://huggingface.co/CohereForAI/aya-8B-GGUF",
            description = "Мультиязычная модель с сильной поддержкой русского и вьетнамского"
        )
    )

    @Suppress("SpellCheckingInspection")
    fun all(): List<AIModel> = models

    fun byId(id: String): AIModel? = models.firstOrNull { it.id == id }

    fun llmModels(): List<AIModel> = models.filter { it.context > 0 && it.parameterCountB >= 1.0 }

    fun sttModels(): List<AIModel> = models.filter { it.id.contains("stt") }
    fun ttsModels(): List<AIModel> = models.filter { it.id.contains("tts") }
}

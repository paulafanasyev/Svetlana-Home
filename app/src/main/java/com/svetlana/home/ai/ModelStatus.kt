package com.svetlana.home.ai

/**
 * Жёсткий жизненный цикл локальной модели (аудит §14, docs/model-status.md).
 *
 * Установленный файл ≠ рабочая модель. Статус **вычисляется** из фактов,
 * а не проставляется вручную.
 */
enum class ModelStatus(val label: String) {
    NOT_INSTALLED("Не установлена"),
    INSTALLED("Установлена"),
    FORMAT_VERIFIED("Формат проверен"),
    RUNTIME_READY("Runtime готов"),
    MODEL_LOADED("Загружена в память"),
    INFERENCE_VERIFIED("Inference проверен"),
    VISION_VERIFIED("Vision проверен"),
    DEVICE_VERIFIED("Проверена на устройстве");

    companion object {
        /**
         * Вычисляет итоговый статус из набора фактов.
         * Возвращает наивысший достижимый уровень.
         */
        fun fromFacts(
            installed: Boolean,
            formatVerified: Boolean,
            runtimeReady: Boolean,
            modelLoaded: Boolean,
            inferenceVerified: Boolean,
            visionVerified: Boolean,
            deviceVerified: Boolean
        ): ModelStatus = when {
            deviceVerified     -> DEVICE_VERIFIED
            visionVerified     -> VISION_VERIFIED
            inferenceVerified  -> INFERENCE_VERIFIED
            modelLoaded        -> MODEL_LOADED
            runtimeReady       -> RUNTIME_READY
            formatVerified     -> FORMAT_VERIFIED
            installed          -> INSTALLED
            else               -> NOT_INSTALLED
        }
    }
}

/**
 * Сверка скачанного файла с доверенным манифестом (аудит §13).
 *
 * Вычислить SHA-256 ≠ проверить SHA-256. Здесь хранится **ожидаемое**
 * значение, с которым сверяется реальный файл.
 */
data class ModelManifest(
    val modelId: String,
    val format: ModelFormat,
    val sizeBytes: Long,
    val sha256: String,
    val capabilities: List<ModelCapability>,
    val backends: List<String>
)

enum class ModelFormat(val magicHex: String, val humanName: String) {
    GGUF("47475546", "GGUF (llama.cpp)"),
    UNKNOWN("", "Неизвестный формат"),
    /**
     * Контейнер LiteRT-LM имеет собственный заголовок, отдельный от
     * .tflite: первые 8 байт файла — ASCII "LITERTLM"
     * (0x4c 0x49 0x54 0x45 0x52 0x54 0x4c 0x4d), затем версия major/minor.
     * Upstream file_format_util.cc явно различает "TFL3" (TFLite), "PK"
     * (ZIP) и StartsWith("LITERTLM") — это три разных формата.
     * Проверка "TFL" была ошибочной: она пропускала бы .tflite-файлы
     * и не подтверждала реальный контейнер LiteRT-LM.
     * Источник: runtime/util/file_format_util.cc, schema/core/litertlm_header.h
     */
    LITERT_LM("4c49544552544c4d", "LiteRT-LM (.litertlm)");

    /**
     * Проверка magic bytes файла. Раньше LITERT_LM принимал любой
     * файл (всегда true), затем ошибочно проверял "TFL" по смещению 4.
     * Теперь сверяем настоящий 8-байный заголовок "LITERTLM" с нулевого
     * смещения — так отсекаются HTML-страницы 404, мусор и .tflite файлы.
     */
    fun matchesMagic(header: ByteArray): Boolean {
        if (magicHex.isEmpty()) return false
        if (header.size < magicHex.length / 2) return false
        for (i in magicHex.indices step 2) {
            val expected = magicHex.substring(i, i + 2).toInt(16)
            if (header[i / 2].toInt() and 0xFF != expected) return false
        }
        return true
    }
}

enum class ModelCapability(val label: String) {
    TEXT("Текст"),
    VISION("Изображения"),
    AUDIO("Аудио"),
    TOOLS("Инструменты")
}

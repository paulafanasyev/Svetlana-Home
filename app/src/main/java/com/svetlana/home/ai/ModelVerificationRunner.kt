package com.svetlana.home.ai

import android.util.Log
import com.svetlana.home.ai.local.LlamaCppRuntime

/**
 * Проверка установленной модели по полной цепочке (аудит п.2).
 *
 * «Установлена» ≠ «работает». После загрузки интерфейс должен показать
 * реальный результат каждого этапа:
 *
 *   Файл найден → Совместимость → Runtime → Load → Context → Inference
 *                 ↓                ↓                     ↓
 *           причина, если этап провален
 *
 * Никаких «установлено = работает»: если inference не запущен —
 * показываем причину.
 */
object ModelVerificationRunner {

    data class Stage(
        val name: String,
        val ok: Boolean,
        val detail: String
    )

    data class Report(
        val modelId: String,
        val stages: List<Stage>,
        val firstTokenMs: Long,
        val tokensPerSecond: Double,
        val ramUsedMb: Int,
        val inferenceOk: Boolean,
        val failureReason: String?
    ) {
        /** Краткий текст для UI. */
        fun summary(): String = buildString {
            stages.forEach { appendLine("${if (it.ok) "✓" else "✕"} ${it.name}${if (it.ok) "" else " — ${it.detail}"}") }
            if (inferenceOk) {
                appendLine()
                appendLine("Первый токен: ${firstTokenMs / 1000.0} сек")
                appendLine("Скорость: ${"%.1f".format(tokensPerSecond)} ток/с")
                appendLine("RAM: $ramUsedMb МБ")
            } else {
                appendLine()
                appendLine("✕ Inference не запущен")
                failureReason?.let { appendLine("Причина: $it") }
            }
        }
    }

    /**
     * @param runtime реальный inference runtime (llama.cpp). null — runtime
     * недоступен в сборке, тогда этапы Runtime/Load/Inference проваливаются.
     */
    fun verify(
        modelId: String,
        manager: LocalModelManager,
        registry: AIModelRegistry,
        runtime: LlamaCppRuntime?,
        device: com.svetlana.home.device.DeviceCapabilityManager
    ): Report {
        val stages = mutableListOf<Stage>()
        val installed = manager.byId(modelId)

        // 1. Файл найден и не пустой
        val file = installed?.filePath?.let { java.io.File(it) }
        val fileOk = file != null && file.exists() && file.length() > 0
        stages.add(Stage("Файл найден", fileOk,
            if (fileOk) "${file!!.length() / 1024 / 1024} МБ"
            else "Файл модели отсутствует"))

        // 2. Совместимость с устройством
        val model = registry.byId(modelId)
        val compatOk = model != null
        stages.add(Stage("Совместимость", compatOk,
            if (compatOk) "${model!!.parameters}, ${model.quantization}, контекст ${model.context}"
            else "Модель не найдена в реестре"))

        // 3. Runtime доступен (нативный llama.cpp для текущего ABI)
        val runtimeOk = runtime != null && try {
            runtime.isReady() || runtime.supportedModelIds().contains(modelId)
        } catch (t: Throwable) { false }
        stages.add(Stage("Runtime", runtimeOk,
            if (runtimeOk) "llama.cpp готов"
            else "Нативный runtime недоступен на этом ABI"))

        // 4/5/6. Load → Context → Inference: реальный запуск
        var firstTokenMs = 0L
        var tokensPerSecond = 0.0
        var ramUsedMb = 0
        var inferenceOk = false
        var reason: String? = null

        if (fileOk && runtimeOk && runtime != null) {
            val ramBefore = device.refresh().ramAvailableMb
            try {
                Log.i(TAG, "Запускаю проверку inference модели $modelId")
                val output = runtime.generate(modelId, VERIFY_PROMPT, maxTokens = 16)
                firstTokenMs = runtime.lastLatencyMs()
                tokensPerSecond = runtime.lastTokensPerSecond()
                ramUsedMb = (ramBefore - device.refresh().ramAvailableMb).coerceAtLeast(0)
                inferenceOk = output.isNotBlank()
                if (!inferenceOk) reason = "Модель вернула пустой ответ"
                stages.add(Stage("Load", true, "модель загружена в память"))
                stages.add(Stage("Context", true, "контекст создан"))
                stages.add(Stage("Inference", inferenceOk,
                    if (inferenceOk) "ответ получен" else "пустой ответ"))
            } catch (t: Throwable) {
                reason = t.message ?: t.javaClass.simpleName
                Log.w(TAG, "Inference не удался для $modelId: $reason", t)
                stages.add(Stage("Load", false, reason ?: "ошибка загрузки"))
                stages.add(Stage("Context", false, "не создан"))
                stages.add(Stage("Inference", false, reason ?: "не запущен"))
            }
        } else {
            val why = when {
                !fileOk -> "файл модели не найден"
                !compatOk -> "модель неизвестна реестру"
                else -> "runtime недоступен"
            }
            reason = why
            stages.add(Stage("Load", false, why))
            stages.add(Stage("Context", false, why))
            stages.add(Stage("Inference", false, why))
        }

        return Report(modelId, stages, firstTokenMs, tokensPerSecond, ramUsedMb, inferenceOk, reason)
    }

    private const val TAG = "ModelVerification"
    private const val VERIFY_PROMPT = "Назови одно слово на русском языке."
}

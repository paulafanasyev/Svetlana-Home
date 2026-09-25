package com.svetlana.home.ai

import com.svetlana.home.memory.HistoryCategory

/**
 * HybridPipeline — РЕАЛЬНЫЙ гибридный pipeline (ТЗ §46, §47, аудит п.20).
 *
 * Раньше HYBRID просто отправлял запрос в LocalAIProvider, и remote-этап
 * фактически не выполнялся. Этот класс реализует обещанную цепочку:
 *
 *   input
 *     ↓
 *   privacy decision  (можно ли вообще отправлять данные наружу)
 *     ↓
 *   local preprocessing  (на устройстве: обрезка контекста, маскирование)
 *     ↓
 *   sanitized payload
 *     ↓
 *   remote inference     (personal server / внешний провайдер)
 *     ↓
 *   local postprocessing (на устройстве: форматирование, проверка)
 *     ↓
 *   result
 *
 * LOCAL_ONLY физически исключает передачу: privacy-проверка выполняется
 * ДО любого обращения к сети, и при отказе payload не формируется.
 */
class HybridPipeline(
        private val localProvider: AIProvider,
    private val remoteProviderFactory: suspend () -> AIProvider?,
    private val recordHistory: (HistoryCategory, String) -> Unit
) {

    data class Stage(
        val name: String,
        val ok: Boolean,
        val detail: String = ""
    )

    data class Result(
        val success: Boolean,
        val text: String,
        val stages: List<Stage>,
        val backend: AIBackend,
        val latencyMs: Long
    )

    /**
     * @param dataType тип данных, который пойдёт на remote-этап.
     * От этого зависит privacy-решение.
     */
    suspend fun run(
        prompt: String,
        dataType: PrivacyDataType = PrivacyDataType.TEXT,
        systemPrompt: String? = null,
        mode: AIMode
    ): Result {
        val started = System.currentTimeMillis()
        val stages = mutableListOf<Stage>()

        // 1. Privacy decision — выполняется до любого сетевого вызова.
        // LOCAL_ONLY физически исключает передачу независимо от вызывающего кода.
        val privacy = PrivacyPolicy.decide(dataType, AIBackend.PERSONAL_SERVER, mode)
        if (!privacy.allowed) {
            stages += Stage("privacy", false, privacy.reason)
            recordHistory(HistoryCategory.AI,
                "Hybrid заблокирован PrivacyRouter: ${privacy.reason}")
            // LOCAL_ONLY физически исключает передачу — возвращаемся на устройство.
            val local = runLocal(prompt, systemPrompt, stages)
            return local.copy(
                text = if (local.success) local.text else privacy.reason,
                latencyMs = System.currentTimeMillis() - started
            )
        }
        stages += Stage("privacy", true, "передача разрешена")

        // 2. Local preprocessing — на устройстве, без сети.
        val preprocessed = preprocess(prompt)
        stages += Stage("preprocess", true,
            "контекст ${prompt.length}→${preprocessed.length} символов")
        val sanitized = sanitize(preprocessed)

        // 3. Remote inference.
        val remote = remoteProviderFactory()
        if (remote == null || !remote.isAvailable()) {
            stages += Stage("remote", false, "удалённый провайдер недоступен")
            // Fallback на устройство — честный, с пометкой о причине.
            val local = runLocal(sanitized, systemPrompt, stages)
            recordHistory(HistoryCategory.AI,
                "Hybrid: remote недоступен, fallback на устройство")
            return local.copy(latencyMs = System.currentTimeMillis() - started)
        }

        val remoteResult = remote.chat(sanitized, systemPrompt)
        stages += Stage("remote", remoteResult.success,
            remoteResult.error ?: "${remoteResult.latencyMs}мс")

        if (!remoteResult.success) {
            // Remote упал — пробуем локально (только если privacy это позволяет
            // и если локальная модель способна ответить).
            val local = runLocal(sanitized, systemPrompt, stages)
            recordHistory(HistoryCategory.AI,
                "Hybrid: remote inference не удался, fallback на устройство: ${remoteResult.error}")
            return local.copy(latencyMs = System.currentTimeMillis() - started)
        }

        // 4. Local postprocessing — на устройстве.
        val final = postprocess(remoteResult.text, prompt)
        stages += Stage("postprocess", true, "ответ ${remoteResult.text.length}→${final.length} символов")

        return Result(
            success = true,
            text = final,
            stages = stages,
            backend = AIBackend.HYBRID,
            latencyMs = System.currentTimeMillis() - started
        )
    }

    /**
     * Локальная предобработка: обрезка сверхдлинного ввода до разумного
     * контекста, чтобы не гнать мусор на сервер.
     */
    private fun preprocess(prompt: String): String {
        val maxChars = 8000
        if (prompt.length <= maxChars) return prompt
        return prompt.take(maxChars) + "\n…(обрезано на устройстве)"
    }

    /**
     * Санитизация: убираем то, что никогда не должно покидуть устройство.
     * Это страховка поверх PrivacyRouter — например, PIN-подобные последовательности.
     */
    private fun sanitize(text: String): String {
        var out = text
        // Маскируем PIN-подобные паттерны (4-8 цифр подряд).
        out = PIN_PATTERN.replace(out) { "****" }
        // Маскируем типичные паттерны ключей.
        out = SECRET_PATTERN.replace(out) { "[СКРЫТО]" }
        return out
    }

    /**
     * Локальная постобработка ответа сервера: нормализуем вывод.
     */
    private fun postprocess(answer: String, originalPrompt: String): String {
        val trimmed = answer.trim()
        return if (trimmed.isEmpty()) {
            "Пустой ответ от сервера. Попробуйте переформулировать."
        } else {
            trimmed
        }
    }

    private suspend fun runLocal(
        prompt: String,
        systemPrompt: String?,
        stages: MutableList<Stage>
    ): Result {
        val localResult = localProvider.chat(prompt, systemPrompt)
        stages += Stage("local", localResult.success,
            localResult.error ?: "${localResult.latencyMs}мс")
        return Result(
            success = localResult.success,
            text = localResult.text,
            stages = stages,
            backend = AIBackend.LOCAL,
            latencyMs = 0L
        )
    }

    companion object {
        private val PIN_PATTERN = Regex("\\b\\d{4,8}\\b")
        private val SECRET_PATTERN = Regex("(?i)(sk-[A-Za-z0-9]{16,})|(Bearer [A-Za-z0-9._-]{16,})")
    }
}

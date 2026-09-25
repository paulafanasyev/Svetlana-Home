package com.svetlana.home.translate

/**
 * Направление перевода.
 */
enum class TranslateDirection(val code: String, val label: String) {
    RU_TO_VI("ru-vi", "Русский → Вьетнамский"),
    VI_TO_RU("vi-ru", "Вьетнамский → Русский");

    companion object {
        fun fromCode(code: String?): TranslateDirection? = entries.firstOrNull { it.code == code }
    }
}

data class TranslateResult(
    val success: Boolean,
    val text: String,
    val sourceText: String,
    val direction: TranslateDirection,
    val backend: String,
    val error: String? = null
)

/**
 * TranslatorProvider — абстракция способа перевода (Тз §54).
 * Поддерживается: local, external, AI, будущие провайдеры.
 * Пользователь выбирает backend в настройках.
 */
interface TranslatorProvider {
    val id: String
    val displayName: String

    /** Готов ли этот способ перевода к работе. */
    fun isAvailable(): Boolean

    suspend fun translate(text: String, direction: TranslateDirection): TranslateResult
}

/**
 * Заглушка локального перевода: RU↔VI словарь наиболее частых фраз.
 * Полноценный офлайн-переводчик подключается как отдельный провайдер
 * (например, на локальной модели), когда пользователь его установит.
 */
class LocalPhraseTranslator : TranslatorProvider {
    override val id: String = "local-phrase"
    override val displayName: String = "Локальный (фразы)"

    private val ruVi = mapOf(
        "привет" to "xin chào",
        "здравствуй" to "chào bạn",
        "доброе утро" to "chào buổi sáng",
        "добрый день" to "chào buổi chiều",
        "добрый вечер" to "chào buổi tối",
        "спасибо" to "cảm ơn",
        "большое спасибо" to "cảm ơn rất nhiều",
        "пожалуйста" to "vui lòng",
        "не за что" to "không có gì",
        "да" to "có",
        "нет" to "không",
        "как дела" to "bạn khỏe không",
        "я люблю тебя" to "tôi yêu bạn",
        "что нового" to "có gì mới không",
        "до свидания" to "tạm biệt",
        "помоги мне" to "hãy giúp tôi",
        "открой" to "mở",
        "назад" to "quay lại",
        "домой" to "trang chủ",
        "настройки" to "cài đặt",
        "переводчик" to "trình dịch",
        "светлана" to "Svetlana",
        "хорошо" to "tốt",
        "отлично" to "tuyệt vời",
        "идём" to "đi nào",
        "одну минуту" to "một phút",
        "я не понимаю" to "tôi không hiểu",
        "говори медленнее" to "nói chậm hơn",
        "комната" to "phòng",
        "еда" to "thức ăn",
        "вода" to "nước",
        "деньги" to "tiền",
        "телефон" to "điện thoại",
        "приложение" to "ứng dụng"
    )

    override fun isAvailable(): Boolean = true

    override suspend fun translate(text: String, direction: TranslateDirection): TranslateResult {
        val source = text.trim()
        if (source.isEmpty()) return TranslateResult(false, "", source, direction, id, "Пустой текст")

        val dict = if (direction == TranslateDirection.RU_TO_VI) ruVi else ruVi.entries.associate { (k, v) -> v to k }
        val lower = source.lowercase().replace("ё", "е").trim('.')

        // Точное совпадение по словарю
        val exact = dict[lower]
        if (exact != null) {
            return TranslateResult(true, exact.replaceFirstChar { it.uppercaseChar() }, source, direction, id)
        }

        // По словам
        val words = lower.split(Regex("\\s+"))
        val translated = words.joinToString(" ") { word ->
            dict[word.trim(',', '.', '!', '?')] ?: word
        }
        val anyTranslated = translated != source
        return if (anyTranslated) {
            TranslateResult(true, translated.replaceFirstChar { it.uppercaseChar() }, source, direction, id)
        } else {
            TranslateResult(false, source, source, direction, id,
                "Локальный переводчик не знает этой фразы — используйте AI-перевод")
        }
    }
}

/**
 * Переводчик на основе AI-провайдера (локальная модель, сервер или внешний AI).
 */
class AiTranslator(
    private val aiRouter: com.svetlana.home.ai.AIRouter
) : TranslatorProvider {
    override val id: String = "ai"
    override val displayName: String = "AI-перевод"

    override fun isAvailable(): Boolean = true

    override suspend fun translate(text: String, direction: TranslateDirection): TranslateResult {
        if (text.isBlank()) return TranslateResult(false, "", text, direction, id, "Пустой текст")
        val from = if (direction == TranslateDirection.RU_TO_VI) "русского" else "вьетнамского"
        val to = if (direction == TranslateDirection.RU_TO_VI) "вьетнамского" else "русского"
        val prompt = "Переведи с $from на $to. В ответе — только перевод, без комментариев.\n\n$text"
        val result = aiRouter.chat(prompt, com.svetlana.home.ai.ModelRouter.TaskComplexity.MEDIUM)
        return if (result.success) {
            TranslateResult(true, result.text.trim(), text, direction, result.backend.name)
        } else {
            TranslateResult(false, text, text, direction, result.backend.name, result.error)
        }
    }
}

/**
 * Менеджер способов перевода: пользователь выбирает backend.
 */
class TranslatorProviderManager(
    private val context: android.content.Context,
    private val aiRouter: com.svetlana.home.ai.AIRouter
) {
    private val local = LocalPhraseTranslator()
    private val ai = AiTranslator(aiRouter)

    val providers: List<TranslatorProvider> = listOf(local, ai)

    fun byId(id: String): TranslatorProvider = providers.first { it.id == id }

    suspend fun translate(text: String, direction: TranslateDirection, backendId: String?): TranslateResult {
        val provider = backendId?.let { id -> providers.firstOrNull { it.id == id } } ?: ai
        // Если выбран AI, а он не смог — честно сообщаем, без скрытых подмен.
        return provider.translate(text, direction)
    }
}

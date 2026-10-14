package com.svetlana.home.control

/**
 * Сравнение имени контакта с подписью на экране мессенджера (чистая функция, без Android).
 *
 * Регистр и «ё» не важны (распознавание речи и парсер пишут «е», а в WhatsApp — «Серёга»).
 * Совпадение — только точное имя или имя + продолжение через пробел («Серый Иванов»,
 * «Серый 🔥»). «Серыйкин» и «Иван Серый» — не совпадение: писать не тому человеку нельзя.
 *
 * Падежи: в команде имя часто стоит не в именительном («напиши Серому», «отправь Маше», «с Иваном»),
 * а в мессенджере контакт записан «Серый», «Маша», «Иван». [forms] даёт имя как сказано (первым)
 * и возможные именительные формы. Движок сначала ищет имя как сказано и только если его
 * нет — формы; если подходят разные люди, писать не будет (неоднозначность).
 */
object ContactMatch {

    private val SPACES = Regex("\\s+")
    private const val MIN_STEM = 2
    private const val MAX_WORDS = 3
    private const val MAX_FORMS = 16

    /** Окончание косвенного падежа → возможные окончания именительного. Длинные — раньше, берётся первое совпавшее. */
    private val ENDINGS: List<Pair<String, List<String>>> = listOf(
        "ому" to listOf("ый", "ий", "ой"),   // Серому → Серый
        "ему" to listOf("ий", "ый"),
        "ого" to listOf("ый", "ий", "ой"),
        "его" to listOf("ий", "ый"),
        "ии" to listOf("ия", "ий"),          // Марии → Мария
        "ие" to listOf("ия", "ий"),
        "ию" to listOf("ия"),
        "ую" to listOf("ая"),
        "ой" to listOf("а", "ая", "ый"),     // Ольгой → Ольга
        "ей" to listOf("я", "а", "ея"),      // Таней → Таня
        "ем" to listOf("й", "ь"),            // Сергеем → Сергей
        "ом" to listOf(""),                  // Иваном → Иван
        "ым" to listOf("ый"),
        "им" to listOf("ий"),
        "е" to listOf("а", "я", ""),          // Маше → Маша, Иване → Иван
        "и" to listOf("а", "я", "ь"),         // Тани → Таня
        "у" to listOf("", "а"),              // Ивану → Иван, Машу → Маша
        "ю" to listOf("й", "ь", "я"),         // Андрею → Андрей
        "а" to listOf(""),                   // Ивана → Иван
        "я" to listOf("й", "ь")              // Андрея → Андрей
    )

    fun norm(s: String): String =
        s.trim().lowercase().replace('ё', 'е').replace(SPACES, " ")

    fun matches(shown: String, contact: String): Boolean {
        val want = norm(contact)
        if (want.isEmpty()) return false
        val s = norm(shown)
        return s == want || s.startsWith("$want ")
    }

    private fun wordForms(word: String): List<String> {
        val out = mutableListOf(word)
        for ((ending, nominative) in ENDINGS) {
            if (word.endsWith(ending) && word.length - ending.length >= MIN_STEM) {
                val stem = word.dropLast(ending.length)
                for (n in nominative) (stem + n).let { if (it !in out) out += it }
                break
            }
        }
        return out
    }

    /** Имя как сказано (первым) и возможные именительные формы, нормализованные [norm]. */
    fun forms(contact: String): List<String> {
        val n = norm(contact)
        if (n.isEmpty()) return emptyList()
        val words = n.split(' ')
        if (words.size > MAX_WORDS) return listOf(n)
        var acc = listOf("")
        for (w in words) {
            val wf = wordForms(w)
            acc = acc.flatMap { prefix -> wf.map { if (prefix.isEmpty()) it else "$prefix $it" } }
        }
        return (listOf(n) + acc).distinct().take(MAX_FORMS)
    }

    /**
     * Что печатать в поиск мессенджера: общее начало всех форм («Серому» → «сер»), чтобы нашлись
     * и «Серый», и «Серому». Выбор строки всё равно идёт по точному совпадению [matches].
     */
    fun searchQuery(contact: String): String {
        val f = forms(contact)
        if (f.size <= 1) return f.firstOrNull() ?: ""
        var prefix = f[0]
        for (x in f.drop(1)) prefix = prefix.commonPrefixWith(x)
        prefix = prefix.trim()
        return if (prefix.length >= MIN_STEM) prefix else f[0]
    }
}

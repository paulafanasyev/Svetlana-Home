package com.svetlana.home.control

/**
 * Сравнение имени контакта с подписью на экране мессенджера (чистая функция, без Android).
 *
 * Регистр и «ё» не важны (распознавание речи и парсер пишут «е», а в WhatsApp — «Серёга»).
 * Совпадение — только точное имя или имя + продолжение через пробел («Серый Иванов»,
 * «Серый 🔥»). «Серыйкин» и «Иван Серый» — не совпадение: писать не тому человеку нельзя.
 */
object ContactMatch {

    private val SPACES = Regex("\\s+")

    fun norm(s: String): String =
        s.trim().lowercase().replace('ё', 'е').replace(SPACES, " ")

    fun matches(shown: String, contact: String): Boolean {
        val want = norm(contact)
        if (want.isEmpty()) return false
        val s = norm(shown)
        return s == want || s.startsWith("$want ")
    }
}

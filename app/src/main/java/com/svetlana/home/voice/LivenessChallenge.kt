package com.svetlana.home.voice

import java.security.SecureRandom

/**
 * Защита от записи голоса: Светлана просит произнести случайные цифры. Заранее записать
 * нужную фразу нельзя: цифры каждый раз новые (SecureRandom, 900 вариантов на попытку,
 * а после 5 неудач голос ставится на паузу). Проверяем два факта по одному и тому же
 * звуку: распознанный текст = загаданные цифры И голос = владелец.
 *
 * Чистая логика, покрыта юнит-тестами. Понимает «7 4 2», «742», «семь, четыре, два»
 * и «семьсот сорок два». Составное число разбирается по строгой грамматике: сотни,
 * десятки, единицы по убыванию, каждый разряд не больше одного раза, лишнее слово = отказ.
 */
object LivenessChallenge {
    const val LENGTH = 3

    private val SPOKEN = arrayOf("ноль", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять")

    private val UNITS = mapOf(
        "ноль" to 0, "нуль" to 0, "один" to 1, "одна" to 1, "два" to 2, "две" to 2, "три" to 3,
        "четыре" to 4, "пять" to 5, "шесть" to 6, "семь" to 7, "восемь" to 8, "девять" to 9,
    )
    private val TEENS = mapOf(
        "десять" to 10, "одиннадцать" to 11, "двенадцать" to 12, "тринадцать" to 13,
        "четырнадцать" to 14, "пятнадцать" to 15, "шестнадцать" to 16, "семнадцать" to 17,
        "восемнадцать" to 18, "девятнадцать" to 19,
    )
    private val TENS = mapOf(
        "двадцать" to 20, "тридцать" to 30, "сорок" to 40, "пятьдесят" to 50, "шестьдесят" to 60,
        "семьдесят" to 70, "восемьдесят" to 80, "девяносто" to 90,
    )
    private val HUNDREDS = mapOf(
        "сто" to 100, "двести" to 200, "триста" to 300, "четыреста" to 400, "пятьсот" to 500,
        "шестьсот" to 600, "семьсот" to 700, "восемьсот" to 800, "девятьсот" to 900,
    )

    class Challenge(digits: IntArray) {
        val digits: IntArray = digits.copyOf()
        /** Цифры строкой, например "742". */
        val code: String = digits.joinToString("")
        /** Как Светлана произносит: "семь, четыре, два". */
        val spoken: String = digits.joinToString(", ") { SPOKEN[it] }
    }

    /** Первая цифра не ноль (иначе «042» часто распознаётся как «42»). */
    fun generate(random: java.util.Random = SecureRandom()): Challenge =
        Challenge(IntArray(LENGTH) { if (it == 0) 1 + random.nextInt(9) else random.nextInt(10) })

    /** Сказаны ли ровно загаданные цифры. Посторонние слова («это», «света») игнорируются. */
    fun matches(transcript: String?, challenge: Challenge): Boolean {
        if (transcript.isNullOrBlank() || transcript.length > 200) return false
        val text = transcript.lowercase().replace('ё', 'е')
        val tokens = Regex("[0-9]+|[а-я]+").findAll(text).map { it.value }
            .filter { it[0].isDigit() || it in UNITS || it in TEENS || it in TENS || it in HUNDREDS }
            .toList()
        if (tokens.isEmpty()) return false

        val compound = tokens.any { it in TEENS || it in TENS || it in HUNDREDS }
        if (!compound) {
            // «7 4 2», «742», «семь четыре два», «7 четыре 2»
            val seq = StringBuilder()
            for (t in tokens) {
                if (t[0].isDigit()) seq.append(t) else seq.append(UNITS.getValue(t))
                if (seq.length > 12) return false
            }
            return seq.toString() == challenge.code
        }
        return parseCompound(tokens)?.toString() == challenge.code
    }

    /**
     * «семьсот сорок два», «семьсот 42», «семьсот два». Каждая часть обязана быть меньше
     * оставшегося разряда ([limit]); ноль и лишние части дают null.
     */
    private fun parseCompound(tokens: List<String>): Int? {
        var value = 0
        var limit = 1000
        for (t in tokens) {
            when {
                t[0].isDigit() -> {
                    if (t.length > 3) return null
                    val n = t.toInt()
                    if (n == 0 || n >= limit) return null
                    value += n
                    limit = 1
                }
                t in HUNDREDS -> {
                    val n = HUNDREDS.getValue(t)
                    if (n >= limit) return null
                    value += n
                    limit = 100
                }
                t in TENS -> {
                    val n = TENS.getValue(t)
                    if (n >= limit) return null
                    value += n
                    limit = 10
                }
                t in TEENS -> {
                    val n = TEENS.getValue(t)
                    if (n >= limit) return null
                    value += n
                    limit = 1
                }
                else -> {
                    val n = UNITS.getValue(t)
                    if (n == 0 || n >= limit) return null
                    value += n
                    limit = 1
                }
            }
        }
        return value
    }
}

/**
 * Доверенная голосовая сессия: после успешной проверки цифрами следующие команды
 * владельца проверяются только по голосу (каждая фраза!). Сессия живёт [idleMs] с последней
 * команды и не дольше [maxMs] всего; любая смена шаблона или чужой голос её закрывает.
 * Часы монотонные (elapsedRealtime), перевод времени на телефоне сессию не продлит.
 */
class VoiceTrust(
    private val idleMs: Long = 2 * 60_000L,
    private val maxMs: Long = 10 * 60_000L,
) {
    private var since = 0L
    private var last = 0L
    private var generation = -1L
    private var active = false

    @Synchronized
    fun isValid(gen: Long, now: Long): Boolean =
        active && gen == generation && now >= last && now - last <= idleMs && now - since <= maxMs

    @Synchronized
    fun start(gen: Long, now: Long) {
        active = true
        generation = gen
        since = now
        last = now
    }

    @Synchronized
    fun touch(now: Long) {
        if (active) last = now
    }

    @Synchronized
    fun end() {
        active = false
        generation = -1L
    }
}

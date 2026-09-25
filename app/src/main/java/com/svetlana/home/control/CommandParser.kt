package com.svetlana.home.control

import com.svetlana.home.apps.AppAliases

/**
 * CommandParser — разбор естественных русских команд в доменные действия.
 *
 * Не зависит от Android-контекста, поэтому полностью покрывается unit-тестами.
 * Понимает русские псевдонимы приложений (AppAliases).
 */
object CommandParser {

    private val wakeWords = listOf("света", "светочка", "светлана")

    private val openMarkers = listOf(
        "переключись на", "открой приложение", "открой", "запусти приложение", "запусти",
        "открой приложения", "вернись в", "перейди в"
    )

    fun parse(rawCommand: String): SvetlanaAction? {
        val c = rawCommand.trim()
        if (c.isEmpty()) return null
        val low = c.lowercase().replace("ё", "е")
        val body = stripWakeWord(low).trim()
        if (body.isEmpty()) return null
        // Оригинальный текст нужен для сохранения регистра элементов и фраз
        // (светлана должна нажать именно «Отправить», а не «отправить»).
        val original = stripWakeWord(c).trim()

        // Навигация
        when {
            body.matches(Regex(".*(вернись назад|назад|go back).*")) -> return SvetlanaAction.PressBack
            body.matches(Regex(".*(домой|на главный экран|go home).*")) -> return SvetlanaAction.PressHome
            body.matches(Regex(".*(недавние|список приложений|recents|показывай недавние).*")) ->
                return SvetlanaAction.OpenRecents
            body.startsWith("открой настройки") || body == "настройки" || body == "настройки приложения" ->
                return SvetlanaAction.OpenSettings
        }

        // Скриншот
        if (body.matches(Regex(".*(сделай скриншот|снимок экрана|скриншот).*")))
            return SvetlanaAction.TakeScreenshot("current")

        // Прокрутка
        Regex(".*(пролистай|прокрути|пролистай вниз|scroll)\\s*(экран|страницу)?\\s*(вниз|вверх|up|down).*")
            .matchEntire(body)?.let {
                return SvetlanaAction.Scroll("current", it.groupValues[3])
            }

        // Свайп
        Regex(".*(свайпни|смахни|swipe)\\s+(вниз|вверх|влево|вправо|up|down|left|right).*")
            .matchEntire(body)?.let {
                return SvetlanaAction.Swipe("current", it.groupValues[2])
            }

        // Открытие приложений
        when {
            body.matches(Regex(".*(открой|запусти)\\s+(приложение\\s+)?для тестирования.*")) ->
                return SvetlanaAction.OpenApp(HARNESS_NAME)
            body.startsWith("переключись на") -> {
                val target = extractTarget(body, "переключись на")
                if (target.isNotBlank()) return SvetlanaAction.OpenApp(target)
            }
            body.startsWith("открой приложение") || body.startsWith("открой") ||
                    body.startsWith("запусти приложение") || body.startsWith("запусти") -> {
                val target = extractTarget(body)
                if (target.isNotBlank()) return SvetlanaAction.OpenApp(target)
            }
        }

        // Ввод текста
        Regex("^(введи|ввести|введи это значение|type|вставь)\\s+(.*)").matchEntire(body)?.let {
            val value = it.groupValues[2].trim().trim('"', '«', '»')
            if (value.isNotBlank()) return SvetlanaAction.TypeText("current", "input", value)
        }

        // Нажатие
        Regex("^(нажми|кликни|tap|press|нажать)\\s+(на\\s+)?(кнопку\\s+|элемент\\s+)?(.*)")
            .matchEntire(body)?.let {
                val element = it.groups[4]!!.value.trim()
                if (element.isNotBlank()) {
                    val origElement = originalElement(original, body, element)
                    return SvetlanaAction.Click("current", origElement)
                }
            }

        // Чтение экрана
        if (body.matches(Regex(".*(прочитай экран|что на экране|что сейчас на экране|read screen|прочитай что на экране).*")))
            return SvetlanaAction.ReadScreen("current")

        // Поиск элемента
        Regex("^(найди|поищи|find)\\s+(.*)").matchEntire(body)?.let {
            val el = it.groups[2]!!.value.trim()
            if (el.isNotBlank()) {
                val origEl = originalElement(original, body, el)
                return SvetlanaAction.FindElement("current", origEl)
            }
        }

        // Перевод
        Regex("^(переведи|перевести|перевод|translate)\\s+(.*)").matchEntire(body)?.let {
            val rest = it.groups[2]!!.value.trim()
            val direction = if (body.contains("вьетнамск") || body.contains("vietnamese")) "ru-vi"
            else if (body.contains("русск")) "vi-ru"
            else "ru-vi"
            // Текст может быть в кавычках или идти после "фразу"
            val text = extractQuoted(rest) ?: rest.removePrefix("фразу").trim().removePrefix("это").trim()
            // Сохраняем оригинальный регистр фразы, если она была в кавычках
            val finalText = if (text.isNotBlank()) originalElement(original, body, text) else text
            return SvetlanaAction.Translate(finalText, direction)
        }

        // Звонок / сообщения
        Regex("^(позвони|звонок|call|набери)\\s+(.*)").matchEntire(body)?.let {
            return SvetlanaAction.MakeCall(it.groupValues[2].trim())
        }
        Regex("^(отправь сообщение|напиши сообщение|смс|sms|send message)\\s+(.*)")
            .matchEntire(body)?.let {
                return SvetlanaAction.SendMessage(it.groupValues[2].trim(), "")
            }

        if (body.matches(Regex(".*(поделись|share|отправь кому).*"))) return SvetlanaAction.Share("")

        return null
    }

    fun stripWakeWord(command: String): String {
        val low = command.lowercase().replace("ё", "е")
        for (w in wakeWords) {
            if (low == w) return ""
            if (low.startsWith("$w ") || low.startsWith("$w,") || low.startsWith("$w:")) {
                return command.substring(w.length).trimStart(' ', ',', ':')
            }
        }
        return command
    }

    /**
     * Возвращает [needle] в оригинальном регистре, ища его в [original].
     * body и original совпадают посимвольно, отличаются только регистром.
     */
    private fun originalElement(original: String, body: String, needle: String): String {
        val idx = body.indexOf(needle)
        if (idx < 0 || idx + needle.length > original.length) return needle
        return original.substring(idx, idx + needle.length)
    }

    private fun extractTarget(body: String, explicitMarker: String? = null): String {
        val marker = explicitMarker ?: openMarkers.firstOrNull { body.startsWith(it) }
        if (marker == null) return ""
        var target = body.removePrefix(marker).trim()
        target = target.trim(' ', '.', ',', '!', '?')
        // Убираем связки в начале
        val fillers = listOf("приложение ", "программу ", "это ")
        for (f in fillers) if (target.startsWith(f)) target = target.removePrefix(f)
        return target.trim()
    }

    private fun extractQuoted(rest: String): String? {
        if (rest.contains('"')) return rest.substringAfter('"').substringBefore('"')
        if (rest.contains('«')) return rest.substringAfter('«').substringBefore('»')
        return null
    }

    /**
     * Известные имена Mobile Harness для разрешения команд.
     */
    const val HARNESS_NAME = "mobile harness"
}

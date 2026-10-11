package com.svetlana.home.ai

import java.net.URI

/**
 * Откуда разрешено качать модели: только HTTPS и только Hugging Face (включая его CDN *.hf.co).
 * Проверяется каждый шаг редиректа, чтобы подменённая ссылка или редирект не привели к чужому файлу.
 */
object ModelSourcePolicy {
    private val HOST_SUFFIXES = listOf("huggingface.co", "hf.co")

    fun isAllowedHost(host: String?): Boolean {
        val h = host?.lowercase()?.trimEnd('.') ?: return false
        return HOST_SUFFIXES.any { h == it || h.endsWith(".$it") }
    }

    fun isAllowedUrl(url: String): Boolean = try {
        val u = URI(url)
        u.scheme.equals("https", ignoreCase = true) && u.userInfo == null &&
            (u.port == -1 || u.port == 443) && isAllowedHost(u.host)
    } catch (e: Exception) {
        false
    }

    /** Верхняя граница размера файла: заявленный размер + 20% + 16 МБ (защита от забивания памяти). */
    fun maxBytes(sizeMb: Long, storageMb: Long): Long =
        maxOf(sizeMb, storageMb) * 1024L * 1024L * 12L / 10L + 16L * 1024L * 1024L
}

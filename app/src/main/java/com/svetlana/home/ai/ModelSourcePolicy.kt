package com.svetlana.home.ai

import java.net.URI

/**
 * Откуда разрешено качать модели: только HTTPS и только
 *  - Hugging Face (huggingface.co, CDN *.hf.co);
 *  - релизы GitHub согласованных проектов (k2-fsa/sherpa-onnx — CAM++, snakers4/silero-models)
 *    и их хранилище файлов *.githubusercontent.com, куда GitHub перенаправляет загрузку.
 * Проверяется каждый шаг редиректа, чтобы подменённая ссылка или редирект не привели к чужому файлу.
 */
object ModelSourcePolicy {
    private val HF_SUFFIXES = listOf("huggingface.co", "hf.co")
    private val GITHUB_RELEASE_OWNERS = listOf("/k2-fsa/sherpa-onnx/releases/download/", "/snakers4/silero-models/releases/download/")
    private val GITHUB_ASSET_HOSTS = setOf("objects.githubusercontent.com", "release-assets.githubusercontent.com")

    private fun bySuffix(h: String, suffixes: List<String>) = suffixes.any { h == it || h.endsWith(".$it") }

    fun isAllowedHost(host: String?): Boolean {
        val h = host?.lowercase()?.trimEnd('.') ?: return false
        return bySuffix(h, HF_SUFFIXES) || h in GITHUB_ASSET_HOSTS
    }

    fun isAllowedUrl(url: String): Boolean = try {
        val u = URI(url)
        val host = u.host?.lowercase()?.trimEnd('.')
        val basic = u.scheme.equals("https", ignoreCase = true) && u.userInfo == null && (u.port == -1 || u.port == 443)
        basic && when {
            host == null -> false
            host == "github.com" -> GITHUB_RELEASE_OWNERS.any { (u.rawPath ?: "").startsWith(it) } && !(u.rawPath ?: "").contains("..")
            else -> isAllowedHost(host)
        }
    } catch (e: Exception) {
        false
    }

    /** Верхняя граница размера файла: заявленный размер + 20% + 16 МБ (защита от забивания памяти). */
    fun maxBytes(sizeMb: Long, storageMb: Long): Long =
        maxOf(sizeMb, storageMb) * 1024L * 1024L * 12L / 10L + 16L * 1024L * 1024L
}

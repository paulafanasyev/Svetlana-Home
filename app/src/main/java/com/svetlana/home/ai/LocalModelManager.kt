package com.svetlana.home.ai

import android.content.Context
import android.util.Log
import com.svetlana.home.core.SvetlanaStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Управление установленными локальными моделями.
 *
 * КРИТИЧЕСКОЕ ПРАВИЛО (ТЗ §32, §33, §87):
 * Локальная модель НИКОГДА не скачивается автоматически.
 *
 * Целостность: любая модель скачивается только по https, не больше [maxDownloadBytes], и
 * устанавливается только при совпадении с доверенным SHA-256 из реестра. Перед КАЖДОЙ
 * загрузкой в нативный runtime файл заново хешируется и сверяется с хешем из реестра (сохранённый
 * на диске хеш не считается доверенным).
 *
 * Докачка: модели по 1–5 ГБ, мобильная связь рвётся. Частичный файл (.part) не удаляется при
 * обрыве: загрузка продолжается с того же места (HTTP Range → 206), в том числе после повторного
 * нажатия «Скачать». Проверка SHA-256 в конце защищает от склейки чужих кусков: несовпадение —
 * файл удаляется и следующая попытка начинается с нуля.
 */
@Serializable
data class InstalledModel(
    val modelId: String,
    val name: String,
    val filePath: String,
    val sizeBytes: Long,
    val installedAt: Long,
    val sha256: String? = null,
    val formatVerified: Boolean = false,
    val benchmark: BenchmarkResult? = null
)

@Serializable
data class BenchmarkResult(
    val modelId: String,
    val loadTimeMs: Long,
    val firstTokenMs: Long,
    val tokensPerSecond: Double,
    val ramUsedMb: Int,
    val cpuPercent: Int,
    val thermal: String,
    val batteryImpact: Int,
    val contextStable: Boolean,
    val status: String
)

/** Загрузка остановлена, но частичный файл сохранён: следующее «Скачать» продолжит. Не ошибка. */
class DownloadPaused(val percent: Int, message: String) : IllegalStateException(message)

open class LocalModelManager(
    private val context: Context,
    private val registry: AIModelRegistry = AIModelRegistry()
) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val storeFile: File by lazy { File(context.filesDir, "local_models.json") }
    private val modelsDir: File by lazy { File(context.filesDir, "models").apply { mkdirs() } }

    /** Успешные проверки: modelId → «путь|размер|время|хеш». Только для быстрых проверок готовности. */
    private val integrityCache = ConcurrentHashMap<String, String>()

    @Volatile
    private var installed: List<InstalledModel> = load()

    open fun list(): List<InstalledModel> = installed

    fun isInstalled(modelId: String): Boolean = installed.any { it.modelId == modelId }

    open fun byId(modelId: String): InstalledModel? = installed.firstOrNull { it.modelId == modelId }

    fun fileFor(modelId: String): File? = byId(modelId)?.let { File(it.filePath) }

    fun freeSpaceBytes(): Long = modelsDir.usableSpace

    private fun maxDownloadBytes(model: AIModel): Long =
        maxOf(model.sizeMb.toLong() * 1024L * 1024L * 5L / 4L, 64L * 1024L * 1024L)

    /** Уже скачано байт для незаконченной загрузки (0 — нет частичного файла). */
    fun partialBytes(model: AIModel): Long {
        val temp = File(modelsDir, ".${model.id}.${extensionFor(model)}.part")
        return if (temp.isFile) temp.length() else 0L
    }

    /**
     * Файл модели не изменён: лежит в нашей папке моделей и его SHA-256 совпадает с доверенным
     * хешем ИЗ РЕЕСТРА. Модель без доверенного хеша не проходит проверку никогда.
     * @param fresh true (по умолчанию) — всегда заново хешировать файл (перед загрузкой в память);
     *   false — для проверок готовности можно взять недавний успешный результат для того же файла.
     */
    open fun verifyIntegrity(modelId: String, fresh: Boolean = true): Boolean {
        val m = byId(modelId) ?: return false
        return try {
            val f = File(m.filePath)
            if (!f.isFile) return false
            val canonical = f.canonicalPath
            if (!canonical.startsWith(modelsDir.canonicalPath + File.separator)) return false
            val trusted = registry.byId(modelId)?.expectedSha256?.takeIf { it.length == 64 } ?: return false
            val stamp = "$canonical|${f.length()}|${f.lastModified()}|${trusted.lowercase()}"
            if (!fresh && integrityCache[modelId] == stamp) return true
            val ok = sha256(f).equals(trusted, ignoreCase = true)
            if (ok) integrityCache[modelId] = stamp else {
                integrityCache.remove(modelId)
                Log.w(TAG, "Контрольная сумма модели $modelId не совпадает с реестром — файл изменён или повреждён")
            }
            ok
        } catch (t: Throwable) { false }
    }

    /** Итог одной попытки скачивания. */
    private sealed class Chunk {
        object Done : Chunk()
        class Fatal(val message: String) : Chunk()
        class Retry(val error: Throwable) : Chunk()
        object Cancelled : Chunk()
    }

    private fun paused(temp: File, model: AIModel): DownloadPaused {
        val p = percentOf(if (temp.isFile) temp.length() else 0L, model)
        return DownloadPaused(p, "Загрузка приостановлена ($p%). Нажмите «Скачать» — она продолжится с того же места.")
    }

    /**
     * @param cancelled проверяется на каждом блоке: true — остановиться, сохранив частичный файл
     *   для докачки (лимит времени foreground-сервиса в Android 15). Пауза возвращается как [DownloadPaused].
     */
    fun install(
        model: AIModel,
        cancelled: () -> Boolean = { false },
        progress: (percent: Int) -> Unit = {}
    ): Result<InstalledModel> {
        val ext = extensionFor(model)
        val target = File(modelsDir, "${model.id}.$ext")
        val temp = File(modelsDir, ".${model.id}.$ext.part")
        val backup = File(modelsDir, ".${model.id}.$ext.previous")
        integrityCache.remove(model.id)

        return try {
            if (!model.runtimeImplemented) {
                return Result.failure(IllegalStateException(
                    "Для модели ${model.name} встроенный runtime ещё не реализован; скачивание отключено."))
            }
            val expected = model.expectedSha256
            if (expected.isNullOrBlank() || expected.length != 64) {
                return Result.failure(IllegalStateException(
                    "Для модели ${model.name} нет доверенной контрольной суммы SHA-256; скачивание отключено."))
            }
            if (!model.downloadUrl.startsWith("https://", ignoreCase = true)) {
                return Result.failure(IllegalStateException("Модели скачиваются только по защищённому https"))
            }
            // Процесс упал между «старая → .previous» и «новая → на место»: сначала вернуть старую.
            if (!target.exists() && backup.exists() && backup.renameTo(target)) {
                Log.w(TAG, "Восстановлена предыдущая версия ${model.id} после прерванной установки")
            }
            val already = if (temp.isFile) temp.length() else 0L
            if (!isSpaceEnough(model, already)) {
                return Result.failure(IllegalStateException("Недостаточно свободного места"))
            }
            val limit = maxDownloadBytes(model)
            Log.i(TAG, "Загрузка модели ${model.name} (${model.sizeMb}MB) по решению пользователя; уже есть $already байт")
            if (target.exists()) backup.delete()
            val client = okhttp3.OkHttpClient.Builder()
                .followSslRedirects(false)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()

            var attempt = 0
            while (true) {
                attempt++
                when (val chunk = downloadOnce(client, model, temp, limit, cancelled, progress)) {
                    Chunk.Done -> break
                    Chunk.Cancelled -> return Result.failure(paused(temp, model))
                    is Chunk.Fatal -> {
                        temp.delete()
                        return Result.failure(IllegalStateException(chunk.message))
                    }
                    is Chunk.Retry -> {
                        Log.w(TAG, "Попытка $attempt не удалась, скачано ${temp.length()} байт", chunk.error)
                        if (attempt >= MAX_ATTEMPTS) {
                            val p = percentOf(temp.length(), model)
                            return Result.failure(DownloadPaused(p,
                                "Связь прерывается ($p% скачано). Нажмите «Скачать» ещё раз — загрузка продолжится."))
                        }
                        sleepUnlessCancelled(BACKOFF_MS * attempt, cancelled)
                        if (cancelled()) return Result.failure(paused(temp, model))
                    }
                }
            }

            if (temp.length() < model.sizeMb * 1024L * 1024L / 4L) {
                temp.delete()
                return Result.failure(IllegalStateException("Файл загрузился не полностью"))
            }
            val sha = sha256(temp)
            if (!verifyFormat(temp, model)) {
                temp.delete()
                return Result.failure(IllegalStateException(
                    "Скачанный файл не является моделью формата ${model.backend}"))
            }
            if (!sha.equals(expected, ignoreCase = true)) {
                temp.delete()
                return Result.failure(IllegalStateException(
                    "Контрольная сумма файла не совпадает с доверенной. Загрузка отменена. Предыдущая модель сохранена."))
            }

            if (target.exists()) {
                backup.delete()
                if (!target.renameTo(backup)) {
                    temp.delete()
                    return Result.failure(IllegalStateException(
                        "Не удалось сохранить предыдущую версию модели перед заменой"))
                }
            }
            if (!temp.renameTo(target)) {
                temp.delete()
                if (backup.exists()) backup.renameTo(target)
                return Result.failure(IllegalStateException(
                    "Не удалось завершить установку модели; предыдущая модель восстановлена"))
            }
            if (backup.exists() && !backup.delete()) {
                Log.w(TAG, "Новая модель установлена, но не удалось удалить backup: ${backup.name}")
            }
            val installedModel = InstalledModel(
                modelId = model.id, name = model.name,
                filePath = target.absolutePath, sizeBytes = target.length(),
                installedAt = System.currentTimeMillis(),
                sha256 = sha,
                formatVerified = true
            )
            installed = installed.filterNot { it.modelId == model.id } + installedModel
            save()
            Log.i(TAG, "Модель ${model.name} установлена")
            Result.success(installedModel)
        } catch (t: Throwable) {
            // Частичный файл оставляем для докачки: целостность всё равно проверит SHA-256.
            if (!target.exists() && backup.exists()) {
                backup.renameTo(target)
            }
            Log.e(TAG, "Не удалось загрузить модель", t)
            Result.failure(t)
        }
    }

    private fun percentOf(bytes: Long, model: AIModel): Int {
        val total = model.sizeMb.toLong() * 1024L * 1024L
        return if (total <= 0) 0 else ((bytes * 100) / total).toInt().coerceIn(0, 99)
    }

    private fun sleepUnlessCancelled(ms: Long, cancelled: () -> Boolean) {
        var left = ms
        while (left > 0 && !cancelled()) {
            val step = minOf(left, 500L)
            Thread.sleep(step)
            left -= step
        }
    }

    /** Одна попытка: продолжает частичный файл (Range) или начинает заново, если сервер прислал весь файл. */
    private fun downloadOnce(
        client: okhttp3.OkHttpClient,
        model: AIModel,
        temp: File,
        limit: Long,
        cancelled: () -> Boolean,
        progress: (Int) -> Unit
    ): Chunk {
        val have = if (temp.isFile) temp.length() else 0L
        if (have > limit) {
            return Chunk.Fatal("Файл больше ожидаемого — загрузка отменена")
        }
        val request = okhttp3.Request.Builder().url(model.downloadUrl).apply {
            if (have > 0) header("Range", "bytes=$have-")
        }.build()
        return try {
            client.newCall(request).execute().use { response ->
                // Диапазон за концом файла: частичный файл уже полный (проверит SHA-256) или битый.
                if (response.code == 416 && have > 0) return Chunk.Done
                if (!response.isSuccessful) {
                    val code = response.code
                    return if (code == 408 || code == 429 || code in 500..599) {
                        Chunk.Retry(IOException("HTTP $code"))
                    } else {
                        Chunk.Fatal("Сервер вернул код $code")
                    }
                }
                if (!response.request.url.isHttps) {
                    return Chunk.Fatal("Загрузка перенаправлена на незащищённый адрес — отменена")
                }
                val body = response.body ?: return Chunk.Retry(IOException("Пустой ответ"))
                val append = have > 0 && response.code == 206
                if (append && contentRangeStart(response.header("Content-Range")) != have) {
                    // Сервер продолжил не с того места — склеивать нельзя, начинаем заново.
                    temp.delete()
                    return Chunk.Retry(IOException("Content-Range не совпадает"))
                }
                val start = if (append) have else 0L
                val length = body.contentLength()
                val total = if (length >= 0) start + length else -1L
                if (total > limit) {
                    return Chunk.Fatal("Файл на сервере больше ожидаемого — загрузка отменена")
                }
                var copied = start
                body.byteStream().use { input ->
                    FileOutputStream(temp, append).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (cancelled()) return Chunk.Cancelled
                            val n = input.read(buffer)
                            if (n <= 0) break
                            copied += n
                            if (copied > limit) {
                                return Chunk.Fatal("Файл больше ожидаемого — загрузка отменена")
                            }
                            output.write(buffer, 0, n)
                            // Без Content-Length прогресс считаем по размеру из реестра (не больше 99%).
                            progress(if (total > 0) ((copied * 100) / total).toInt() else percentOf(copied, model))
                        }
                    }
                }
                if (total >= 0 && copied != total) Chunk.Retry(IOException("Связь прервалась: $copied/$total"))
                else Chunk.Done
            }
        } catch (e: IOException) {
            Chunk.Retry(e)
        }
    }

    fun uninstall(modelId: String): Boolean {
        val model = byId(modelId) ?: return false
        integrityCache.remove(modelId)
        val deleted = try { File(model.filePath).delete() } catch (t: Throwable) { false }
        installed = installed.filterNot { it.modelId == modelId }
        save()
        return deleted
    }

    fun recordBenchmark(result: BenchmarkResult) {
        installed = installed.map {
            if (it.modelId == result.modelId) it.copy(benchmark = result) else it
        }
        save()
    }

    fun statusFor(
        modelId: String,
        runtimeReady: Boolean = false,
        modelLoaded: Boolean = false,
        inferenceVerified: Boolean = false
    ): ModelStatus {
        val model = byId(modelId) ?: return ModelStatus.NOT_INSTALLED
        return ModelStatus.fromFacts(
            installed = true,
            formatVerified = model.formatVerified,
            runtimeReady = runtimeReady,
            modelLoaded = modelLoaded,
            inferenceVerified = inferenceVerified,
            visionVerified = false,
            deviceVerified = model.benchmark?.status == SvetlanaStatus.DEVICE_VERIFIED
        )
    }

    fun isActive(modelId: String, activeId: String?): Boolean = activeId != null && modelId == activeId

    fun canUninstall(modelId: String, activeId: String?): Boolean =
        !isActive(modelId, activeId)

    fun benchmarkStatusFor(modelId: String): String {
        val model = byId(modelId) ?: return SvetlanaStatus.NOT_PROVEN
        return model.benchmark?.let {
            if (it.status == SvetlanaStatus.DEVICE_VERIFIED) SvetlanaStatus.DEVICE_VERIFIED
            else SvetlanaStatus.NOT_PROVEN
        } ?: SvetlanaStatus.NOT_PROVEN
    }

    /** Уже скачанная часть файла места больше не требует. */
    private fun isSpaceEnough(model: AIModel, alreadyBytes: Long = 0L): Boolean =
        freeSpaceBytes() + alreadyBytes > model.storageRequirementMb * 1024L * 1024L * 1.1

    private fun extensionFor(model: AIModel): String = when (model.backend) {
        "litertlm" -> "litertlm"
        "llama.cpp" -> "gguf"
        else -> "bin"
    }

    fun migrateExtensions() {
        var changed = false
        val mapped = installed.map { m ->
            val current = File(m.filePath)
            if (current.exists()) return@map m
            val model = registry.byId(m.modelId) ?: return@map m
            val legacy = File(modelsDir, "${m.modelId}.bin")
            if (!legacy.exists()) return@map m
            val renamed = File(modelsDir, "${m.modelId}.${extensionFor(model)}")
            if (!legacy.renameTo(renamed)) return@map m
            changed = true
            Log.i(TAG, "Миграция ${m.modelId}: .bin → ${renamed.name}")
            m.copy(filePath = renamed.absolutePath)
        }
        if (changed) {
            installed = mapped
            save()
        }
    }

    private fun verifyFormat(file: File, model: AIModel): Boolean {
        return try {
            when (model.backend) {
                "llama.cpp" -> ModelFormat.GGUF.matchesMagic(readHead(file, 4))
                "litertlm" -> ModelFormat.LITERT_LM.matchesMagic(readHead(file, 8))
                else -> false
            }
        } catch (t: Throwable) { false }
    }

    private fun readHead(file: File, n: Int): ByteArray =
        file.inputStream().use { input ->
            val buffer = ByteArray(n)
            var read = 0
            while (read < n) {
                val r = input.read(buffer, read, n - read)
                if (r <= 0) break
                read += r
            }
            if (read == n) buffer else buffer.copyOf(read)
        }

    private fun sha256(file: File): String = try {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                digest.update(buffer, 0, n)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (t: Throwable) { "" }

    private fun load(): List<InstalledModel> = try {
        if (storeFile.exists() && storeFile.length() > 0)
            json.decodeFromString(ListSerializer(InstalledModel.serializer()), storeFile.readText())
        else emptyList()
    } catch (t: Throwable) { emptyList() }

    private fun save() {
        try { storeFile.writeText(json.encodeToString(ListSerializer(InstalledModel.serializer()), installed)) } catch (t: Throwable) { /* ignore */ }
    }

    companion object {
        private const val TAG = "LocalModels"
        private const val MAX_ATTEMPTS = 8
        private const val BACKOFF_MS = 3_000L

        /** «bytes 1000-1999/5000» → 1000; что угодно другое → null. */
        fun contentRangeStart(header: String?): Long? {
            val h = header?.trim()?.lowercase() ?: return null
            if (!h.startsWith("bytes ")) return null
            return h.removePrefix("bytes ").substringBefore('-').trim().toLongOrNull()
        }
    }
}

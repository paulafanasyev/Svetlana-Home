package com.svetlana.home.ai

import android.content.Context
import android.util.Log
import com.svetlana.home.core.SvetlanaStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Управление установленными локальными моделями.
 *
 * КРИТИЧЕСКОЕ ПРАВИЛО (ТЗ §32, §33, §87):
 * Локальная модель НИКОГДА не скачивается автоматически.
 * Даже если найдена идеально совместимая модель.
 *
 * Только после явного решения пользователя:
 *   Предложить → Показать размер → Показать требования → Решение → Скачать
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

/**
 * open — чтобы unit-тесты могли подставлять предустановленный список моделей,
 * не вызывая реальную загрузку файлов (аудит п.6: проверка выбора модели).
 */
open class LocalModelManager(
    private val context: Context,
    private val registry: AIModelRegistry = AIModelRegistry()
) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val storeFile: File by lazy { File(context.filesDir, "local_models.json") }
    private val modelsDir: File by lazy { File(context.filesDir, "models").apply { mkdirs() } }

    @Volatile
    private var installed: List<InstalledModel> = load()

    open fun list(): List<InstalledModel> = installed

    fun isInstalled(modelId: String): Boolean = installed.any { it.modelId == modelId }

    open fun byId(modelId: String): InstalledModel? = installed.firstOrNull { it.modelId == modelId }

    fun fileFor(modelId: String): File? = byId(modelId)?.let { File(it.filePath) }

    fun freeSpaceBytes(): Long = modelsDir.usableSpace

    /**
     * Запрос на загрузку модели. Этот метод только НАЧИНАЕТ загрузку после
     * явного решения пользователя — вызывается из UI по тапу на «Скачать».
     */
    fun install(
        model: AIModel,
        progress: (percent: Int) -> Unit = {}
    ): Result<InstalledModel> {
        val ext = extensionFor(model)
        val target = File(modelsDir, "${model.id}.$ext")
        val temp = File(modelsDir, ".${model.id}.$ext.part")
        val backup = File(modelsDir, ".${model.id}.$ext.previous")

        return try {
            if (!model.runtimeImplemented) {
                return Result.failure(IllegalStateException(
                    "Для модели ${model.name} встроенный runtime ещё не реализован; скачивание отключено."))
            }
            if (!isSpaceEnough(model)) {
                return Result.failure(IllegalStateException("Недостаточно свободного места"))
            }
            Log.i(TAG, "Начинаю загрузку модели ${model.name} (${model.sizeMb}MB) по решению пользователя")
            // Расширение файла обязано совпадать с форматом: движок LiteRT-LM
            // отклоняет тот же контент, если файл назван .bin вместо .litertlm
            // (upstream issue: загрузка зависит от расширения). Для GGUF
            // расширение не принципиально, но оставляем осмысленное.
            temp.delete()
            backup.delete()
            val client = okhttp3.OkHttpClient.Builder().build()
            val request = okhttp3.Request.Builder().url(model.downloadUrl).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.failure(IllegalStateException("Сервер вернул код ${response.code}"))
                }
                val body = response.body ?: return Result.failure(IllegalStateException("Пустой ответ"))
                val total = body.contentLength()
                var copied = 0L
                body.byteStream().use { input ->
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n <= 0) break
                            output.write(buffer, 0, n)
                            copied += n
                            if (total > 0) progress(((copied * 100) / total).toInt())
                        }
                    }
                }
                if (total >= 0 && copied != total) {
                    temp.delete()
                    return Result.failure(IllegalStateException("Размер загрузки не совпадает с Content-Length ($copied/$total)"))
                }
            }
            if (temp.length() < model.sizeMb * 1024L * 1024L / 4L) {
                temp.delete()
                return Result.failure(IllegalStateException("Файл загрузился не полностью"))
            }
            // Аудит п.9: криптографическая и форматная проверка файла.
            // Файл мог скачаться страницей 404/HTML вместо модели.
            val sha = sha256(temp)
            if (!verifyFormat(temp, model)) {
                temp.delete()
                return Result.failure(IllegalStateException(
                    "Скачанный файл не является моделью формата ${model.backend}"))
            }
            // Аудит §13: вычислить SHA ≠ проверить SHA. Если реестр знает
            // доверенный хеш — сверяем. Несовпадение = файл повреждён/подменён.
            val expected = model.expectedSha256
            if (model.backend.equals("litertlm", ignoreCase = true) && expected.isNullOrBlank()) {
                temp.delete()
                return Result.failure(IllegalStateException("Для LiteRT-LM модели отсутствует доверенный SHA-256"))
            }
            val shaVerified = expected.isNullOrBlank() || sha.equals(expected, ignoreCase = true)
            if (!shaVerified) {
                temp.delete()
                return Result.failure(IllegalStateException(
                    "Контрольная сумма файла не совпадает с доверенной. Загрузка отменена. Предыдущая модель сохранена."))
            }

            // Безопасная замена: сначала сохраняем рабочий файл, затем
            // устанавливаем новый. При сбое rename старый файл возвращается.
            if (target.exists() && !target.renameTo(backup)) {
                temp.delete()
                return Result.failure(IllegalStateException(
                    "Не удалось сохранить предыдущую версию модели перед заменой"))
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
            // Повторная установка той же модели не должна плодить дубликаты
            // в реестре установленных моделей.
            installed = installed.filterNot { it.modelId == model.id } + installedModel
            save()
            Log.i(TAG, "Модель ${model.name} установлена")
            Result.success(installedModel)
        } catch (t: Throwable) {
            temp.delete()
            if (!target.exists() && backup.exists()) {
                backup.renameTo(target)
            }
            Log.e(TAG, "Не удалось загрузить модель", t)
            Result.failure(t)
        }
    }

    fun uninstall(modelId: String): Boolean {
        val model = byId(modelId) ?: return false
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

    /**
     * Аудит §14: строгий жизненный цикл модели. Статус вычисляется из фактов.
     *
     * @param runtimeReady загружен ли runtime и готов ли он к работе
     * @param modelLoaded  загружена ли модель в память
     * @param inferenceVerified прошёл ли реальный inference
     */
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

    /**
     * Активная модель — та, которую пользователь выбрал основной.
     * Удалять активную модель запрещено (аудит §15).
     */
    fun isActive(modelId: String, activeId: String?): Boolean = activeId != null && modelId == activeId

    /**
     * Можно ли удалить модель: активную удалять запрещено.
     */
    fun canUninstall(modelId: String, activeId: String?): Boolean =
        !isActive(modelId, activeId)

    /**
     * Фактический benchmark ставит DEVICE VERIFIED только после реального теста (ТЗ §35).
     */
    fun benchmarkStatusFor(modelId: String): String {
        val model = byId(modelId) ?: return SvetlanaStatus.NOT_PROVEN
        return model.benchmark?.let {
            if (it.status == SvetlanaStatus.DEVICE_VERIFIED) SvetlanaStatus.DEVICE_VERIFIED
            else SvetlanaStatus.NOT_PROVEN
        } ?: SvetlanaStatus.NOT_PROVEN
    }

    private fun isSpaceEnough(model: AIModel): Boolean =
        freeSpaceBytes() > model.storageRequirementMb * 1024L * 1024L * 1.1

    /**
     * Расширение целевого файла по формату модели. LiteRT-LM требует
     * именно .litertlm, иначе Engine отклоняет файл.
     */
    private fun extensionFor(model: AIModel): String = when (model.backend) {
        "litertlm" -> "litertlm"
        "llama.cpp" -> "gguf"
        else -> "bin"
    }

    /**
     * Миграция установленных моделей со старого расширения .bin на
     * расширение формата (актуально для litertlm — движок требует
     * именно .litertlm, иначе Engine отклоняет файл). Вызывается при
     * инициализации.
     */
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

    /**
     * Аудит п.9: проверка magic bytes скачанного файла.
     * GGUF (llama.cpp) начинается с "GGUF" (0x47 0x47 0x55 0x46).
     * ONNX — protobuf; первый field ModelProto имеет тег 0x0c (field 1, wire 4)?
     * Надёжнее: ONNX-модели от HF не имеют стабильного magic, поэтому для
     * onnxruntime принимаем любой непустой бинарный файл нетекстового вида.
     */
    private fun verifyFormat(file: File, model: AIModel): Boolean {
        return try {
            when (model.backend) {
                "llama.cpp" -> ModelFormat.GGUF.matchesMagic(readHead(file, 4))
                "litertlm" -> {
                    // Контейнер .litertlm начинается с 8-байтного
                    // magic "LITERTLM" по нулевому смещению.
                    // (Раньше ошибочно проверяли "TFL" по смещению 4 —
                    // это заголовок .tflite, а не LiteRT-LM.)
                    ModelFormat.LITERT_LM.matchesMagic(readHead(file, 8))
                }
                else -> {
                    // No runtime/format validator is implemented for this backend.
                    // Never mark an unsupported format as verified.
                    false
                }
            }
        } catch (t: Throwable) { false }
    }

    /**
     * readNBytes требует API 33; minSdk = 26 — читаем вручную.
     */
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

    companion object { private const val TAG = "LocalModels" }
}

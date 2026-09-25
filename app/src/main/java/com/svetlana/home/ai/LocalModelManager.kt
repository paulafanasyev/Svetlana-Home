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

class LocalModelManager(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val storeFile: File by lazy { File(context.filesDir, "local_models.json") }
    private val modelsDir: File by lazy { File(context.filesDir, "models").apply { mkdirs() } }

    @Volatile
    private var installed: List<InstalledModel> = load()

    fun list(): List<InstalledModel> = installed

    fun isInstalled(modelId: String): Boolean = installed.any { it.modelId == modelId }

    fun byId(modelId: String): InstalledModel? = installed.firstOrNull { it.modelId == modelId }

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
        return try {
            if (!isSpaceEnough(model)) {
                return Result.failure(IllegalStateException("Недостаточно свободного места"))
            }
            Log.i(TAG, "Начинаю загрузку модели ${model.name} (${model.sizeMb}MB) по решению пользователя")
            val target = File(modelsDir, "${model.id}.bin")
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
                    target.outputStream().use { output ->
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
            }
            if (target.length() < model.sizeMb / 4) {
                target.delete()
                return Result.failure(IllegalStateException("Файл загрузился не полностью"))
            }
            val installedModel = InstalledModel(
                modelId = model.id, name = model.name,
                filePath = target.absolutePath, sizeBytes = target.length(),
                installedAt = System.currentTimeMillis()
            )
            installed = installed + installedModel
            save()
            Log.i(TAG, "Модель ${model.name} установлена")
            Result.success(installedModel)
        } catch (t: Throwable) {
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

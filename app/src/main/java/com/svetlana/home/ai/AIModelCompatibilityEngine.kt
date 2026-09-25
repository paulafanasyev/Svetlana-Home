package com.svetlana.home.ai

import com.svetlana.home.device.DeviceCapabilityManager
import com.svetlana.home.core.SvetlanaStatus

/**
 * Уровень совместимости модели с устройством (ТЗ §31).
 */
enum class CompatibilityLevel(val label: String) {
    VERIFIED(SvetlanaStatus.VERIFIED),
    LIKELY_COMPATIBLE("LIKELY COMPATIBLE"),
    NOT_PROVEN("NOT PROVEN"),
    INCOMPATIBLE("INCOMPATIBLE")
}

data class CompatibilityReport(
    val model: AIModel,
    val level: CompatibilityLevel,
    val reasons: List<String>,
    val expectedPerf: String,
    val canRunOnDevice: Boolean,
    val canRunOnServer: Boolean
)

/**
 * AIModelCompatibilityEngine — определяет, какая модель реально подходит
 * устройству, исходя из измеренных параметров.
 */
class AIModelCompatibilityEngine(
    private val device: DeviceCapabilityManager
) {

    fun evaluate(model: AIModel, caps: DeviceCapabilityManager.Capabilities = device.current()): CompatibilityReport {
        val reasons = mutableListOf<String>()
        var score = 0

        // RAM
        if (caps.ramTotalMb >= model.ramRequirementMb * RAM_SAFETY) {
            reasons.add("RAM: ${caps.ramTotalMb}MB ≥ требуемых ${model.ramRequirementMb}MB")
            score += 2
        } else if (caps.ramTotalMb >= model.ramRequirementMb) {
            reasons.add("RAM: достаточно минимально (${caps.ramTotalMb}MB)")
            score += 1
        } else {
            reasons.add("RAM: недостаточно — есть ${caps.ramTotalMb}MB, нужно ${model.ramRequirementMb}MB")
            score -= 3
        }

        // Storage
        if (caps.storageAvailableMb >= model.storageRequirementMb) {
            reasons.add("Storage: свободно ${caps.storageAvailableMb}MB")
            score += 1
        } else {
            reasons.add("Storage: мало места — свободно ${caps.storageAvailableMb}MB, нужно ${model.storageRequirementMb}MB")
            score -= 3
        }

        // CPU / ядра
        if (!model.cpuSupport) {
            reasons.add("Модель не рассчитана на CPU-вывод на телефоне")
            score -= 2
        } else if (caps.cpuCores >= 6) {
            reasons.add("CPU: ${caps.cpuCores} ядер")
            score += 1
        }

        // GPU/NPU
        if (model.gpuSupport && caps.backendSupport.gpu) reasons.add("GPU: поддерживается (${caps.gpu})")
        if (model.npuSupport && caps.backendSupport.npu) reasons.add("NPU/NNAPI: поддерживается")
        if ((model.gpuSupport || model.npuSupport) &&
            !caps.backendSupport.gpu && !caps.backendSupport.npu
        ) {
            reasons.add("Устройство без GPU/NPU ускорения для этой модели")
            score -= 1
        }

        // Android SDK
        if (caps.sdkInt < model.minAndroidSdk) {
            reasons.add("Android SDK ${caps.sdkInt} ниже минимального ${model.minAndroidSdk}")
            score -= 4
        }

        // Thermal / battery
        val thermalBad = caps.thermalStatus in listOf("severe", "critical", "emergency", "shutdown")
        if (thermalBad) {
            reasons.add("Тепловое состояние: ${caps.thermalStatus} — тяжелые модели не рекомендуются")
            score -= 1
        }
        if (caps.batteryPercent in 0..10) {
            reasons.add("Батарея ${caps.batteryPercent}% — тяжёлые модели не рекомендуются")
            score -= 1
        }

        val level = when {
            score >= 4 && caps.ramTotalMb >= model.ramRequirementMb * RAM_SAFETY -> CompatibilityLevel.VERIFIED
            score >= 1 -> CompatibilityLevel.LIKELY_COMPATIBLE
            score >= -1 -> CompatibilityLevel.NOT_PROVEN
            else -> CompatibilityLevel.INCOMPATIBLE
        }

        // VERIFIED на устройстве ставится только после фактического benchmark (ТЗ §35).
        // Здесь — предсказание; фактический статус выставляет BenchmarkRunner.
        val deviceLevel = if (level == CompatibilityLevel.VERIFIED)
            CompatibilityLevel.LIKELY_COMPATIBLE else level

        return CompatibilityReport(
            model = model,
            level = deviceLevel,
            reasons = reasons,
            expectedPerf = expectedPerformance(model, caps),
            canRunOnDevice = deviceLevel != CompatibilityLevel.INCOMPATIBLE,
            canRunOnServer = true
        )
    }

    private fun expectedPerformance(model: AIModel, caps: DeviceCapabilityManager.Capabilities): String {
        if (model.context == 0) return "Назначение: не LLM (STT/TTS/эмбеддинги)"
        val perSec = when {
            model.parameterCountB <= 1.0 -> if (caps.backendSupport.npu) "8–14 ток/с" else "4–8 ток/с"
            model.parameterCountB <= 2.0 -> if (caps.backendSupport.npu) "5–9 ток/с" else "2–5 ток/с"
            model.parameterCountB <= 3.5 -> "1–3 ток/с"
            else -> "менее 1 ток/с — только сервер"
        }
        return "Ожидаемо: $perSec"
    }

    /**
     * Отфильтровать модели, которые теоретически могут работать на устройстве.
     * Используется, чтобы предложить пользователю подходящие варианты (ТЗ §32).
     */
    fun compatibleModels(registry: AIModelRegistry, caps: DeviceCapabilityManager.Capabilities = device.current())
            : List<CompatibilityReport> {
        return registry.llmModels().map { evaluate(it, caps) }
            .filter { it.canRunOnDevice }
            .sortedBy { it.model.sizeMb }
    }

    /**
     * Лучшая модель для текущего состояния устройства.
     */
    fun bestFit(registry: AIModelRegistry, caps: DeviceCapabilityManager.Capabilities = device.current())
            : CompatibilityReport? {
        return compatibleModels(registry, caps).maxByOrNull {
            val lvl = when (it.level) {
                CompatibilityLevel.VERIFIED -> 3
                CompatibilityLevel.LIKELY_COMPATIBLE -> 2
                CompatibilityLevel.NOT_PROVEN -> 1
                CompatibilityLevel.INCOMPATIBLE -> 0
            }
            lvl * 1000 - it.model.sizeMb.toInt()
        }
    }

    companion object {
        // Запас по RAM под систему и другие приложения
        private const val RAM_SAFETY = 1.5
    }
}

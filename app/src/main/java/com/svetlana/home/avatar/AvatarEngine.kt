package com.svetlana.home.avatar

import com.svetlana.home.device.DeviceCapabilityManager
import com.svetlana.home.server.PersonalServerManager

/**
 * Уровни аватара (ТЗ §9).
 */
enum class AvatarLevel(val level: Int, val label: String) {
    L0_LIVING_ORB(0, "Living Orb"),
    L1_LIGHT_AVATAR(1, "Light Avatar"),
    L2_REALISTIC_AVATAR(2, "Realistic Avatar"),
    L3_FULL_REAL_AVATAR(3, "Full Real Avatar");

    companion object {
        fun fromLevel(level: Int): AvatarLevel =
            entries.firstOrNull { it.level == level } ?: L0_LIVING_ORB
    }
}

/**
 * Ресурсы, влияющие на выбор режима аватара.
 */
data class AvatarResources(
    val fps: Int,
    val cpuCores: Int,
    val ramAvailableMb: Int,
    val ramTotalMb: Int,
    val gpu: Boolean,
    val thermal: String,
    val batteryPercent: Int,
    val localInferenceAvailable: Boolean,
    val remoteInferenceAvailable: Boolean,
    val networkAvailable: Boolean,
    val latencyMs: Long
)

/**
 * AvatarEngine — автоматически выбирает режим аватара по реальным ресурсам (ТЗ §9).
 *
 * Если Real Avatar работает плохо: Real → Light → Living Orb.
 */
class AvatarEngine(
    private val device: DeviceCapabilityManager,
    private val serverManager: PersonalServerManager
) {

    /**
     * Текущий измеренный FPS (обновляется из UI).
     */
    @Volatile
    var measuredFps: Int = 60
        private set

    @Volatile
    var currentLevel: AvatarLevel = AvatarLevel.L0_LIVING_ORB
        private set

    fun updateFps(fps: Int) {
        measuredFps = fps
    }

    /**
     * Результат выбора аватара: разделяет желаемый уровень, реальную
     * доступность renderer'а и итоговый выбранный уровень.
     * Это защищает от ложного заявления «доступен Real Avatar»,
     * когда renderer'а в сборке нет (аудит п.24).
     */
    data class AvatarDecision(
        val requestedLevel: AvatarLevel,
        val rendererAvailable: Boolean,
        val selectedLevel: AvatarLevel,
        val reason: String
    )

    /**
     * Измерить ресурсы и выбрать уровень.
     * @return детальное решение: желаемый уровень, доступность renderer'а,
     *         итоговый выбранный уровень и причина.
     */
    fun evaluate(override: Int = -1): AvatarLevel {
        currentLevel = decide(override).selectedLevel
        return currentLevel
    }

    /**
     * Полная версия evaluate: возвращает, что было запрошено и что реально выбрано.
     * UI показывает пользователю честную картину возможностей.
     */
    fun decide(override: Int = -1): AvatarDecision {
        if (override >= 0) {
            // Делегируем в чистую логику деградации — она же покрыта тестами.
            val fallback = AvatarFallback.decide(AvatarLevel.fromLevel(override))
            val decision = AvatarDecision(
                requestedLevel = fallback.requestedLevel,
                rendererAvailable = fallback.rendererAvailable,
                selectedLevel = fallback.selectedLevel,
                reason = if (fallback.selectedLevel == fallback.requestedLevel)
                    "явный выбор пользователя" else fallback.reason
            )
            currentLevel = decision.selectedLevel
            return decision
        }
        val caps = device.current()
        val remote = serverManager.isReachable()
        val resources = AvatarResources(
            fps = measuredFps,
            cpuCores = caps.cpuCores,
            ramAvailableMb = caps.ramAvailableMb,
            ramTotalMb = caps.ramTotalMb,
            gpu = caps.backendSupport.gpu,
            thermal = caps.thermalStatus,
            batteryPercent = caps.batteryPercent,
            localInferenceAvailable = false, // локальные VLM пока не входят в базовую сборку
            remoteInferenceAvailable = remote,
            networkAvailable = caps.networkAvailable,
            latencyMs = 0
        )
        val requested = chooseLevel(resources)
        // Главная защита: нельзя выбрать уровень, renderer для которого
        // не зарегистрирован как доступный.
        val selected = AvatarRendererRegistry.highestAvailableAtOrBelow(requested)
        val decision = AvatarDecision(
            requestedLevel = requested,
            rendererAvailable = AvatarRendererRegistry.isAvailable(requested),
            selectedLevel = selected,
            reason = reasonText(requested, selected, resources)
        )
        currentLevel = selected
        return decision
    }

    private fun reasonText(requested: AvatarLevel, selected: AvatarLevel, r: AvatarResources): String = buildString {
        append("RAM ${r.ramTotalMb}MB, ядер ${r.cpuCores}, FPS=${r.fps} → запрошен ${requested.label}")
        if (selected != requested) {
            append("; renderer для ${requested.label} недоступен → выбран ${selected.label}")
        }
    }

    private fun chooseLevel(r: AvatarResources): AvatarLevel {
        val thermalBad = r.thermal in listOf("severe", "critical", "emergency", "shutdown")
        val lowBattery = r.batteryPercent in 0..10
        val strongDevice = r.ramTotalMb >= 6144 && r.cpuCores >= 6 && r.gpu

        // Полный реальный аватар: мощное устройство ИЛИ мощный удалённый compute
        if (!thermalBad && !lowBattery && r.fps >= 24) {
            if (strongDevice || (r.remoteInferenceAvailable && r.networkAvailable)) {
                return AvatarLevel.L3_FULL_REAL_AVATAR
            }
        }

        // Реалистичный аватар: хорошее устройство
        if (!thermalBad && r.ramTotalMb >= 4096 && r.cpuCores >= 4 && r.fps >= 20) {
            return AvatarLevel.L2_REALISTIC_AVATAR
        }

        // Лёгкий аватар: среднее устройство
        if (r.ramTotalMb >= 2048 && r.cpuCores >= 2 && !thermalBad) {
            return AvatarLevel.L1_LIGHT_AVATAR
        }

        // Базовый режим — работает везде
        return AvatarLevel.L0_LIVING_ORB
    }

    /**
     * Деградация при просадке FPS (ТЗ §9).
     */
    fun degrade(): AvatarLevel {
        val next = AvatarLevel.fromLevel((currentLevel.level - 1).coerceAtLeast(0))
        currentLevel = next
        return next
    }

    /**
     * Описание причины выбора для UI.
     */
    fun reason(level: AvatarLevel): String {
        val caps = device.current()
        return buildString {
            append("RAM ${caps.ramTotalMb}MB, ядер ${caps.cpuCores}, GPU=${caps.backendSupport.gpu}, ")
            append("thermal=${caps.thermalStatus}, батарея ${caps.batteryPercent}%, ")
            append("FPS=$measuredFps, ")
            append("сервер=${if (serverManager.isReachable()) "доступен" else "недоступен"}")
            append(" → уровень ${level.level}")
        }
    }
}

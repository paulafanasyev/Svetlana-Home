package com.svetlana.home.avatar

/**
 * AvatarFallback — чистая логика деградации аватара (ТЗ §9, аудит п.24).
 *
 * Не зависит от Android — только от реестра renderer'ов. Используется
 * и AvatarEngine'ом, и unit-тестами: единственный источник правды для
 * правила «нельзя выбрать уровень, renderer для которого не
 * зарегистрирован».
 *
 * Цепочка: Real Avatar → Light Avatar → Living Orb.
 */
object AvatarFallback {

    data class Decision(
        val requestedLevel: AvatarLevel,
        val rendererAvailable: Boolean,
        val selectedLevel: AvatarLevel,
        val reason: String
    )

    /**
     * Запросить уровень [requested]. Если renderer для него не
     * зарегистрирован — выбирается наивысший доступный уровень ниже.
     */
    fun decide(requested: AvatarLevel): Decision {
        val available = AvatarRendererRegistry.isAvailable(requested)
        val selected = AvatarRendererRegistry.highestAvailableAtOrBelow(requested)
        return Decision(
            requestedLevel = requested,
            rendererAvailable = available,
            selectedLevel = selected,
            reason = if (selected == requested) {
                "уровень доступен"
            } else {
                "renderer для ${requested.label} недоступен → выбран ${selected.label}"
            }
        )
    }
}

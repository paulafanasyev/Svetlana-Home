package com.svetlana.home.avatar

/**
 * AvatarRendererRegistry — какие режимы аватара РЕАЛЬНО доступны.
 *
 * Проблема, которую решает этот класс: AvatarEngine мог выбрать
 * L3_FULL_REAL_AVATAR, хотя никакого реалистичного renderer'а в сборке нет.
 * Это ложное заявление о возможности (false capability claim).
 *
 * Правило: уровень можно выбрать только если соответствующий renderer
 * действительно зарегистрирован как доступный.
 *
 * L0 (Living Orb) — это всегда Canvas/Compose, поэтому доступен всегда.
 * Остальные режимы появляются только после явной регистрации движка.
 */
object AvatarRendererRegistry {

    private val available: MutableSet<AvatarLevel> = mutableSetOf(AvatarLevel.L0_LIVING_ORB)

    /** Регистрация реального renderer'а. Вызывается модулем, который его предоставляет. */
    fun register(level: AvatarLevel) {
        synchronized(available) { available.add(level) }
    }

    /** Отмена регистрации (например, renderer выгружен). */
    fun unregister(level: AvatarLevel) {
        if (level == AvatarLevel.L0_LIVING_ORB) return // Orb убрать нельзя
        synchronized(available) { available.remove(level) }
    }

    fun isAvailable(level: AvatarLevel): Boolean = synchronized(available) { level in available }

    /** Все доступные уровни. */
    fun availableLevels(): Set<AvatarLevel> = synchronized(available) { available.toSet() }

    /**
     * Наивысший доступный уровень, не превосходящий [target].
     * Используется для fallback Real → Light → Orb.
     */
    fun highestAvailableAtOrBelow(target: AvatarLevel): AvatarLevel {
        for (level in target.level downTo 0) {
            val candidate = AvatarLevel.fromLevel(level)
            if (isAvailable(candidate)) return candidate
        }
        return AvatarLevel.L0_LIVING_ORB
    }

    fun reset() {
        synchronized(available) {
            available.clear()
            available.add(AvatarLevel.L0_LIVING_ORB)
        }
    }
}

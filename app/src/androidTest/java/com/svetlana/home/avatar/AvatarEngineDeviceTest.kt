package com.svetlana.home.avatar

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.svetlana.home.SvetlanaDeviceTest
import com.svetlana.home.core.ServiceLocator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Avatar Engine на устройстве (ТЗ §7, §9; аудит п.24).
 *
 * Ключевая проверка: нельзя выбрать уровень аватара, renderer для которого
 * не зарегистрирован. Это защищает от ложного заявления «доступен Real Avatar».
 */
@RunWith(AndroidJUnit4::class)
class AvatarEngineDeviceTest : SvetlanaDeviceTest() {

    @Test
    fun orbIsAlwaysAvailable() {
        assertTrue("Living Orb доступен всегда",
            AvatarRendererRegistry.isAvailable(AvatarLevel.L0_LIVING_ORB))
    }

    @Test
    fun selectionNeverExceedsAvailableRenderer() {
        // Запросим самый высокий уровень — движок должен выбрать ближайший
        // доступный, а не несуществующий L3.
        val engine = ServiceLocator.avatarEngine
        val decision = engine.decide(override = AvatarLevel.L3_FULL_REAL_AVATAR.level)

        println("REQUESTED=${decision.requestedLevel} AVAILABLE=${decision.rendererAvailable} SELECTED=${decision.selectedLevel}")
        assertTrue("Выбранный уровень должен быть доступным",
            AvatarRendererRegistry.isAvailable(decision.selectedLevel))
        assertTrue("Выбранный уровень не выше запрошенного",
            decision.selectedLevel.level <= decision.requestedLevel.level)

        // Если L3 renderer не зарегистрирован, выбран должен быть не L3.
        if (!AvatarRendererRegistry.isAvailable(AvatarLevel.L3_FULL_REAL_AVATAR)) {
            assertFalse("L3 не должен выбираться без renderer'а",
                decision.selectedLevel == AvatarLevel.L3_FULL_REAL_AVATAR)
        }
    }

    @Test
    fun resourceBasedEvaluationSelectsAvailableLevel() {
        val engine = ServiceLocator.avatarEngine
        engine.updateFps(60)
        val decision = engine.decide()

        println("AUTO_DECISION=${decision.selectedLevel} reason=${decision.reason}")
        assertTrue("Автовыбор всегда доступный уровень",
            AvatarRendererRegistry.isAvailable(decision.selectedLevel))
    }

    @Test
    fun degradesToAvailableLevel() {
        val engine = ServiceLocator.avatarEngine
        engine.updateFps(5) // очень низкий FPS — должна быть деградация
        engine.evaluate()
        val degraded = engine.degrade()

        println("DEGRADED_TO=$degraded")
        assertTrue("Деградация не ниже Orb",
            degraded.level >= AvatarLevel.L0_LIVING_ORB.level)
    }
}

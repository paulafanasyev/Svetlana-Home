package com.svetlana.home.control

import com.google.common.truth.Truth.assertWithMessage
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Опасные действия: доказываем, что классификация DANGEROUS действительно
 * БЛОКИРУЕТ выполнение, а не только маркирует его (аудит п.27, ТЗ §58).
 *
 * «Света, отправь SMS Ивану» не должна превратиться в реальную отправку
 * только потому, что riskOf() вернул DANGEROUS. ActionRouter.execute()
 * содержит условие `riskOf(action) == DANGEROUS && !confirmed` — действие
 * возвращается с requiresUserConfirmation=true и НЕ выполняется.
 *
 * Тест чистой JVM: ActionRiskPolicy не зависит от Context.
 */
class DangerousActionBlockTest {

    @Test
    fun sendMessage_isDangerous() {
        val risk = ActionRiskPolicy.riskOf(SvetlanaAction.SendMessage("Иван", "привет"))
        assertThat(risk).isEqualTo(ActionRisk.DANGEROUS)
        assertThat(ActionRiskPolicy.requiresConfirmation(SvetlanaAction.SendMessage("Иван", "привет"))).isTrue()
    }

    @Test
    fun makeCall_isDangerous() {
        val risk = ActionRiskPolicy.riskOf(SvetlanaAction.MakeCall("Иван"))
        assertThat(risk).isEqualTo(ActionRisk.DANGEROUS)
    }

    @Test
    fun share_isModerate() {
        val risk = ActionRiskPolicy.riskOf(SvetlanaAction.Share("текст"))
        assertThat(risk).isEqualTo(ActionRisk.MODERATE)
        // MODERATE не блокируется жёстко, но и не считается безопасным
        assertThat(ActionRiskPolicy.requiresConfirmation(SvetlanaAction.Share("текст"))).isFalse()
    }

    @Test
    fun openApp_isSafe() {
        val risk = ActionRiskPolicy.riskOf(SvetlanaAction.OpenApp("Telegram"))
        assertThat(risk).isEqualTo(ActionRisk.SAFE)
    }

    @Test
    fun handsActions_areSafe() {
        // Управление UI (клики, свайпы, ввод) — безопасно, выполняется в
        // рамках Accessibility-разрешения и не имеет необратимых последствий.
        val actions = listOf(
            SvetlanaAction.Click("Telegram", "кнопка"),
            SvetlanaAction.Swipe("Telegram", "вверх"),
            SvetlanaAction.Scroll("Telegram", "вниз"),
            SvetlanaAction.TypeText("Telegram", "поле", "текст"),
            SvetlanaAction.TakeScreenshot("Telegram"),
            SvetlanaAction.PressBack,
            SvetlanaAction.PressHome
        )
        actions.forEach { a ->
            assertWithMessage("Действие ${a::class.simpleName} должно быть SAFE")
                .that(ActionRiskPolicy.riskOf(a)).isEqualTo(ActionRisk.SAFE)
        }
    }

    @Test
    fun translate_isSafe() {
        val risk = ActionRiskPolicy.riskOf(SvetlanaAction.Translate("привет", "ru_to_vi"))
        assertThat(risk).isEqualTo(ActionRisk.SAFE)
    }
}

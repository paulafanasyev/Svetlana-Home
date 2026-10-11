package com.svetlana.home.control

import com.google.common.truth.Truth.assertWithMessage
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Опасные действия: доказываем, что классификация действительно
 * БЛОКИРУЕТ выполнение без подтверждения, а не только маркирует его (аудит п.27, ТЗ §58).
 *
 * ActionRouter.execute() содержит условие `requiresConfirmation(action) && !confirmed` —
 * действие возвращается с requiresUserConfirmation=true и НЕ выполняется.
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
        // ТЗ §58: публикации/шеринг требуют подтверждения пользователя
        assertThat(ActionRiskPolicy.requiresConfirmation(SvetlanaAction.Share("текст"))).isTrue()
    }

    @Test
    fun openApp_isSafe() {
        val risk = ActionRiskPolicy.riskOf(SvetlanaAction.OpenApp("Telegram"))
        assertThat(risk).isEqualTo(ActionRisk.SAFE)
    }

    @Test
    fun navigation_isSafe() {
        // Навигация видна и обратима: без подтверждения.
        val actions = listOf(
            SvetlanaAction.Swipe("Telegram", "вверх"),
            SvetlanaAction.Scroll("Telegram", "вниз"),
            SvetlanaAction.PressBack,
            SvetlanaAction.PressHome,
            SvetlanaAction.OpenRecents
        )
        actions.forEach { a ->
            assertWithMessage("Действие ${a::class.simpleName} должно быть SAFE")
                .that(ActionRiskPolicy.riskOf(a)).isEqualTo(ActionRisk.SAFE)
        }
    }

    @Test
    fun clicksTypingAndScreenshots_requireConfirmation() {
        // Принцип владельца: чужой голос не должен нажимать «Оплатить», вводить текст в банк
        // или снимать экран с перепиской. Пока нет проверки голоса — только с подтверждением.
        val actions = listOf(
            SvetlanaAction.Click("Telegram", "кнопка"),
            SvetlanaAction.LongClick("Telegram", "сообщение"),
            SvetlanaAction.TypeText("Telegram", "поле", "текст"),
            SvetlanaAction.ClearText("Telegram", "поле"),
            SvetlanaAction.TakeScreenshot("Telegram")
        )
        actions.forEach { a ->
            assertWithMessage("Действие ${a::class.simpleName} должно требовать подтверждения")
                .that(ActionRiskPolicy.requiresConfirmation(a)).isTrue()
        }
    }

    @Test
    fun composeMessage_isDangerous_andRequiresConfirmation() {
        val action = SvetlanaAction.ComposeMessage("whatsapp", "серый", "привет")
        assertWithMessage("ComposeMessage должна быть DANGEROUS")
            .that(ActionRiskPolicy.riskOf(action)).isEqualTo(ActionRisk.DANGEROUS)
        assertThat(ActionRiskPolicy.requiresConfirmation(action)).isTrue()
    }

    @Test
    fun compound_riskEqualsMostDangerousStep() {
        val safe = SvetlanaAction.Compound(listOf(
            SvetlanaAction.OpenApp("whatsapp"),
            SvetlanaAction.Scroll("whatsapp", "вниз")
        ))
        assertThat(ActionRiskPolicy.riskOf(safe)).isEqualTo(ActionRisk.SAFE)

        val withClick = SvetlanaAction.Compound(listOf(
            SvetlanaAction.OpenApp("whatsapp"),
            SvetlanaAction.Click("whatsapp", "поиск")
        ))
        assertThat(ActionRiskPolicy.requiresConfirmation(withClick)).isTrue()

        val dangerous = SvetlanaAction.Compound(listOf(
            SvetlanaAction.OpenApp("whatsapp"),
            SvetlanaAction.ComposeMessage("whatsapp", "серый", "привет")
        ))
        assertThat(ActionRiskPolicy.riskOf(dangerous)).isEqualTo(ActionRisk.DANGEROUS)
        assertThat(ActionRiskPolicy.requiresConfirmation(dangerous)).isTrue()
    }

    @Test
    fun compound_requiresAtLeastOneStep() {
        assertThrows(IllegalArgumentException::class.java) {
            SvetlanaAction.Compound(emptyList())
        }
    }
}

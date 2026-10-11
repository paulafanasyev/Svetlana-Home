package com.svetlana.home.control

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class TargetAwareRiskTest {

    @Test
    fun irreversibleButtonsRequireConfirmation() {
        listOf(
            "Удалить", "Удалить чат", "Оплатить 990 ₽", "Купить", "Отправить", "Перевести",
            "Установить", "Подтвердить", "Сбросить настройки", "Delete", "Pay", "Buy now", "Send",
            "Uninstall", "Confirm", "OK", "Разрешить", "Позвонить", "Place order",
        ).forEach { label ->
            assertWithMessage("Click «$label»")
                .that(ActionRiskPolicy.requiresConfirmation(SvetlanaAction.Click("app", label))).isTrue()
            assertWithMessage("LongClick «$label»")
                .that(ActionRiskPolicy.riskOf(SvetlanaAction.LongClick("app", label))).isEqualTo(ActionRisk.DANGEROUS)
        }
    }

    @Test
    fun ordinaryNavigationStaysSafe() {
        listOf("кнопка", "поиск", "Настройки", "Назад", "Чаты", "Display", "PayPal", "Профиль", "search", "Telegram")
            .forEach { label ->
                assertWithMessage("Click «$label»")
                    .that(ActionRiskPolicy.riskOf(SvetlanaAction.Click("app", label))).isEqualTo(ActionRisk.SAFE)
            }
    }

    @Test
    fun typingIntoSecretFieldsRequiresConfirmation() {
        assertThat(ActionRiskPolicy.riskOf(SvetlanaAction.TypeText("bank", "Пароль", "x"))).isEqualTo(ActionRisk.DANGEROUS)
        assertThat(ActionRiskPolicy.riskOf(SvetlanaAction.TypeText("bank", "PIN", "1234"))).isEqualTo(ActionRisk.DANGEROUS)
        assertThat(ActionRiskPolicy.riskOf(SvetlanaAction.TypeText("bank", "Код из СМС", "1234"))).isEqualTo(ActionRisk.DANGEROUS)
        assertThat(ActionRiskPolicy.riskOf(SvetlanaAction.TypeText("shop", "поле", "4111 1111 1111 1111"))).isEqualTo(ActionRisk.DANGEROUS)
        assertThat(ActionRiskPolicy.riskOf(SvetlanaAction.TypeText("chat", "Сообщение", "Привет"))).isEqualTo(ActionRisk.SAFE)
        assertThat(ActionRiskPolicy.riskOf(SvetlanaAction.TypeText("maps", "Поиск", "Пинск"))).isEqualTo(ActionRisk.SAFE)
    }

    @Test
    fun dangerousClickInsideChainMakesTheChainDangerous() {
        val chain = SvetlanaAction.Compound(listOf(
            SvetlanaAction.OpenApp("files"),
            SvetlanaAction.Click("files", "Удалить всё"),
        ))
        assertThat(ActionRiskPolicy.requiresConfirmation(chain)).isTrue()
    }
}

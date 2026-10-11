package com.svetlana.home.control

import com.google.common.truth.Truth.assertWithMessage
import java.lang.reflect.Modifier
import org.junit.Test

/**
 * Опасные методы движка нельзя вызвать в обход ActionRouter: каждый требует [ActionGrant],
 * а единственная реализация — приватный объект в ActionRouter.kt.
 */
class ActionGrantTest {
    private val gated = setOf(
        "click", "longClick", "typeText", "clearText", "scroll", "swipe", "readScreen",
        "takeScreenshot", "takeScreenshotAction", "pressBack", "pressHome", "openRecents",
        "composeMessage", "makeCall", "sendMessage", "share",
    )

    @Test
    fun dangerousEngineMethodsRequireRouterGrant() {
        val methods = AppControlEngine::class.java.declaredMethods
            .filter { it.name in gated && Modifier.isPublic(it.modifiers) }
        assertWithMessage("все опасные методы на месте").that(methods.map { it.name }.toSet()).isEqualTo(gated)
        methods.forEach { m ->
            assertWithMessage("${m.name} должен требовать ActionGrant")
                .that(m.parameterTypes.firstOrNull()).isEqualTo(ActionGrant::class.java)
        }
    }

    @Test
    fun grantIsSealedAndCannotBeImplementedOutside() {
        assertWithMessage("ActionGrant — интерфейс").that(ActionGrant::class.java.isInterface).isTrue()
        assertWithMessage("ActionGrant — sealed").that(ActionGrant::class.isSealed).isTrue()
    }
}

package com.svetlana.home.cabinet

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertThrows
import org.junit.Test

class CabinetProtocolTest {
    private val token = "A".repeat(43)

    @Test
    fun normalizesCodeAndRejectsBadOnes() {
        assertThat(CabinetProtocol.normalizeCode(" abcd efgh-jkmn ")).isEqualTo("ABCD-EFGH-JKMN")
        assertThrows(IllegalArgumentException::class.java) { CabinetProtocol.normalizeCode("ABCD-EFGH") }
        assertThrows(IllegalArgumentException::class.java) { CabinetProtocol.normalizeCode("ABCD-EFGH-IJK0") }
    }

    @Test
    fun onlySiteOrigins() {
        assertThat(CabinetProtocol.checkOrigin(CabinetProtocol.ORIGINS[0])).isEqualTo(CabinetProtocol.ORIGINS[0])
        assertThrows(IllegalArgumentException::class.java) { CabinetProtocol.checkOrigin("https://evil.example") }
    }

    @Test
    fun linkBodyIsWhitelistedAndNameIsSanitized() {
        val o = Json.parseToJsonElement(CabinetProtocol.linkBody("abcdefghjkmn", "Мой\n\u202Eтелефон")).jsonObject
        assertThat(o.keys).containsExactly("code", "platform", "name", "app_version")
        assertThat(o["platform"]!!.jsonPrimitive.content).isEqualTo("android")
        assertThat(o["name"]!!.jsonPrimitive.content).isEqualTo("Мойтелефон")
    }

    @Test
    fun heartbeatSendsOnlyFlagsAndCatalogModels() {
        val status = CabinetProtocol.Status(true, false, true, true, false)
        val body = CabinetProtocol.heartbeatBody(status, listOf(
            CabinetProtocol.Model("qwen3-0.6b-litert", 586),
            CabinetProtocol.Model("my-private-model"),
        ))
        val o = Json.parseToJsonElement(body).jsonObject
        assertThat(o.keys).containsExactly("app_version", "status", "models")
        assertThat(o["status"]!!.jsonObject.keys)
            .containsExactly("bridge_v2", "voice_enrolled", "secrets_encrypted", "confirm_gate", "paired_with")
        assertThat(o["models"]!!.jsonArray).hasSize(1)
        assertThat(body).doesNotContain("my-private-model")
    }

    @Test
    fun linkReplyMustCarryWellFormedToken() {
        val ok = CabinetProtocol.parseLinkReply(200, """{"device_id":"d1","device_token":"$token"}""")
        assertThat(ok.token).isEqualTo(token)
        assertThrows(IllegalStateException::class.java) {
            CabinetProtocol.parseLinkReply(200, """{"device_id":"d1","device_token":"short"}""")
        }
        val e = assertThrows(IllegalStateException::class.java) {
            CabinetProtocol.parseLinkReply(401, """{"message":"Код неверный"}""")
        }
        assertThat(e.message).isEqualTo("Код неверный")
        assertThrows(IllegalStateException::class.java) { CabinetProtocol.parseLinkReply(200, "<html>") }
    }
}

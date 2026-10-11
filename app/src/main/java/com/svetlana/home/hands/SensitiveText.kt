package com.svetlana.home.hands

/**
 * Redaction of secrets in what Hands reads from other apps' screens.
 *
 * Svetlana never needs the contents of password, PIN, one-time-code or card
 * fields, and that text must never reach the model, logs or history. Pure
 * function, covered by JVM unit tests.
 */
object SensitiveText {
    const val MASK = "••••"

    private val SENSITIVE_MARKERS = listOf(
        "password", "passwd", "passcode", "pin_code", "pincode", "otp", "one_time", "onetime",
        "verification_code", "sms_code", "auth_code", "2fa", "totp", "cvv", "cvc", "card_number",
        "cardnumber", "security_code", "secret",
        "пароль", "пин-код", "пин код", "код из смс", "код подтверждения", "одноразов", "cvv-код",
        "номер карты", "секретн",
    )

    /** Card-like digit runs (13–19 digits, optional spaces/dashes) that pass the Luhn check. */
    private val CARD_RE = Regex("(?<![0-9])(?:[0-9][ -]?){12,18}[0-9](?![0-9])")

    /** True if the node is a password field or is labelled as a PIN / OTP / card field. */
    fun isSensitiveField(isPassword: Boolean, id: String, contentDescription: String, hint: String = ""): Boolean {
        if (isPassword) return true
        val label = "$id $contentDescription $hint".lowercase()
        return SENSITIVE_MARKERS.any { label.contains(it) }
    }

    /** Text safe to expose: masked for sensitive fields, card numbers masked elsewhere. */
    fun redact(text: String, isPassword: Boolean, id: String, contentDescription: String, hint: String = ""): String {
        if (text.isEmpty()) return text
        if (isSensitiveField(isPassword, id, contentDescription, hint)) return MASK
        return maskCardNumbers(text)
    }

    fun maskCardNumbers(text: String): String = CARD_RE.replace(text) { m ->
        val digits = m.value.filter { it.isDigit() }
        if (digits.length in 13..19 && luhn(digits)) "$MASK ${digits.takeLast(4)}" else m.value
    }

    private fun luhn(digits: String): Boolean {
        var sum = 0
        var double = false
        for (i in digits.indices.reversed()) {
            var d = digits[i] - '0'
            if (double) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
            double = !double
        }
        return sum % 10 == 0
    }
}

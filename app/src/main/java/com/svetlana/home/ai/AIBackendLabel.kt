package com.svetlana.home.ai

import android.content.Context
import com.svetlana.home.core.Tr

/**
 * «Какой ИИ сейчас отвечает» на языке интерфейса. Сам [AIBackend.humanReadable] остаётся
 * русским (история, тесты), перевод — только при показе.
 */
fun AIBackend.label(ctx: Context): String {
    if (!Tr.isEnglish(ctx)) return humanReadable
    return when (this) {
        AIBackend.LOCAL -> "Right now I'm using the local model on this device."
        AIBackend.PERSONAL_SERVER -> "Right now I'm using your remote server."
        AIBackend.EXTERNAL -> "Right now I'm using an external AI provider."
        AIBackend.HYBRID -> "Right now I'm using hybrid mode (local + remote)."
        AIBackend.NONE -> "No AI is selected right now."
    }
}

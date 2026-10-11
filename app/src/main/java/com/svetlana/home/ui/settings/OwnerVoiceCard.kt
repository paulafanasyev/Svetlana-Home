package com.svetlana.home.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.svetlana.home.core.ServiceLocator
import com.svetlana.home.memory.HistoryCategory
import com.svetlana.home.owner.BiometricAuth
import com.svetlana.home.ui.components.GlassCard
import com.svetlana.home.voice.OwnerVoiceStore
import com.svetlana.home.voice.PcmCapture
import com.svetlana.home.voice.VoicePrint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ENROLL_PHRASES = listOf(
    "Светлана, это я",
    "Сегодня хороший день, чтобы сделать всё задуманное",
    "Света, позвони мне завтра в девять утра",
    "Один, два, три, четыре, пять, шесть, семь",
    "Покажи мои доходы и налог за этот месяц",
)
private const val RECORD_MS = 3_500

/**
 * Запись голоса владельца. Записать, перезаписать или удалить голос можно только после
 * системной проверки владельца (биометрия или PIN устройства): чужой человек с разблокированным
 * телефоном не подменит свой голос. То же правило дублирует OwnerVoiceStore. Аудио не
 * сохраняется, только числа шаблона. Keystore читается не на main thread.
 */
@Composable
fun OwnerVoiceCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enrolled by remember { mutableStateOf(OwnerVoiceStore.isEnrolledCached()) }
    var busy by remember { mutableStateOf(false) }
    var step by remember { mutableStateOf(-1) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        enrolled = withContext(Dispatchers.IO) { OwnerVoiceStore.isEnrolled(context) }
    }

    fun withOwner(title: String, action: () -> Unit) {
        val activity = context as? FragmentActivity
        if (activity == null) {
            message = "Недоступно в этом контексте"
            return
        }
        BiometricAuth(context).authenticate(activity, title) { ok, reason ->
            if (ok) action() else message = reason ?: "Подтверждение отменено"
        }
    }

    fun enroll() {
        if (!PcmCapture.hasPermission(context)) {
            message = "Нужно разрешение на микрофон (кнопка «Изменить» в разделе «Разрешения»)."
            return
        }
        busy = true
        message = null
        scope.launch {
            val prints = ArrayList<VoicePrint.Print>()
            var attempts = 0
            while (prints.size < ENROLL_PHRASES.size && attempts < ENROLL_PHRASES.size + 4) {
                step = prints.size
                attempts++
                val pcm = PcmCapture.record(context, RECORD_MS)
                val p = pcm?.let { audio ->
                    withContext(Dispatchers.Default) {
                        VoicePrint.compute(audio, PcmCapture.SAMPLE_RATE).also { audio.fill(0f) }
                    }
                }
                if (p == null) {
                    message = "Не расслышала. Повторите чуть громче."
                } else {
                    prints.add(p)
                    message = null
                }
            }
            step = -1
            if (prints.size < ENROLL_PHRASES.size) {
                message = "Не получилось записать голос. Попробуйте в тихом месте."
                busy = false
                return@launch
            }
            val template = VoicePrint.buildTemplate(prints)
            val saved = withContext(Dispatchers.IO) { OwnerVoiceStore.save(context, template) }
            enrolled = withContext(Dispatchers.IO) { OwnerVoiceStore.isEnrolled(context) }
            message = if (saved) "Голос записан. Теперь голосовые команды выполняются только от вас."
            else "Не удалось сохранить голос: подтвердите владельца ещё раз и повторите."
            ServiceLocator.historyManager.record(
                HistoryCategory.CONFIRMATIONS,
                if (saved) "Записан голос владельца (${prints.size} записей)" else "Ошибка сохранения голоса владельца"
            )
            busy = false
        }
    }

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Text("Голос владельца", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (enrolled) "Записан. Голосовые команды выполняются только от вашего голоса."
                else "Не записан. Пока голос не записан, голосовые команды не выполняются.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Аудио не сохраняется и никуда не отправляется: на телефоне хранится только " +
                    "зашифрованный числовой шаблон. Опасные действия всё равно подтверждаются на экране.",
                style = MaterialTheme.typography.bodySmall
            )
            if (step >= 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Запись ${step + 1} из ${ENROLL_PHRASES.size}. Скажите: «${ENROLL_PHRASES[step]}»",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            message?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(10.dp))
            Button(
                enabled = !busy,
                onClick = { withOwner(if (enrolled) "Перезаписать голос владельца" else "Записать голос владельца") { enroll() } },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text(
                    if (busy) "Идёт запись…" else if (enrolled) "Перезаписать голос" else "Записать голос",
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
            if (enrolled && !busy) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        withOwner("Удалить голос владельца") {
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { OwnerVoiceStore.delete(context) }
                                if (ok) {
                                    enrolled = false
                                    message = "Голос удалён. Голосовые команды отключены до новой записи."
                                    ServiceLocator.historyManager.record(HistoryCategory.CONFIRMATIONS, "Удалён голос владельца")
                                } else {
                                    message = "Не удалось удалить голос. Попробуйте ещё раз."
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("Удалить голос") }
            }
        }
    }
}

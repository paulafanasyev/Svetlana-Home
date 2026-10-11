package com.svetlana.home.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.svetlana.home.cabinet.CabinetClient
import com.svetlana.home.cabinet.CabinetProtocol
import com.svetlana.home.ui.components.GlassCard
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Настройки → Личный кабинет: привязка по коду с сайта и состояние защиты. */
@Composable
fun CabinetScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(CabinetClient.state(context)) }
    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(Build.MODEL ?: "Телефон") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val status = remember { CabinetClient.collectStatus(context) }

    LaunchedEffect(Unit) {
        CabinetClient.heartbeat(context)
        state = CabinetClient.state(context)
    }

    fun fmt(t: Long) = if (t > 0) DateFormat.getDateTimeInstance().format(Date(t)) else "—"

    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.verticalScroll(rememberScrollState()),
    ) {
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Личный кабинет «Мир Самозанятых»", style = MaterialTheme.typography.titleMedium)
                if (state.linked) {
                    Text("Телефон привязан ${fmt(state.linkedAt)}. Последняя отправка: ${fmt(state.lastOk)}" +
                        (state.lastError?.let { " (ошибка: $it)" } ?: ""), style = MaterialTheme.typography.bodySmall)
                    Text("В кабинет уходят только версия, флаги защиты и названия моделей. Контакты, деньги, голос и переписка остаются на телефоне.",
                        style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !busy, onClick = {
                            busy = true
                            scope.launch {
                                val r = CabinetClient.heartbeat(context, force = true)
                                message = when (r) {
                                    CabinetClient.Beat.OK -> "Состояние отправлено в кабинет."
                                    CabinetClient.Beat.UNLINKED -> "Устройство отключено в кабинете."
                                    CabinetClient.Beat.NOT_LINKED -> "Телефон ещё не привязан."
                                    else -> "Нет связи с сайтом, попробую позже."
                                }
                                state = CabinetClient.state(context)
                                busy = false
                            }
                        }) { Text("Обновить") }
                        OutlinedButton(onClick = {
                            CabinetClient.unlinkLocal(context)
                            state = CabinetClient.state(context)
                            message = "Привязка удалена. Отключите устройство и в кабинете."
                        }) { Text("Отвязать") }
                    }
                } else {
                    Text("На сайте откройте «Мои устройства» → «Получить код» и введите его здесь. Код действует 10 минут. Никому не сообщайте код.",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = code, onValueChange = { code = it.take(16) }, singleLine = true,
                        label = { Text("XXXX-XXXX-XXXX") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = name, onValueChange = { name = it.take(80) }, singleLine = true,
                        label = { Text("Название телефона") }, modifier = Modifier.fillMaxWidth())
                    Button(enabled = !busy && code.isNotBlank(), onClick = {
                        busy = true
                        message = null
                        scope.launch {
                            message = try {
                                CabinetProtocol.normalizeCode(code)
                                CabinetClient.link(context, code, name)
                                CabinetClient.heartbeat(context, force = true)
                                code = ""
                                "Телефон привязан к личному кабинету."
                            } catch (e: Exception) {
                                e.message ?: "Не удалось привязать"
                            }
                            state = CabinetClient.state(context)
                            busy = false
                        }
                    }) { Text("Привязать") }
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                Text("Защита этого телефона", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text((if (status.secretsEncrypted) "✅" else "⚠️") + " Ключи зашифрованы")
                Text((if (status.bridgeV2) "✅" else "⚠️") + " Защищённый мост с компьютером")
                Text((if (status.voiceEnrolled) "✅" else "⚠️") + " Голос владельца (в разработке)")
                Text((if (status.confirmGate) "✅" else "⚠️") + " Подтверждение опасных действий")
            }
        }
    }
}

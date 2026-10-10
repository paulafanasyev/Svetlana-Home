package com.svetlana.home.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.svetlana.home.bridge.BridgeController
import com.svetlana.home.ui.components.GlassCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Подключение к ПК (мост к ядру Svetlana 2.0) — только по явному включению.
 * Без него Светлана полностью работает на телефоне. Адрес и код показываем
 * прямо здесь (работает и без разрешения на уведомления); диск и Keystore —
 * на Dispatchers.IO.
 */
@Composable
fun BridgeSettingsCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var bridgeOn by remember { mutableStateOf(false) }
    var bridgeInfo by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        bridgeOn = withContext(Dispatchers.IO) { BridgeController.isEnabled(context) }
    }
    // Адрес может смениться (другая Wi‑Fi сеть) — обновляем, пока мост включён.
    LaunchedEffect(bridgeOn) {
        if (!bridgeOn) {
            bridgeInfo = null
            return@LaunchedEffect
        }
        while (true) {
            bridgeInfo = withContext(Dispatchers.IO) {
                try {
                    val where = BridgeController.address() ?: "нет Wi‑Fi"
                    "$where · код ${BridgeController.pairingCode(context)}"
                } catch (e: IllegalStateException) {
                    e.message
                }
            }
            delay(30_000)
        }
    }
    GlassCard(modifier = Modifier.fillMaxWidth().testTag("settings_bridge")) {
        Column {
            Text("Подключение к ПК", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = bridgeInfo ?: if (bridgeOn) "Включено" else "Выключено: Светлана работает только на телефоне",
                style = MaterialTheme.typography.bodySmall,
                color = if (bridgeOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (bridgeOn) "Выключить" else "Включить",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable {
                        val target = !bridgeOn
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                try {
                                    BridgeController.setEnabled(context, target)
                                    true
                                } catch (e: IllegalStateException) {
                                    false
                                }
                            }
                            if (ok) bridgeOn = target else bridgeInfo = "Мост недоступен: нет защищённого хранилища"
                        }
                    }
                    .padding(horizontal = 4.dp, vertical = 6.dp)
            )
        }
    }
}

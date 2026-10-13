package com.svetlana.home.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.svetlana.home.R
import com.svetlana.home.core.ServiceLocator
import com.svetlana.home.core.tr
import com.svetlana.home.ui.components.GlassCard
import com.svetlana.home.ui.theme.AlmostBlack
import com.svetlana.home.ui.theme.MintPrimary
import com.svetlana.home.ui.theme.TextSecondary
import com.svetlana.home.voice.VoiceAssistantService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Настройки голоса (аудит п.8): STT, TTS, wake word, язык.
 */
@Composable
fun VoiceSettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tts = remember { ServiceLocator.tts }
    val recognizer = remember { ServiceLocator.speechRecognizer }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                Text(tr("Голос Светланы", "Svetlana’s voice"), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(tr("TTS доступен", "TTS available") + ": " + if (tts.isAvailable) tr("да", "yes") else tr("нет", "no"),
                    style = MaterialTheme.typography.bodySmall)
                Text(tr("STT (русский)", "STT (Russian)") + ": " + if (recognizer.isAvailable) tr("доступен", "available") else tr("недоступен", "unavailable"),
                    style = MaterialTheme.typography.bodySmall)
            }
        }

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            var wakeOn by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                wakeOn = ServiceLocator.settings.wakeWordEnabled.first()
            }
            Column {
                Text("Wake word", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text("Света, Светочка, Светлана",
                    style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = tr(
                        "Фоновый ассистент слушает слово пробуждения, даже " +
                            "когда приложение свёрнуто. Работает через системный " +
                            "STT по таймеру, а не как low-power always-on детектор — " +
                            "это ограничение текущей реализации.",
                        "The background assistant listens for the wake word even " +
                            "when the app is minimized. It uses the system STT on a timer, " +
                            "not a low-power always-on detector — a limitation of the current implementation."
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                // Аудит п.12: технический режим прослушивания видим
                // пользователю — никаких скрытых характеристик.
                Text(
                    text = tr("Режим", "Mode") + ": ${ServiceLocator.wakeWord.listeningKind}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (wakeOn) tr("Фоновый ассистент включён", "Background assistant on")
                        else tr("Фоновый ассистент выключен", "Background assistant off"),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = wakeOn,
                        onCheckedChange = { enabled ->
                            wakeOn = enabled
                            scope.launch {
                                ServiceLocator.settings.setWakeWordEnabled(enabled)
                                if (enabled) VoiceAssistantService.startIfEnabled(context)
                                else VoiceAssistantService.stop(context)
                            }
                        }
                    )
                }
            }
        }

        SystemSettingsRow(
            label = stringResource(R.string.sys_accessibility),
            intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/**
 * Настройки Hands (аудит п.8): доступ, статус, проверка.
 */
@Composable
fun HandsSettingsScreen() {
    val context = LocalContext.current
    val pm = remember { ServiceLocator.permissionManager }
    var enabled by remember { mutableStateOf(pm.accessibilityEnabled()) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                Text(tr("Hands / Специальные возможности", "Hands / Accessibility"),
                    style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(tr("Статус", "Status") + ": " + if (enabled) tr("Активен", "Active") else tr("Выключено", "Off"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (enabled) MintPrimary else TextSecondary)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = tr(
                        "Разрешение означает, что система выдала доступ. " +
                            "Реальное действие подтверждается доказательной цепочкой: ",
                        "The permission only means the system granted access. " +
                            "A real action is confirmed by the evidence chain: "
                    ) + "PLAN → TARGET → ACTION_ATTEMPTED → ACTION_PERFORMED → VERIFIED.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        SystemSettingsRow(
            label = if (enabled) tr("Изменить доступ Hands", "Change Hands access") else tr("Включить Hands", "Turn on Hands"),
            intent = pm.settingsIntentFor(com.svetlana.home.permissions.PermissionManager.KEY_HANDS)
        )
        SystemSettingsRow(
            label = stringResource(R.string.sys_accessibility),
            intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/**
 * Раздел Launcher: настройки главного экрана (аудит п.8).
 */
@Composable
fun AppDrawerSettingsScreen() {
    LauncherSettingsScreen()
}

/**
 * Конфиденциальность (аудит п.8): Local Only, что можно отправлять.
 */
@Composable
fun PrivacySettingsScreen() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                Text(tr("Режим «Только устройство»", "“Device only” mode"),
                    style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = tr(
                        "В этом режиме External AI, Personal Server и Cloud " +
                            "запрещены. Если локальный ИИ не справляется, Светлана " +
                            "сообщает, что режим запрещает передачу данных наружу.",
                        "In this mode External AI, Personal Server and Cloud " +
                            "are blocked. If the local AI can’t handle a request, Svetlana " +
                            "says the mode forbids sending data outside."
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                Text(tr("Что можно отправлять", "What may be sent"), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    tr(
                        "Текст, скриншоты, UI tree, изображения, аудио, история, " +
                            "документы — каждое проверяется Privacy Router перед отправкой " +
                            "конкретному бэкенду.",
                        "Text, screenshots, UI tree, images, audio, history, " +
                            "documents — each is checked by the Privacy Router before being sent " +
                            "to a specific backend."
                    ),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * Системные настройки телефона (аудит п.9).
 *
 * Светлана НЕ копирует Android Settings внутрь себя. Она открывает
 * соответствующий системный экран — это оболочка, а не замена.
 */
@Composable
fun SystemSettingsScreen() {
    val context = LocalContext.current

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.system_settings_note),
            style = MaterialTheme.typography.bodySmall)

        SystemSettingsRow(
            label = stringResource(R.string.system_open_settings),
            intent = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        SystemSettingsRow(
            label = stringResource(R.string.sys_wifi),
            intent = Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        SystemSettingsRow(
            label = stringResource(R.string.sys_bluetooth),
            intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        SystemSettingsRow(
            label = stringResource(R.string.sys_mobile_network),
            intent = Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        SystemSettingsRow(
            label = stringResource(R.string.sys_display),
            intent = Intent(Settings.ACTION_DISPLAY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        SystemSettingsRow(
            label = stringResource(R.string.sys_sound),
            intent = Intent(Settings.ACTION_SOUND_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        SystemSettingsRow(
            label = stringResource(R.string.sys_battery),
            intent = Intent(Intent.ACTION_POWER_USAGE_SUMMARY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        SystemSettingsRow(
            label = stringResource(R.string.sys_apps),
            intent = Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        SystemSettingsRow(
            label = stringResource(R.string.sys_notifications),
            intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        SystemSettingsRow(
            label = stringResource(R.string.sys_location),
            intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        SystemSettingsRow(
            label = stringResource(R.string.sys_security),
            intent = Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        SystemSettingsRow(
            label = stringResource(R.string.sys_accessibility),
            intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        SystemSettingsRow(
            label = stringResource(R.string.sys_date_time),
            intent = Intent(Settings.ACTION_DATE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        SystemSettingsRow(
            label = stringResource(R.string.sys_storage),
            intent = Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/**
 * Экран выбора темы интерфейса (ТЗ §6).
 *
 * 0 — по системе, 1 — тёмная, 2 — светлая.
 */
@Composable
fun ThemeScreen() {
    val scope = rememberCoroutineScope()
    val settings = remember { ServiceLocator.settings }
    val mode by settings.themeMode.collectAsState(initial = 0)

    val options = listOf(
        0 to tr("По системе", "System default"),
        1 to tr("Тёмная", "Dark"),
        2 to tr("Светлая", "Light")
    )
    val appearance = tr("Оформление", "Appearance")
    val logoDesc = tr("Логотип Светланы", "Svetlana logo")

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(
                text = appearance,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
        }
        items(options.size) { i ->
            val (value, label) = options[i]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (mode == value) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surface)
                    .clickable { scope.launch { settings.setThemeMode(value) } }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (mode == value) Text("✓", color = MaterialTheme.colorScheme.primary)
            }
        }
        item {
            Spacer(Modifier.height(12.dp))
            Image(
                painter = androidx.compose.ui.res.painterResource(R.drawable.logo_svetlana),
                contentDescription = logoDesc,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "SVETLANA HOME",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

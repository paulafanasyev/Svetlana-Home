package com.svetlana.home.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.svetlana.home.R
import com.svetlana.home.core.ServiceLocator
import com.svetlana.home.ui.components.GlassCard
import com.svetlana.home.ui.theme.AlmostBlack
import com.svetlana.home.ui.theme.MintPrimary
import com.svetlana.home.ui.theme.TextSecondary

/**
 * Настройки голоса (аудит п.8): STT, TTS, wake word, язык.
 */
@Composable
fun VoiceSettingsScreen() {
    val context = LocalContext.current
    val tts = remember { ServiceLocator.tts }
    val recognizer = remember { ServiceLocator.speechRecognizer }
    var selectedLang by remember { mutableStateOf("ru-RU") }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                Text("Голос Светланы", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text("TTS доступен: ${if (tts.isAvailable) "да" else "нет"}",
                    style = MaterialTheme.typography.bodySmall)
                Text("STT (русский): ${if (recognizer.isAvailable) "доступен" else "недоступен"}",
                    style = MaterialTheme.typography.bodySmall)
            }
        }

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                Text("Wake word", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text("Света, Светочка, Светлана",
                    style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Слушатель работает через системный STT по таймеру, " +
                            "а не как low-power always-on детектор. Это ограничение " +
                            "текущей реализации.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Spacer(Modifier.height(4.dp))
                // Аудит п.12: технический режим прослушивания видим
                // пользователю — никаких скрытых характеристик.
                Text(
                    text = "Режим: ${ServiceLocator.wakeWord.listeningKind}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }

        // Язык распознавания
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                Text("Язык STT", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                listOf("ru-RU" to "Русский", "vi-VN" to "Вьетнамский").forEach { (id, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (selectedLang == id) MintPrimary.copy(alpha = 0.15f) else AlmostBlack)
                            .clickable { selectedLang = id }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(label, modifier = Modifier.weight(1f))
                        if (selectedLang == id) Text("✓", color = MintPrimary)
                    }
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
                Text("Hands / Специальные возможности",
                    style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text("Статус: ${if (enabled) "Активен" else "Выключено"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (enabled) MintPrimary else TextSecondary)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Разрешение означает, что система выдала доступ. " +
                            "Реальное действие подтверждается доказательной цепочкой: " +
                            "PLAN → TARGET → ACTION_ATTEMPTED → ACTION_PERFORMED → VERIFIED.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }

        SystemSettingsRow(
            label = if (enabled) "Изменить доступ Hands" else "Включить Hands",
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
                Text("Режим «Только устройство»",
                    style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "В этом режиме External AI, Personal Server и Cloud " +
                            "запрещены. Если локальный ИИ не справляется, Светлана " +
                            "сообщает, что режим запрещает передачу данных наружу.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                Text("Что можно отправлять", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text("Текст, скриншоты, UI tree, изображения, аудио, история, " +
                        "документы — каждое проверяется Privacy Router перед отправкой " +
                        "конкретному бэкенду.",
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

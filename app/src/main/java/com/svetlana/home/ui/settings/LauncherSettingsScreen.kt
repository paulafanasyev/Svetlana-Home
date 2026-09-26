package com.svetlana.home.ui.settings

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import com.svetlana.home.permissions.PermissionManager
import com.svetlana.home.ui.components.GlassCard
import com.svetlana.home.ui.theme.AlmostBlack
import com.svetlana.home.ui.theme.MintPrimary
import com.svetlana.home.ui.theme.TextSecondary

/**
 * Launcher (ТЗ §4): официальный механизм ROLE_HOME.
 *
 * Цепочка:
 *   Настройки Светланы → Главный экран →
 *   [Сделать Светлану главным экраном] →
 *   системный Android ROLE_HOME → подтверждение → Светлана становится Home.
 *
 * После назначения Home → Светлана и Home после перезагрузки → Светлана
 * сохраняются системой. BootCompletedReceiver не нужен для самого факта
 * назначения — Android хранит роль постоянно.
 */
@Composable
fun LauncherSettingsScreen() {
    val context = LocalContext.current
    val pm = remember { ServiceLocator.permissionManager }
    var isHome by remember { mutableStateOf(pm.isHomeLauncher()) }

    // Запускает системный запрос ROLE_HOME и фиксирует результат.
    val roleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        // Проверяем реальное состояние после возврата из системного диалога.
        isHome = pm.isHomeLauncher()
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                Text(stringResource(R.string.title_launcher), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.launcher_status_current) + ": " +
                            if (isHome) stringResource(R.string.launcher_already_home)
                            else stringResource(R.string.launcher_not_home),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isHome) MintPrimary else TextSecondary
                )
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.launcher_hint), style = MaterialTheme.typography.bodySmall)
            }
        }

        if (!isHome) {
            Button(
                onClick = {
                    val intent = pm.homeRoleIntent()
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        roleLauncher.launch(intent)
                    } catch (t: Throwable) {
                        // Если RoleManager недоступен — открываем системные
                        // настройки выбора launcher (запасной штатный путь).
                        try {
                            val fallback = Intent(Settings.ACTION_HOME_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            roleLauncher.launch(fallback)
                        } catch (t2: Throwable) {
                            isHome = pm.isHomeLauncher()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MintPrimary)
            ) {
                Text(stringResource(R.string.launcher_make_home), color = AlmostBlack)
            }
        } else {
            // Уже назначен: даём пользователю возможность вернуться к системному
            // выбору, не обходя Android.
            SystemSettingsRow(
                label = "Выбрать другой главный экран",
                intent = Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }

        // Быстрый переход к App Drawer
        SystemSettingsRow(
            label = stringResource(R.string.title_apps),
            intent = Intent(context, com.svetlana.home.ui.apps.AppDrawerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/**
 * Строка-переход в системные настройки Android.
 * Светлана открывает системный экран, а не дублирует его (ТЗ §3, §9 из аудита).
 */
@Composable
fun SystemSettingsRow(label: String, intent: Intent) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AlmostBlack)
            .clickable {
                try { context.startActivity(intent) } catch (t: Throwable) { }
            }
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
    }
}

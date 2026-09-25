package com.svetlana.home.ui.permissions

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.svetlana.home.permissions.PermissionItem
import com.svetlana.home.ui.components.GlassCard
import com.svetlana.home.ui.theme.AlmostBlack
import com.svetlana.home.ui.theme.MintPrimary
import com.svetlana.home.ui.theme.MintSoft
import com.svetlana.home.ui.theme.TextSecondary

/**
 * Мастер «Настроить Светлану» — последовательно запрашивает только
 * действительно нужные доступы (ТЗ §25).
 *
 * Никакие лишние разрешения не запрашиваются. Если Android требует
 * повторное системное подтверждение — пользователь проходит штатный flow.
 */
@Composable
fun PermissionSetupScreen(onAllHandled: () -> Unit) {
    val context = LocalContext.current
    val pm = remember { ServiceLocator.permissionManager }
    var items by remember { mutableStateOf(pm.list()) }
    var currentIndex by remember { mutableStateOf(0) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        items = pm.list()
        currentIndex++
    }

    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        items = pm.list()
        currentIndex++
    }

    if (currentIndex >= items.size) {
        // Все шаги пройдены
        Box(modifier = Modifier.fillMaxSize().background(AlmostBlack)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Настройка завершена. Можно начинать.",
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = onAllHandled,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MintPrimary)
                ) {
                    Text(stringResource(R.string.perm_finish), color = AlmostBlack)
                }
            }
        }
        return
    }

    val item = items[currentIndex]

    Box(modifier = Modifier.fillMaxSize().background(AlmostBlack)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = stringResource(R.string.setup_title),
                    style = MaterialTheme.typography.headlineMedium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.setup_subtitle),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(20.dp))
                ProgressDots(total = items.size, current = currentIndex)
                Spacer(Modifier.height(20.dp))
                Text(text = item.title, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(text = item.description, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (item.granted) "Уже предоставлено" else if (item.required) "Требуется" else "Необязательно",
                    style = MaterialTheme.typography.labelLarge
                )
            }

            Column(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        when (item.kind) {
                            PermissionItem.Kind.RUNTIME -> {
                                pm.runtimePermissionFor(item.key)?.let { permLauncher.launch(it) }
                                    ?: run { currentIndex++ }
                            }
                            PermissionItem.Kind.NOTIFICATION -> {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else currentIndex++
                            }
                            PermissionItem.Kind.ACCESSIBILITY,
                            PermissionItem.Kind.SETTINGS -> {
                                settingsLauncher.launch(pm.settingsIntentFor(item.key))
                            }
                            PermissionItem.Kind.ROLE_HOME -> {
                                try {
                                    settingsLauncher.launch(pm.homeRoleIntent())
                                } catch (t: Throwable) {
                                    settingsLauncher.launch(
                                        Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MintPrimary)
                ) {
                    Text(
                        text = if (item.granted) "Продолжить" else stringResource(R.string.perm_grant),
                        color = AlmostBlack
                    )
                }
                Spacer(Modifier.height(8.dp))
                if (!item.required) {
                    androidx.compose.material3.TextButton(
                        onClick = { currentIndex++ },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.perm_skip)) }
                }
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.TextButton(
                    onClick = {
                        settingsLauncher.launch(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.perm_open_settings)) }
            }
        }
    }
}

@Composable
private fun ProgressDots(total: Int, current: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (i in 0 until total) {
            Box(
                modifier = Modifier
                    .height(4.dp)
                    .weight(1f)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (i <= current) MintPrimary else TextSecondary.copy(alpha = 0.3f))
            )
        }
    }
}

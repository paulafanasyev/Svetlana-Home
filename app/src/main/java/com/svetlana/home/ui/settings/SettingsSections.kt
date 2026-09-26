package com.svetlana.home.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.foundation.lazy.LazyColumn
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
import com.svetlana.home.avatar.AvatarLevel
import com.svetlana.home.core.ServiceLocator
import com.svetlana.home.device.DeviceCapabilityManager
import com.svetlana.home.memory.MemoryMode
import com.svetlana.home.permissions.PermissionItem
import com.svetlana.home.ui.components.GlassCard
import com.svetlana.home.ui.theme.AlmostBlack
import com.svetlana.home.ui.theme.MintPrimary
import com.svetlana.home.ui.theme.MintSoft
import com.svetlana.home.ui.theme.TextSecondary
import com.svetlana.home.ui.theme.WarnAmber
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

/**
 * Permission Center (ТЗ §27).
 * Показывает реальное состояние каждого доступа. Любое состояние можно изменить.
 *
 * Постоянный доступ используется повторно в рамках разрешённой функции,
 * но не является безусловным (ТЗ §59).
 */
@Composable
fun PermissionCenterScreen() {
    val context = LocalContext.current
    val pm = remember { ServiceLocator.permissionManager }
    var items by remember { mutableStateOf(pm.list()) }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.perm_note_persistent),
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(12.dp))
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(items.size) { i ->
                val item = items[i]
                // Аудит п.7: геолокация — два независимых состояния.
                val isLocation = item.key == com.svetlana.home.permissions.PermissionManager.KEY_LOCATION
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.title, style = MaterialTheme.typography.titleMedium)
                            Text(item.description, style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = item.statusText,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (item.granted) MintPrimary else TextSecondary
                            )
                            if (isLocation) {
                                val diag = remember(item.key) {
                                    ServiceLocator.permissionManager.locationDiagnostics()
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = "Разрешение Светлане: ${if (diag.permissionGranted) "выдано" else "не выдано"}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    text = "Системная геолокация: ${if (diag.locationServicesEnabled) "включена" else "выключена"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (diag.locationServicesEnabled) MintPrimary else WarnAmber
                                )
                                if (diag.locationServicesEnabled && !diag.permissionGranted) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = "Нажмите «Изменить», чтобы выдать разрешение Светлане",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary
                                    )
                                } else if (!diag.locationServicesEnabled && diag.permissionGranted) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = "Нажмите «Изменить», чтобы включить системную геолокацию Android",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary
                                    )
                                }
                            }
                        }
                        Text(
                            text = stringResource(R.string.perm_change),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    val intent = if (isLocation && ServiceLocator.permissionManager
                                            .locationDiagnostics().permissionGranted) {
                                        // Разрешение есть, но выключена системная геолокация —
                                        // ведём в системные настройки геолокации.
                                        ServiceLocator.permissionManager.locationServicesIntent()
                                    } else {
                                        pm.settingsIntentFor(item.key)
                                    }
                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    try { context.startActivity(intent) } catch (t: Throwable) { }
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }
            item {
                Text(
                    text = "Подтверждение опасных действий всегда запрашивается у пользователя.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}

@Composable
fun OwnerScreen() {
    val owner = remember { ServiceLocator.ownerIdentity }
    var created by remember { mutableStateOf(owner.isOwnerCreated()) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                Text("Профиль владельца", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                if (created) {
                    Text("Имя: ${owner.displayName()}", style = MaterialTheme.typography.bodyMedium)
                    Text("Идентификатор: ${owner.stablePublicId()}", style = MaterialTheme.typography.bodySmall)
                    Text("Android Keystore: ${if (owner.keyStoreBacked()) "используется" else "недоступен"}",
                        style = MaterialTheme.typography.bodySmall)
                    Text("Биометрия доступна: ${if (owner.biometricAvailable()) "да" else "нет"}",
                        style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(
                        text = "Единый профиль ещё не создан. PIN и пароли не сохраняются.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
        if (!created) {
            Button(
                onClick = {
                    scope.launch {
                        val result = owner.createOwner("Владелец")
                        if (result is com.svetlana.home.owner.OwnerIdentity.Result.Created) {
                            created = true
                            ServiceLocator.historyManager.record(
                                com.svetlana.home.memory.HistoryCategory.CONFIRMATIONS,
                                "Создан профиль владельца, Keystore=${owner.keyStoreBacked()}"
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MintPrimary)
            ) { Text("Создать профиль владельца", color = AlmostBlack) }
        } else {
            Text(
                text = "Статус владельца не даёт root, скрытого доступа к Accessibility, " +
                        "микрофону или камере и не обходит Android permission model.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
fun DeviceScreen() {
    val device = remember { ServiceLocator.device }
    val caps = remember { device.refresh() }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    Text("Реальные характеристики устройства", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    DeviceRow(R.string.device_model, caps.model)
                    DeviceRow(R.string.device_android, caps.androidVersion)
                    DeviceRow(R.string.device_sdk, caps.sdkInt.toString())
                    DeviceRow(R.string.device_abi, caps.abis.joinToString(", "))
                    DeviceRow(R.string.device_cpu, "${caps.cpuCores} ядер")
                    DeviceRow(R.string.device_ram, "${caps.ramTotalMb} МБ")
                    DeviceRow(R.string.device_ram_free, "${caps.ramAvailableMb} МБ")
                    DeviceRow(R.string.device_storage, "${caps.storageTotalMb} МБ")
                    DeviceRow(R.string.device_storage_free, "${caps.storageAvailableMb} МБ")
                    DeviceRow(R.string.device_gpu, caps.gpu)
                    DeviceRow(R.string.device_gpu_vendor, caps.gpuVendor)
                    DeviceRow(R.string.device_vulkan,
                        if (caps.vulkanSupported) caps.vulkanVersion.ifBlank { "да" } else "нет")
                    DeviceRow(R.string.device_gles, caps.openGlEsVersion)
                    DeviceRow(R.string.device_nnapi, if (caps.nnapiSupported) "да" else "нет")
                    DeviceRow(R.string.device_thermal, caps.thermalStatus)
                    DeviceRow(R.string.device_battery, "${caps.batteryPercent}%")
                    DeviceRow(R.string.device_screen, "%.1f\" @ %ddpi".format(caps.screenInches, caps.screenDensityDpi))
                    DeviceRow(R.string.device_camera, if (caps.hasCamera) "есть" else "нет")
                    DeviceRow(R.string.device_mic, if (caps.hasMicrophone) "есть" else "нет")
                    DeviceRow(R.string.device_network, caps.networkType)
                    DeviceRow(R.string.device_backend,
                        "cpu=${caps.backendSupport.cpu} gpu=${caps.backendSupport.gpu} npu=${caps.backendSupport.npu}")
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(labelRes: Int, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = stringResource(labelRes), style = MaterialTheme.typography.bodyMedium)
        Text(text = value, style = MaterialTheme.typography.bodyMedium, color = MintSoft)
    }
}

@Composable
fun AvatarScreen() {
    val engine = remember { ServiceLocator.avatarEngine }
    val level = remember { engine.evaluate() }
    val reason = remember { engine.reason(level) }

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Text("Адаптивный аватар", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text("Текущий режим: ${level.label} (уровень ${level.level})",
                style = MaterialTheme.typography.bodyMedium)
            Text(reason, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            Text("Деградация при нехватке ресурсов: Real Avatar → Light Avatar → Living Orb",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun MemoryScreen() {
    val settings = ServiceLocator.settings
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var mode by remember { mutableStateOf(MemoryMode.LOCAL) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        mode = settings.memoryMode.first()
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.memory_note), style = MaterialTheme.typography.bodyMedium)
        MemoryMode.entries.forEach { m ->
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            mode = m
                            scope.launch { settings.setMemoryMode(m) }
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(m.label, modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium)
                    if (mode == m) Text("✓", color = MintPrimary)
                }
            }
        }
        val facts = remember { ServiceLocator.personalMemory.facts() }
        Text("Фактов в памяти: ${facts.size}", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun AboutScreen() {
    val packageInfo = remember {
        try {
            com.svetlana.home.SvetlanaApp.instance.packageManager.getPackageInfo(
                com.svetlana.home.SvetlanaApp.instance.packageName, 0)
        } catch (t: Throwable) { null }
    }
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Text("SVETLANA HOME", style = MaterialTheme.typography.titleLarge)
            Text("Персональный AI-телефон", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Text("Версия: ${packageInfo?.versionName ?: "?"} (${packageInfo?.versionCode ?: 0})",
                style = MaterialTheme.typography.bodySmall)
            Text("Сборка: ${com.svetlana.home.BuildConfig.BUILD_TYPE}",
                style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Безопасность: нет root, нет Termux, нет произвольных shell-команд, " +
                        "нет скрытых загрузок, нет скрытого управления, опасные действия " +
                        "подтверждаются пользователем.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

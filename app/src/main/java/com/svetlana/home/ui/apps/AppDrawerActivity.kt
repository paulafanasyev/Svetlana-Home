package com.svetlana.home.ui.apps

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.svetlana.home.R
import com.svetlana.home.apps.AppCategory
import com.svetlana.home.apps.AppModel
import com.svetlana.home.core.ServiceLocator
import com.svetlana.home.ui.components.GlassCard
import com.svetlana.home.ui.theme.AlmostBlack
import com.svetlana.home.ui.theme.MintPrimary
import com.svetlana.home.ui.theme.MintSoft
import com.svetlana.home.ui.theme.SvetlanaTheme
import com.svetlana.home.ui.theme.TextPrimary
import com.svetlana.home.ui.theme.TextTertiary

/**
 * App Drawer (ТЗ §11): список приложений, поиск, категории, избранное,
 * недавние, скрытые, запуск, настройки, удаление.
 */
class AppDrawerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SvetlanaTheme { AppDrawerScreen() } }
    }
}

@Composable
private fun AppDrawerScreen() {
    val context = LocalContext.current
    val registry = remember { ServiceLocator.appRegistry }
    var query by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(Tab.ALL) }
    var selectedCategory by remember { mutableStateOf<String?>(null) }

    // ТЗ §11: список строится из реального PackageManager.
    // Сканируем при открытии drawer, чтобы состав был актуальным.
    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            registry.scan()
        }
    }

    val apps by registry.apps.collectAsState()
    val visible by remember(apps, query, tab, selectedCategory) {
        derivedStateOf {
            when (tab) {
                Tab.ALL -> registry.search(query).filterNot { it.isHidden }
                Tab.FAVORITES -> registry.favorites().filter {
                    query.isBlank() || it.label.contains(query, ignoreCase = true)
                }
                Tab.RECENT -> registry.recent().filter {
                    query.isBlank() || it.label.contains(query, ignoreCase = true)
                }
                Tab.CATEGORIES -> apps.filterNot { it.isHidden }.filter {
                    (selectedCategory == null || it.category == selectedCategory) &&
                        (query.isBlank() || it.label.contains(query, ignoreCase = true))
                }
                Tab.HIDDEN -> apps.filter { it.isHidden }
            }
        }
    }
    val categories by remember(apps, tab) {
        derivedStateOf {
            if (tab == Tab.CATEGORIES) AppCategory.ALL.filter { c ->
                apps.any { it.category == c && !it.isHidden }
            } else emptyList()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(AlmostBlack)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(16.dp)
        ) {
            Text(
                text = stringResource(R.string.title_apps),
                style = MaterialTheme.typography.headlineMedium
            )
            Text(
                text = "Установлено приложений: ${apps.size}",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary
            )
            Spacer16()

            // Поиск
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Search, contentDescription = null, tint = TextTertiary)
                    Spacer8()
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        textStyle = TextStyle(color = TextPrimary, fontSize = 16.sp),
                        cursorBrush = SolidColor(MintPrimary),
                        keyboardOptions = KeyboardOptions.Default,
                        modifier = Modifier.weight(1f),
                        decorationBox = { inner ->
                            if (query.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.search_hint),
                                    color = TextTertiary
                                )
                            }
                            inner()
                        }
                    )
                }
            }
            Spacer16()

            // Вкладки
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Tab.entries.forEach { t ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (tab == t) MintPrimary.copy(alpha = 0.18f) else
                                    TextTertiary.copy(alpha = 0.06f)
                            )
                            .clickable { tab = t }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = t.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (tab == t) MintPrimary else TextTertiary
                        )
                    }
                }
            }
            Spacer16()

            // Категории (ТЗ §11)
            if (tab == Tab.CATEGORIES && categories.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    item {
                        CategoryChip(
                            label = "Все категории",
                            selected = selectedCategory == null,
                            onClick = { selectedCategory = null }
                        )
                    }
                    items(categories) { category ->
                        CategoryChip(
                            label = category,
                            selected = selectedCategory == category,
                            onClick = { selectedCategory = category }
                        )
                    }
                }
                Spacer16()
            }

            // Список
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                if (visible.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.search_no_results),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                items(visible, key = { it.packageName }) { app ->
                    AppRow(app = app, onLaunch = {
                        try {
                            context.packageManager.getLaunchIntentForPackage(app.packageName)?.let { intent ->
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                                ServiceLocator.appRepository.markUsed(app.packageName)
                            }
                        } catch (t: Throwable) {
                            ServiceLocator.historyManager.record(
                                com.svetlana.home.memory.HistoryCategory.APPS,
                                "Не удалось открыть ${app.label}: ${t.message}"
                            )
                        }
                    })
                }
            }
        }
    }
}
@Composable
private fun CategoryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MintPrimary.copy(alpha = 0.18f) else TextTertiary.copy(alpha = 0.06f)
            )
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MintPrimary else TextTertiary
        )
    }
}

@Composable
private fun AppRow(app: AppModel, onLaunch: () -> Unit) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            val drawable = remember(app.packageName) {
                ServiceLocator.appRepository.iconFor(app.packageName)
            }
            val bitmap = remember(app.packageName) { drawable?.toImageBitmap(40) }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = app.label,
                    modifier = Modifier.size(40.dp)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MintPrimary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(app.label.firstOrNull()?.toString() ?: "?")
                }
            }
            Spacer8()
            Column(modifier = Modifier.weight(1f)) {
                Text(text = app.label, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary
                )
                // ТЗ §66: показываем реальные возможности управления.
                Text(
                    text = capabilitySummary(app),
                    style = MaterialTheme.typography.bodySmall,
                    color = MintSoft
                )
            }
            TextButton(text = stringResource(R.string.action_launch), onClick = onLaunch)
        }
    }
}

/**
 * Краткая сводка того, какими способами Светлана может управлять приложением.
 * ТЗ §66: пользователь видит реальные возможности, а не обещания.
 */
private fun capabilitySummary(app: AppModel): String {
    val caps = app.control
    val ways = buildList {
        if (caps.canLaunch) add("запуск")
        if (caps.canIntent) add("intent")
        if (caps.canDeepLink) add("deep-link")
        if (caps.canAccessibility) add("hands")
    }
    return if (ways.isEmpty()) "управление недоступно" else "управление: ${ways.joinToString(", ")}"
}

/** Конвертация Drawable в ImageBitmap для Compose. */
private fun android.graphics.drawable.Drawable.toImageBitmap(sizePx: Int): ImageBitmap {
    val bmp = android.graphics.Bitmap.createBitmap(sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    setBounds(0, 0, sizePx, sizePx)
    draw(canvas)
    return bmp.asImageBitmap()
}

@Composable
private fun TextButton(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

@Composable
private fun Spacer8() = Spacer(Modifier.size(8.dp))

@Composable
private fun Spacer16() = Spacer(Modifier.size(16.dp))

private enum class Tab(val label: String) {
    ALL("Все"), FAVORITES("Избранное"), RECENT("Недавние"),
    CATEGORIES("Категории"), HIDDEN("Скрытые")
}


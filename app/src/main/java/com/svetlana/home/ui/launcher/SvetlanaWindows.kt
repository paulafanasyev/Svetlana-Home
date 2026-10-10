package com.svetlana.home.ui.launcher

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.svetlana.home.R
import com.svetlana.home.apps.AppModel
import com.svetlana.home.ui.components.GlassCard
import com.svetlana.home.ui.components.LivingOrb
import com.svetlana.home.ui.history.HistoryActivity
import com.svetlana.home.ui.settings.SettingsActivity
import com.svetlana.home.ui.translator.TranslatorActivity
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

/** Полноэкранное окно Светланы с кнопкой закрытия. */
@Composable
internal fun SvetlanaWindowFrame(tag: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag(tag)
    ) {
        content()
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 4.dp, end = 8.dp)
                .testTag("window_close")
        ) {
            Icon(Icons.Outlined.Close, contentDescription = "Закрыть окно", tint = MaterialTheme.colorScheme.onBackground)
        }
    }
}

/**
 * Панель Светланы слева от рабочего стола (как экран −1 в Pixel Launcher):
 * орб, последний ответ и вход во все функции Светланы.
 */
@Composable
internal fun SvetlanaPanel(
    uiState: HomeUiState,
    onOpen: (SvetlanaWindow) -> Unit,
    onVoice: () -> Unit,
    onDrawer: () -> Unit,
    actions: LauncherActions
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.94f))
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
            .testTag("svetlana_panel")
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.svetlana_name), style = MaterialTheme.typography.headlineMedium)
                Text(
                    text = uiState.aiBackendLabel.ifBlank { "Ваш ИИ-помощник" },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }
            MiniOrb(36.dp)
        }

        GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(18.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                LivingOrb(
                    modifier = Modifier.clickable(onClick = onVoice),
                    size = 116.dp,
                    level = uiState.orbLevel,
                    active = uiState.orbActive || uiState.isListening || uiState.isThinking,
                    speaking = uiState.isSpeaking
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = uiState.lastReply,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton(Icons.Outlined.Mic, "Сказать", onVoice, "panel_voice")
                    PillButton(Icons.Outlined.Forum, "Написать", { onOpen(SvetlanaWindow.CHAT) }, "panel_chat")
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "Функции",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )
        val tiles = listOf(
            Tile("tile_chat", Icons.Outlined.Forum, "Чат", "Переписка со Светланой") { onOpen(SvetlanaWindow.CHAT) },
            Tile("tile_voice", Icons.Outlined.Mic, "Голос", "Разговор и команды") { onOpen(SvetlanaWindow.VOICE) },
            Tile("tile_translate", Icons.Outlined.Language, "Переводчик", "Текст, голос, камера") {
                actions.open(TranslatorActivity::class.java)
            },
            Tile("tile_history", Icons.Outlined.History, "История", "Что делала Светлана") {
                actions.open(HistoryActivity::class.java)
            },
            Tile("tile_settings", Icons.Outlined.Settings, "Настройки", "ИИ, голос, Hands и разрешения") {
                actions.open(SettingsActivity::class.java)
            },
            Tile("tile_apps", Icons.Outlined.Apps, "Приложения", "Все приложения телефона", onDrawer)
        )
        tiles.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { t -> FeatureTile(t, Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
        }
        Spacer(Modifier.height(24.dp))
    }
}

internal class Tile(
    val tag: String,
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val onClick: () -> Unit
)

@Composable
private fun FeatureTile(tile: Tile, modifier: Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = tile.onClick)
            .testTag(tile.tag)
            .padding(14.dp)
    ) {
        Box(
            Modifier.size(38.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(tile.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(tile.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            tile.subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outlineVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PillButton(icon: ImageVector, label: String, onClick: () -> Unit, tag: String) {
    Row(
        Modifier
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
            .clickable(onClick = onClick)
            .testTag(tag)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

/** Окно «Голос»: орб, ответ Светланы и подтверждение опасных действий. */
@Composable
internal fun VoiceWindowContent(viewModel: HomeViewModel, uiState: HomeUiState) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(top = 56.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        LivingOrb(
            modifier = Modifier
                .padding(12.dp)
                .clickable {
                    if (uiState.isListening) viewModel.stopListening() else viewModel.startListening(context)
                },
            size = 210.dp,
            level = uiState.orbLevel,
            active = uiState.orbActive || uiState.isListening || uiState.isThinking,
            speaking = uiState.isSpeaking
        )
        Text(stringResource(R.string.svetlana_name), style = MaterialTheme.typography.headlineMedium)
        Text(
            text = uiState.lastReply,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            maxLines = 6,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp)
        )
        if (uiState.aiBackendLabel.isNotBlank()) {
            Text(uiState.aiBackendLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outlineVariant)
        }
        if (uiState.orbReason.isNotBlank()) {
            Text(
                uiState.orbReason,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outlineVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
        }
        AnimatedVisibility(visible = uiState.pendingConfirmation != null, enter = fadeIn(), exit = fadeOut()) {
            GlassCard(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                contentPadding = PaddingValues(16.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PillButton(Icons.Outlined.Mic, stringResource(R.string.confirm), { viewModel.confirmPendingAction(context) }, "voice_confirm")
                    PillButton(Icons.Outlined.Close, stringResource(R.string.cancel), { viewModel.cancelPendingAction() }, "voice_cancel")
                }
            }
        }
        Text(
            text = if (uiState.isListening) "Слушаю…" else "Нажмите на орб, чтобы говорить",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outlineVariant
        )
    }
}

/** Поле поиска в стиле Android: скруглённое, с иконкой лупы. */
@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String
) {
    Row(
        modifier
            .height(50.dp)
            .clip(RoundedCornerShape(25.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = MaterialTheme.colorScheme.onBackground, fontSize = 16.sp),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            modifier = Modifier.weight(1f).testTag("search_field"),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(placeholder, color = MaterialTheme.colorScheme.outlineVariant, fontSize = 16.sp, maxLines = 1)
                }
                inner()
            }
        )
        if (value.isNotEmpty()) {
            IconButton(onClick = { onValueChange("") }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Outlined.Close, contentDescription = "Очистить", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * Все приложения (свайп вверх): поиск, недавние и алфавитная сетка.
 * Закрывается свайпом вниз от верха списка или кнопкой «Назад».
 */
@Composable
internal fun AppDrawer(
    apps: List<AppModel>,
    menu: AppMenu,
    onLaunch: (AppModel) -> Unit,
    onAskSvetlana: (String) -> Unit,
    onClose: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    val sorted = remember(apps) { LauncherModel.sortByLabel(apps) }
    val recent = remember(apps) { LauncherModel.recent(apps) }
    val results = remember(apps, query) { LauncherModel.search(apps, query) }
    val closePx = with(LocalDensity.current) { 96.dp.toPx() }
    val closeOnPull = remember(closePx) {
        object : NestedScrollConnection {
            var pulled = 0f
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.Drag && available.y > 0f) {
                    pulled += available.y
                    if (pulled > closePx) {
                        pulled = 0f
                        onClose()
                    }
                } else if (consumed.y != 0f) {
                    pulled = 0f
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                pulled = 0f
                return Velocity.Zero
            }
        }
    }
    var headerDrag by remember { mutableFloatStateOf(0f) }
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    // Буква → индекс первого приложения на эту букву в алфавитном списке.
    val letters = remember(sorted) {
        sorted.withIndex()
            .groupBy { LauncherModel.indexLetter(it.value.label) }
            .map { (letter, list) -> letter to list.first().index }
            .sortedBy { it.second }
    }
    val sortedOffset = if (recent.isNotEmpty()) recent.size + 3 else 1

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.97f))
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .testTag("app_drawer")
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .pointerInput(closePx) {
                    detectVerticalDragGestures(
                        onDragStart = { headerDrag = 0f },
                        onDragEnd = { if (headerDrag > closePx) onClose() },
                        onVerticalDrag = { _, dy -> headerDrag += dy }
                    )
                }
                .padding(horizontal = 16.dp)
        ) {
            Box(
                Modifier
                    .padding(top = 8.dp, bottom = 12.dp)
                    .align(Alignment.CenterHorizontally)
                    .size(width = 36.dp, height = 4.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), RoundedCornerShape(2.dp))
            )
            SearchField(
                value = query,
                onValueChange = { query = it },
                onSubmit = {
                    val first = results.firstOrNull()
                    if (first != null) onLaunch(first) else if (query.isNotBlank()) onAskSvetlana(query.trim())
                },
                placeholder = "Поиск приложений",
                modifier = Modifier.fillMaxWidth()
            )
        }

        Box(Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(LauncherModel.HOME_COLUMNS),
                state = gridState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(end = if (query.isBlank() && letters.size > 1) 30.dp else 0.dp)
                    .nestedScroll(closeOnPull)
                    .testTag("drawer_grid"),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (query.isBlank()) {
                    if (recent.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) { DrawerHeader("Недавние") }
                        items(recent, key = { "r_" + it.packageName }) { app ->
                            AppTile(app, menu, { onLaunch(app) }, onWallpaper = false)
                        }
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            HorizontalDivider(Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) { DrawerHeader("Все приложения · ${sorted.size}") }
                    items(sorted, key = { it.packageName }) { app ->
                        AppTile(app, menu, { onLaunch(app) }, onWallpaper = false)
                    }
                } else {
                    items(results, key = { it.packageName }) { app ->
                        AppTile(app, menu, { onLaunch(app) }, onWallpaper = false)
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        AskRow(Icons.Outlined.Forum, "Спросить Светлану: «${query.trim()}»", "drawer_ask") {
                            onAskSvetlana(query.trim())
                        }
                    }
                }
            }
            if (query.isBlank() && letters.size > 1) {
                FastScroller(
                    letters = letters.map { it.first },
                    modifier = Modifier.align(Alignment.CenterEnd),
                    onLetter = { i ->
                        val target = sortedOffset + letters[i].second
                        scope.launch { gridState.scrollToItem(target) }
                    }
                )
            }
        }
    }
}

/** Быстрый алфавитный указатель справа, как в стандартном списке приложений. */
@Composable
private fun FastScroller(letters: List<String>, modifier: Modifier, onLetter: (Int) -> Unit) {
    var active by remember { mutableIntStateOf(-1) }
    var heightPx by remember { mutableFloatStateOf(1f) }
    val latestOnLetter by rememberUpdatedState(onLetter)
    fun pick(y: Float) {
        val i = ((y / heightPx) * letters.size).toInt().coerceIn(0, letters.lastIndex)
        if (i != active) {
            active = i
            latestOnLetter(i)
        }
    }
    Box(modifier) {
        Column(
            Modifier
                .width(36.dp)
                .onSizeChanged { heightPx = it.height.toFloat().coerceAtLeast(1f) }
                .pointerInput(letters) {
                    // Один детектор: касание и протягивание по буквам.
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        pick(down.position.y)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()
                            pick(change.position.y)
                        }
                        active = -1
                    }
                }
                .semantics { contentDescription = "Алфавитный указатель приложений" }
                .testTag("fast_scroller"),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            letters.forEachIndexed { i, l ->
                Text(
                    l,
                    style = TextStyle(
                        fontSize = 11.sp,
                        fontWeight = if (i == active) FontWeight.Bold else FontWeight.Normal,
                        color = if (i == active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier
                        .padding(vertical = 1.dp)
                        .semantics {
                            contentDescription = "Приложения на букву $l"
                            onClick(label = "Перейти") { latestOnLetter(i); true }
                        }
                )
            }
        }
        if (active in letters.indices) {
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 48.dp)
                    .size(64.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(letters[active], style = TextStyle(fontSize = 28.sp, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold))
            }
        }
    }
}

@Composable
private fun DrawerHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp)
    )
}

@Composable
private fun AskRow(icon: ImageVector, text: String, tag: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .testTag(tag)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(36.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Окно поиска из строки на рабочем столе: приложения + вопрос Светлане + интернет. */
@Composable
internal fun SearchWindow(
    apps: List<AppModel>,
    onClose: () -> Unit,
    onLaunch: (AppModel) -> Unit,
    onAsk: (String) -> Unit,
    onWeb: (String) -> Unit,
    onOpenWindow: (SvetlanaWindow) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val results = remember(apps, query) { LauncherModel.search(apps, query).take(8) }
    val recent = remember(apps) { LauncherModel.recent(apps) }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .testTag("window_search")
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose, modifier = Modifier.testTag("window_close")) {
                Icon(Icons.Outlined.Close, contentDescription = "Закрыть поиск", tint = MaterialTheme.colorScheme.onBackground)
            }
            SearchField(
                value = query,
                onValueChange = { query = it },
                onSubmit = { if (query.isNotBlank()) onAsk(query.trim()) },
                placeholder = "Приложение или вопрос",
                modifier = Modifier.weight(1f).focusRequester(focus)
            )
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 12.dp)) {
            if (query.isBlank()) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PillButton(Icons.Outlined.Forum, "Чат", { onOpenWindow(SvetlanaWindow.CHAT) }, "search_chat")
                        PillButton(Icons.Outlined.Mic, "Голос", { onOpenWindow(SvetlanaWindow.VOICE) }, "search_voice")
                    }
                }
                if (recent.isNotEmpty()) {
                    item { DrawerHeader("Недавние приложения") }
                    item {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                            recent.forEach { app ->
                                AppTile(app, AppMenu({ false }, { false }, {}, {}, {}), { onLaunch(app) }, onWallpaper = false, modifier = Modifier.weight(1f))
                            }
                            repeat(LauncherModel.HOME_COLUMNS - recent.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            } else {
                item {
                    AskRow(Icons.Outlined.Forum, "Спросить Светлану: «${query.trim()}»", "search_ask") { onAsk(query.trim()) }
                }
                if (results.isNotEmpty()) {
                    item { DrawerHeader("Приложения") }
                    items(results, key = { it.packageName }) { app ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onLaunch(app) }
                                .padding(horizontal = 20.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AppIconImage(app.packageName, 40.dp)
                            Spacer(Modifier.width(14.dp))
                            Text(app.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                item {
                    AskRow(Icons.Outlined.Public, "Искать в интернете: «${query.trim()}»", "search_web") { onWeb(query.trim()) }
                }
            }
        }
    }
}

/** Окно «Виджеты»: выбор системных виджетов и список размещённых. */
@Composable
internal fun WidgetPickerWindow(
    placed: List<Int>,
    onPick: (AppWidgetProviderInfo) -> Unit,
    onRemove: (Int) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val manager = remember(context) { AppWidgetManager.getInstance(context) }
    val pm = context.packageManager
    val providers = remember(context) {
        runCatching { manager.installedProviders }.getOrDefault(emptyList())
            .map { it to (runCatching { it.loadLabel(pm) }.getOrNull() ?: it.provider.className) }
            .sortedWith(compareBy({ appLabel(pm, it.first.provider.packageName) }, { it.second }))
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .testTag("window_widgets")
    ) {
        WindowHeader("Виджеты", onClose)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (placed.isNotEmpty()) {
                item { DrawerHeader("На главном экране") }
                items(placed, key = { "placed_$it" }) { id ->
                    val info = remember(id) { runCatching { manager.getAppWidgetInfo(id) }.getOrNull() }
                    SettingsRow(
                        title = info?.let { runCatching { it.loadLabel(pm) }.getOrNull() } ?: "Виджет",
                        subtitle = info?.let { appLabel(pm, it.provider.packageName) } ?: "",
                        actionLabel = "Удалить",
                        tag = "widget_remove",
                        onClick = { onRemove(id) }
                    )
                }
                item { HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            }
            if (providers.isEmpty()) {
                item {
                    Text(
                        "На телефоне нет приложений с виджетами.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(20.dp)
                    )
                }
            } else {
                item { DrawerHeader("Добавить на главный экран") }
                items(providers, key = { it.first.provider.flattenToString() }) { (info, label) ->
                    val density = context.resources.displayMetrics.density
                    val cols = ((info.minWidth / density + 30) / 70).toInt().coerceIn(1, 4)
                    val rows = ((info.minHeight / density + 30) / 70).toInt().coerceIn(1, 4)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(info) }
                            .testTag("widget_provider")
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        WidgetPreview(info)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${appLabel(pm, info.provider.packageName)} · $cols×$rows",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outlineVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun appLabel(pm: android.content.pm.PackageManager, pkg: String): String =
    runCatching { pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString() }.getOrDefault(pkg)

/** Окно «Настройки главного экрана» — как Home settings у Pixel. */
@Composable
internal fun HomeSettingsWindow(
    isHome: Boolean,
    dotsEnabled: Boolean,
    widgetCount: Int,
    actions: LauncherActions,
    onWidgets: () -> Unit,
    onResetLayout: () -> Unit,
    onClose: () -> Unit
) {
    var confirmReset by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .testTag("window_home_settings")
    ) {
        WindowHeader("Главный экран", onClose)
        SettingsRow(
            title = "Главный экран по умолчанию",
            subtitle = if (isHome) "Светлана" else "Сейчас другой launcher",
            actionLabel = if (isHome) null else "Назначить",
            tag = "settings_default_home",
            onClick = actions::requestHomeRole
        )
        SettingsRow(
            title = "Значки уведомлений",
            subtitle = if (dotsEnabled) "Включены" else "Нужен доступ к уведомлениям (видно только, у каких приложений они есть)",
            actionLabel = if (dotsEnabled) "Изменить" else "Включить",
            tag = "settings_dots",
            onClick = actions::notificationAccess
        )
        SettingsRow(
            title = "Виджеты",
            subtitle = if (widgetCount == 0) "Нет виджетов" else "На экране: $widgetCount",
            actionLabel = "Открыть",
            tag = "settings_widgets",
            onClick = onWidgets
        )
        SettingsRow(
            title = "Обои и стиль",
            subtitle = "Системный выбор обоев",
            actionLabel = "Открыть",
            tag = "settings_wallpaper",
            onClick = actions::wallpaper
        )
        SettingsRow(
            title = "Настройки Светланы",
            subtitle = "ИИ, голос, Hands и разрешения",
            actionLabel = "Открыть",
            tag = "settings_svetlana",
            onClick = { actions.open(SettingsActivity::class.java) }
        )
        SettingsRow(
            title = "Сбросить раскладку",
            subtitle = if (confirmReset) "Нажмите ещё раз — иконки и папки вернутся к исходным" else "Иконки и папки по умолчанию",
            actionLabel = if (confirmReset) "Сбросить" else null,
            tag = "settings_reset",
            onClick = { if (confirmReset) onResetLayout() else confirmReset = true }
        )
    }
}

@Composable
private fun WindowHeader(title: String, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        CloseButton(onClose, "Закрыть")
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun SettingsRow(title: String, subtitle: String, actionLabel: String?, tag: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag(tag)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        if (actionLabel != null) {
            Spacer(Modifier.width(12.dp))
            Text(actionLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun WidgetPreview(info: AppWidgetProviderInfo) {
    val context = LocalContext.current
    val preview by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, info.provider) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val d = info.loadPreviewImage(context, 0) ?: return@runCatching null
                val w = d.intrinsicWidth.coerceIn(1, 1024)
                val h = d.intrinsicHeight.coerceIn(1, 1024)
                val scale = 192f / maxOf(w, h)
                d.toBitmap((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1)).asImageBitmap()
            }.getOrNull()
        }
    }
    Box(
        Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        val bmp = preview
        if (bmp != null) {
            androidx.compose.foundation.Image(bmp, contentDescription = null, modifier = Modifier.size(56.dp))
        } else {
            AppIconImage(info.provider.packageName, 36.dp)
        }
    }
}

package com.svetlana.home.ui.launcher

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.svetlana.home.apps.AppModel
import com.svetlana.home.core.ServiceLocator
import com.svetlana.home.ui.settings.SettingsActivity
import com.svetlana.home.voice.VoiceAssistantService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Дополнительные окна Светланы поверх рабочего стола. */
enum class SvetlanaWindow { CHAT, VOICE, SEARCH }

internal val WallpaperText = Color.White
internal val WallpaperShadow = Shadow(Color.Black.copy(alpha = 0.55f), Offset(0f, 2f), 6f)

/**
 * Рабочий стол в стиле стандартного Android launcher:
 * обои системы, виджет часов, сетка иконок, док, строка поиска,
 * свайп вверх — все приложения, свайп вниз — шторка, долгое нажатие —
 * меню рабочего стола, слева — панель Светланы (как экран −1 у Pixel).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LauncherScreen(homeViewModel: HomeViewModel, homeSignal: Int) {
    val context = LocalContext.current
    val actions = remember(context) { LauncherActions(context) }
    val uiState by homeViewModel.state.collectAsState()
    val chatViewModel: ChatViewModel = viewModel()
    val allApps by ServiceLocator.appRegistry.apps.collectAsState()

    val roles = remember(context) { DefaultApps.resolve(context) }
    val apps = remember(allApps) { LauncherModel.launchable(allApps, context.packageName) }
    val dock = remember(apps, roles) { LauncherModel.dock(apps, roles) }
    val grid = remember(apps, dock) { LauncherModel.homeGrid(apps, dock) }

    var drawerOpen by rememberSaveable { mutableStateOf(false) }
    var window by rememberSaveable { mutableStateOf<SvetlanaWindow?>(null) }
    var homeMenu by remember { mutableStateOf(false) }
    val pager = rememberPagerState(initialPage = 1) { 2 }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        while (true) {
            homeViewModel.refreshClock(context)
            delay(15_000)
        }
    }
    LaunchedEffect(Unit) {
        homeViewModel.refreshAvatarLevel()
        homeViewModel.refreshBackendLabel()
    }
    LaunchedEffect(Unit) {
        VoiceAssistantService.startIfEnabled(context)
        homeViewModel.observeDialogueEvents()
    }
    LaunchedEffect(Unit) {
        if (ServiceLocator.appRegistry.apps.value.isEmpty()) ServiceLocator.appRegistry.scanAsync()
    }
    // Кнопка «Домой» при открытом launcher: закрыть всё и вернуться на рабочий стол.
    LaunchedEffect(homeSignal) {
        if (homeSignal > 0) {
            window = null
            drawerOpen = false
            homeMenu = false
            pager.animateScrollToPage(1)
        }
    }

    BackHandler(enabled = window != null || drawerOpen || homeMenu || pager.currentPage != 1) {
        when {
            window != null -> window = null
            homeMenu -> homeMenu = false
            drawerOpen -> drawerOpen = false
            else -> scope.launch { pager.animateScrollToPage(1) }
        }
    }

    val pinActions = remember(grid) {
        fun onHome(app: AppModel): Boolean =
            if (grid.suggested) grid.apps.any { it.packageName == app.packageName } else app.isFavorite
        AppMenuActions(
            isPinned = ::onHome,
            togglePin = { app ->
                val repo = ServiceLocator.appRepository
                if (onHome(app)) {
                    if (grid.suggested) {
                        // Подсказки превращаются в настоящие закрепления — без убранного приложения.
                        LauncherModel.pinnedAfterUnpin(grid, app.packageName).forEach { repo.setFavorite(it, true) }
                    } else {
                        repo.setFavorite(app.packageName, false)
                    }
                } else {
                    LauncherModel.pinnedAfterPin(grid, app.packageName).forEach { repo.setFavorite(it, true) }
                }
            },
            info = actions::appInfo,
            uninstall = actions::uninstall
        )
    }

    val density = LocalDensity.current
    val swipePx = with(density) { 72.dp.toPx() }

    Box(Modifier.fillMaxSize().testTag("launcher")) {
        // Затемнение сверху и снизу, чтобы белый текст читался на любых обоях.
        Box(
            Modifier.fillMaxWidth().height(220.dp).background(
                Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.38f), Color.Transparent))
            )
        )
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(300.dp).background(
                Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f)))
            )
        )

        Column(Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pager,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                beyondBoundsPageCount = 1
            ) { page ->
                if (page == 0) {
                    SvetlanaPanel(
                        uiState = uiState,
                        onOpen = { window = it },
                        onVoice = {
                            window = SvetlanaWindow.VOICE
                            homeViewModel.startListening(context)
                        },
                        onDrawer = { drawerOpen = true },
                        actions = actions
                    )
                } else {
                    Workspace(
                        clock = uiState.clock,
                        lastReply = uiState.lastReply,
                        grid = grid,
                        menu = pinActions,
                        onLaunch = actions::launch,
                        onOpenSvetlana = { window = SvetlanaWindow.CHAT },
                        onSwipeUp = { drawerOpen = true },
                        onSwipeDown = actions::expandNotifications,
                        onLongPress = { homeMenu = true },
                        onRequestHome = actions::requestHomeRole,
                        swipePx = swipePx
                    )
                }
            }

            // Док, точки страниц и строка поиска исчезают на панели Светланы.
            Column(
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = (pager.currentPage + pager.currentPageOffsetFraction).coerceIn(0f, 1f)
                    }
                    .navigationBarsPadding()
                    .padding(bottom = 10.dp)
                    .pointerInput(swipePx) {
                        var total = 0f
                        detectVerticalDragGestures(
                            onDragStart = { total = 0f },
                            onDragEnd = { if (total < -swipePx) drawerOpen = true },
                            onVerticalDrag = { _, dy -> total += dy }
                        )
                    }
            ) {
                PageDots(count = 2, current = pager.currentPage)
                Spacer(Modifier.height(10.dp))
                Dock(dock, pinActions, actions::launch)
                Spacer(Modifier.height(12.dp))
                SearchPill(
                    onSearch = { window = SvetlanaWindow.SEARCH },
                    onMic = {
                        window = SvetlanaWindow.VOICE
                        homeViewModel.startListening(context)
                    },
                    onSvetlana = { window = SvetlanaWindow.CHAT }
                )
            }
        }

        AnimatedVisibility(
            visible = drawerOpen,
            enter = slideInVertically(tween(260)) { it / 3 } + fadeIn(tween(200)),
            exit = slideOutVertically(tween(220)) { it / 3 } + fadeOut(tween(180))
        ) {
            AppDrawer(
                apps = apps,
                menu = pinActions,
                onLaunch = { app ->
                    actions.launch(app)
                    drawerOpen = false
                },
                onAskSvetlana = { q ->
                    drawerOpen = false
                    chatViewModel.handleInput(context, q)
                    window = SvetlanaWindow.CHAT
                },
                onClose = { drawerOpen = false }
            )
        }

        AnimatedVisibility(visible = homeMenu, enter = fadeIn(), exit = fadeOut()) {
            HomeMenu(
                onDismiss = { homeMenu = false },
                onWallpaper = { homeMenu = false; actions.wallpaper() },
                onSettings = { homeMenu = false; actions.open(SettingsActivity::class.java) },
                onApps = { homeMenu = false; drawerOpen = true }
            )
        }

        AnimatedContent(
            targetState = window,
            transitionSpec = {
                (fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 12 }) togetherWith
                    fadeOut(tween(160))
            },
            label = "svetlana_window"
        ) { target ->
            when (target) {
                SvetlanaWindow.CHAT -> SvetlanaWindowFrame(tag = "window_chat", onClose = { window = null }) {
                    ChatScreen(viewModel = chatViewModel)
                }
                SvetlanaWindow.VOICE -> SvetlanaWindowFrame(tag = "window_voice", onClose = {
                    homeViewModel.stopListening()
                    window = null
                }) {
                    VoiceWindowContent(homeViewModel, uiState)
                }
                SvetlanaWindow.SEARCH -> SearchWindow(
                    apps = apps,
                    onClose = { window = null },
                    onLaunch = { app ->
                        window = null
                        actions.launch(app)
                    },
                    onAsk = { q ->
                        chatViewModel.handleInput(context, q)
                        window = SvetlanaWindow.CHAT
                    },
                    onWeb = { q ->
                        window = null
                        actions.webSearch(q)
                    },
                    onOpenWindow = { window = it }
                )
                null -> Box(Modifier)
            }
        }
    }
}

/** Действия контекстного меню иконки (долгое нажатие). */
internal class AppMenuActions(
    val isPinned: (AppModel) -> Boolean,
    val togglePin: (AppModel) -> Unit,
    val info: (AppModel) -> Unit,
    val uninstall: (AppModel) -> Unit
)

@Composable
private fun Workspace(
    clock: String,
    lastReply: String,
    grid: HomeGrid,
    menu: AppMenuActions,
    onLaunch: (AppModel) -> Unit,
    onOpenSvetlana: () -> Unit,
    onSwipeUp: () -> Unit,
    onSwipeDown: () -> Unit,
    onLongPress: () -> Unit,
    onRequestHome: () -> Unit,
    swipePx: Float
) {
    val haptic = LocalHapticFeedback.current
    Column(
        Modifier
            .fillMaxSize()
            .testTag("workspace")
            .pointerInput(swipePx) {
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = {
                        when {
                            total < -swipePx -> onSwipeUp()
                            total > swipePx -> onSwipeDown()
                        }
                    },
                    onVerticalDrag = { _, dy -> total += dy }
                )
            }
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongPress()
                })
            }
            .statusBarsPadding()
    ) {
        ClockWidget(clock = clock, lastReply = lastReply, onOpenSvetlana = onOpenSvetlana)
        DefaultHomeHint(onRequestHome)
        Spacer(Modifier.weight(1f))
        HomeGridView(grid, menu, onLaunch)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ClockWidget(clock: String, lastReply: String, onOpenSvetlana: () -> Unit) {
    val now = clock.ifBlank { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()) }
    val date = remember(clock) {
        SimpleDateFormat("EEEE, d MMMM", Locale("ru")).format(Date())
            .replaceFirstChar { it.uppercase() }
    }
    Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 28.dp)) {
        Text(
            text = now,
            style = TextStyle(
                fontSize = 68.sp, fontWeight = FontWeight.Light, color = WallpaperText,
                shadow = WallpaperShadow, letterSpacing = (-1).sp
            ),
            modifier = Modifier.testTag("clock")
        )
        Text(
            text = date,
            style = TextStyle(fontSize = 17.sp, color = WallpaperText, shadow = WallpaperShadow)
        )
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(Color.Black.copy(alpha = 0.30f))
                .clickable(onClick = onOpenSvetlana)
                .semantics { contentDescription = "Открыть чат со Светланой" }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MiniOrb(18.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Светлана: $lastReply",
                style = TextStyle(fontSize = 14.sp, color = WallpaperText),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun DefaultHomeHint(onRequestHome: () -> Unit) {
    val pm = remember { ServiceLocator.permissionManager }
    val lifecycleOwner = LocalLifecycleOwner.current
    var isHome by remember { mutableStateOf(pm.isHomeLauncher()) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) isHome = pm.isHomeLauncher()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    if (isHome) return
    Row(
        Modifier
            .padding(start = 24.dp, end = 24.dp, top = 10.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.92f))
            .clickable(onClick = onRequestHome)
            .testTag("make_default_home")
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.Home, contentDescription = null, tint = Color(0xFF1E8F6B), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text = "Сделать главным экраном",
            style = TextStyle(fontSize = 14.sp, color = Color(0xFF12201A), fontWeight = FontWeight.Medium)
        )
    }
}

@Composable
private fun HomeGridView(grid: HomeGrid, menu: AppMenuActions, onLaunch: (AppModel) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("home_grid")) {
        grid.apps.chunked(LauncherModel.HOME_COLUMNS).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { app ->
                    AppTile(
                        app = app, menu = menu, onLaunch = { onLaunch(app) },
                        onWallpaper = true, modifier = Modifier.weight(1f)
                    )
                }
                repeat(LauncherModel.HOME_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun PageDots(count: Int, current: Int) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(count) { i ->
            Box(
                Modifier
                    .padding(horizontal = 4.dp)
                    .size(if (i == current) 8.dp else 6.dp)
                    .background(WallpaperText.copy(alpha = if (i == current) 0.95f else 0.45f), CircleShape)
            )
        }
    }
}

@Composable
private fun Dock(dock: List<AppModel>, menu: AppMenuActions, onLaunch: (AppModel) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Color.White.copy(alpha = 0.14f))
            .padding(vertical = 8.dp)
            .testTag("dock"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        dock.forEach { app ->
            AppTile(
                app = app, menu = menu, onLaunch = { onLaunch(app) },
                onWallpaper = true, showLabel = false, modifier = Modifier.weight(1f)
            )
        }
        repeat(LauncherModel.DOCK_SIZE - dock.size) { Spacer(Modifier.weight(1f)) }
    }
}

@Composable
private fun SearchPill(onSearch: () -> Unit, onMic: () -> Unit, onSvetlana: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(54.dp)
            .clip(RoundedCornerShape(27.dp))
            .background(Color.White.copy(alpha = 0.95f))
            .clickable(onClick = onSearch)
            .testTag("search_pill")
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(42.dp)
                .clip(CircleShape)
                .clickable(onClick = onSvetlana)
                .semantics { contentDescription = "Чат со Светланой" },
            contentAlignment = Alignment.Center
        ) { MiniOrb(26.dp) }
        Text(
            text = "Поиск или вопрос Светлане",
            style = TextStyle(fontSize = 16.sp, color = Color(0xFF5F6368)),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 6.dp)
        )
        IconButton(onClick = onMic, modifier = Modifier.testTag("search_mic")) {
            Icon(Icons.Outlined.Mic, contentDescription = "Голосовой ввод", tint = Color(0xFF1E8F6B))
        }
    }
}

/** Иконка приложения с подписью и контекстным меню по долгому нажатию. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AppTile(
    app: AppModel,
    menu: AppMenuActions,
    onLaunch: () -> Unit,
    onWallpaper: Boolean,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true
) {
    var menuOpen by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    Box(modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .combinedClickable(
                    onClick = onLaunch,
                    onLongClickLabel = "Действия с приложением",
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuOpen = true
                    }
                )
                .semantics { contentDescription = app.label }
                .padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AppIconImage(app.packageName, 54.dp)
            if (showLabel) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = app.label,
                    style = if (onWallpaper)
                        TextStyle(fontSize = 12.sp, color = WallpaperText, shadow = WallpaperShadow, textAlign = TextAlign.Center)
                    else
                        TextStyle(fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(if (menu.isPinned(app)) "Убрать с главного экрана" else "На главный экран") },
                leadingIcon = { Icon(Icons.Outlined.PushPin, contentDescription = null) },
                onClick = { menuOpen = false; menu.togglePin(app) }
            )
            DropdownMenuItem(
                text = { Text("О приложении") },
                leadingIcon = { Icon(Icons.Outlined.Info, contentDescription = null) },
                onClick = { menuOpen = false; menu.info(app) }
            )
            if (!app.systemApp) {
                DropdownMenuItem(
                    text = { Text("Удалить") },
                    leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                    onClick = { menuOpen = false; menu.uninstall(app) }
                )
            }
        }
    }
}

@Composable
private fun HomeMenu(
    onDismiss: () -> Unit,
    onWallpaper: () -> Unit,
    onSettings: () -> Unit,
    onApps: () -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            )
            .testTag("home_menu"),
        contentAlignment = Alignment.BottomCenter
    ) {
        Row(
            Modifier
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 120.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            HomeMenuItem(Icons.Outlined.Wallpaper, "Обои и стиль", onWallpaper)
            HomeMenuItem(Icons.Outlined.Apps, "Приложения", onApps)
            HomeMenuItem(Icons.Outlined.Settings, "Настройки", onSettings)
        }
    }
}

@Composable
private fun HomeMenuItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

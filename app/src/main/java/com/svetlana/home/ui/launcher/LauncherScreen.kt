package com.svetlana.home.ui.launcher

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material.icons.outlined.UnfoldLess
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.foundation.Image
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.svetlana.home.apps.AppModel
import com.svetlana.home.core.ServiceLocator
import com.svetlana.home.voice.VoiceAssistantService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Дополнительные окна Светланы и launcher поверх рабочего стола. */
enum class SvetlanaWindow { CHAT, VOICE, SEARCH, WIDGETS, HOME_SETTINGS }

internal val WallpaperText = Color.White
internal val WallpaperShadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 2f), 6f)
private val DotColor = Color(0xFFEF6C3A)

/** Действия меню иконки (долгое нажатие). */
internal class AppMenu(
    val onHome: (AppModel) -> Boolean,
    val canPin: (AppModel) -> Boolean,
    val togglePin: (AppModel) -> Unit,
    val info: (AppModel) -> Unit,
    val uninstall: (AppModel) -> Unit,
    val folders: () -> List<HomeItem.Folder> = { emptyList() },
    val addToFolder: ((AppModel, String?) -> Unit)? = null,
    val removeFromFolder: ((AppModel) -> Unit)? = null
) {
    fun forFolder(remove: (AppModel) -> Unit) = AppMenu(
        onHome = onHome, canPin = { false }, togglePin = togglePin, info = info,
        uninstall = uninstall, removeFromFolder = remove
    )
}

private class DragState(val key: String, val pkg: String?, val pos: Offset)

private class PosHolder { var value: Offset = Offset.Zero }

/** Счётчик возвратов на экран: всё, что зависит от системных настроек, перечитываем на ON_RESUME. */
@Composable
internal fun rememberResumeTick(): Int {
    val owner = LocalLifecycleOwner.current
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return tick
}

/**
 * Рабочий стол в стиле стандартного Android launcher: системные обои, часы,
 * виджеты, страницы иконок и папок, док, строка поиска. Свайп вверх — все
 * приложения, вниз — шторка, долгое нажатие — меню, перетаскивание иконок —
 * перенос и папки. Слева — панель Светланы (экран −1), её функции — в окнах.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LauncherScreen(homeViewModel: HomeViewModel, homeSignal: Int) {
    val context = LocalContext.current
    HomeLayoutStore.init(context)
    val actions = remember(context) { LauncherActions(context) }
    val uiState by homeViewModel.state.collectAsState()
    val chatViewModel: ChatViewModel = viewModel()
    val allApps by ServiceLocator.appRegistry.apps.collectAsState()
    val layout by HomeLayoutStore.layout.collectAsState()
    val dots by NotificationDots.counts.collectAsState()
    val resumeTick = rememberResumeTick()

    val roles = remember(context, resumeTick) { DefaultApps.resolve(context) }
    val apps = remember(allApps) { LauncherModel.launchable(allApps, context.packageName) }
    val appsByPkg = remember(apps) { apps.associateBy { it.packageName } }
    val dock = remember(apps, roles) { LauncherModel.dock(apps, roles) }
    val dockPkgs = remember(dock) { dock.map { it.packageName }.toSet() }
    val entries = remember(layout, appsByPkg) { HomeLayoutOps.visibleEntries(layout.items, appsByPkg.keys) }
    val widgetManager = remember(context) { AppWidgetManager.getInstance(context) }
    val widgets = remember(layout.widgets, resumeTick) {
        layout.widgets.filter { runCatching { widgetManager.getAppWidgetInfo(it) }.getOrNull() != null }
    }
    val pages = remember(entries, widgets) { HomeLayoutOps.pages(entries.size, widgets.isNotEmpty()) }
    val isHome = remember(resumeTick) { ServiceLocator.permissionManager.isHomeLauncher() }

    var drawerOpen by rememberSaveable { mutableStateOf(false) }
    var window by rememberSaveable { mutableStateOf<SvetlanaWindow?>(null) }
    var homeMenu by remember { mutableStateOf(false) }
    var openFolderId by rememberSaveable { mutableStateOf<String?>(null) }
    var drag by remember { mutableStateOf<DragState?>(null) }
    val cells = remember { HashMap<String, Rect>() }
    val pageCount by rememberUpdatedState(1 + pages.size)
    val pager = rememberPagerState(initialPage = 1) { pageCount }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val swipePx = with(density) { 72.dp.toPx() }
    val removeZonePx = with(density) { 120.dp.toPx() }
    val iconCenterPx = with(density) { 33.dp.toPx() }
    val iconRadiusPx = with(density) { 26.dp.toPx() }
    val configuration = LocalConfiguration.current
    val compact = configuration.screenHeightDp < 700
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val edgePx = with(density) { 36.dp.toPx() }

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
        if (ServiceLocator.appRegistry.apps.first().isEmpty()) ServiceLocator.appRegistry.scanAsync()
    }
    // Первый запуск: создаём раскладку один раз — закреплённые раньше или подсказки.
    LaunchedEffect(apps, layout.initialized) {
        if (!layout.initialized && apps.isNotEmpty()) {
            val favorites = apps.filter { it.isFavorite }.map { it.packageName }
            val suggestions = LauncherModel.ranked(apps).map { it.packageName }
                .filter { it !in dockPkgs }.take(LauncherModel.HOME_COLUMNS * 2)
            HomeLayoutStore.update {
                if (it.initialized) it
                else HomeLayoutOps.initial(favorites, suggestions, dockPkgs)
                    .copy(widgets = it.widgets, coachmarkShown = it.coachmarkShown)
            }
        }
    }
    LaunchedEffect(homeSignal) {
        if (homeSignal > 0) {
            window = null
            drawerOpen = false
            homeMenu = false
            openFolderId = null
            pager.animateScrollToPage(1)
        }
    }
    LaunchedEffect(pager.currentPage) {
        if (pager.currentPage == 0) HomeLayoutStore.update { it.copy(coachmarkShown = true) }
    }
    // Подсказка про панель Светланы показывается один раз и прячется сама.
    LaunchedEffect(layout.initialized, layout.coachmarkShown) {
        if (layout.initialized && !layout.coachmarkShown) {
            delay(8_000)
            HomeLayoutStore.update { it.copy(coachmarkShown = true) }
        }
    }
    // Перетаскивание к краю экрана листает страницы рабочего стола (не на панель Светланы).
    LaunchedEffect(drag != null) {
        var heldSince = 0L
        while (drag != null) {
            val x = drag?.pos?.x ?: break
            val dir = when {
                x < edgePx -> -1
                x > screenWidthPx - edgePx -> 1
                else -> 0
            }
            if (dir == 0) {
                heldSince = 0L
            } else if (heldSince == 0L) {
                heldSince = System.currentTimeMillis()
            } else if (System.currentTimeMillis() - heldSince > 600) {
                val target = (pager.currentPage + dir).coerceIn(1, pages.size)
                if (target != pager.currentPage) pager.animateScrollToPage(target)
                heldSince = 0L
            }
            delay(100)
        }
    }

    BackHandler(enabled = window != null || drawerOpen || homeMenu || openFolderId != null || pager.currentPage != 1) {
        when {
            window != null -> window = null
            openFolderId != null -> openFolderId = null
            homeMenu -> homeMenu = false
            drawerOpen -> drawerOpen = false
            else -> scope.launch { pager.animateScrollToPage(1) }
        }
    }

    val menu = remember(dockPkgs, layout) {
        val repo = ServiceLocator.appRepository
        AppMenu(
            onHome = { app -> app.packageName in layout.pinnedPackages },
            canPin = { app -> app.packageName !in dockPkgs },
            togglePin = { app ->
                val pkg = app.packageName
                if (pkg in layout.pinnedPackages) {
                    HomeLayoutStore.update { HomeLayoutOps.unpin(it, pkg) }
                    repo.setFavorite(pkg, false)
                } else {
                    HomeLayoutStore.update { HomeLayoutOps.pin(it, pkg) }
                    repo.setFavorite(pkg, true)
                }
            },
            info = actions::appInfo,
            uninstall = actions::uninstall,
            folders = { layout.folders },
            addToFolder = { app, folderId ->
                HomeLayoutStore.update { HomeLayoutOps.addToFolder(it, app.packageName, folderId, HomeLayoutStore.newId()) }
                repo.setFavorite(app.packageName, true)
            }
        )
    }

    fun dragFor(entry: IndexedValue<HomeItem>) = TileDrag(
        onStart = { pos -> drag = DragState(entry.value.key, (entry.value as? HomeItem.App)?.pkg, pos) },
        onMove = { delta -> drag?.let { drag = DragState(it.key, it.pkg, it.pos + delta) } },
        onEnd = {
            val d = drag
            drag = null
            val from = d?.let { st -> entries.firstOrNull { it.value.key == st.key } }
            if (d != null && from != null) {
                if (d.pos.y < removeZonePx) {
                    when (val item = from.value) {
                        is HomeItem.App -> {
                            HomeLayoutStore.update { HomeLayoutOps.unpin(it, item.pkg) }
                            ServiceLocator.appRepository.setFavorite(item.pkg, false)
                        }
                        is HomeItem.Folder -> HomeLayoutStore.update { HomeLayoutOps.removeAt(it, from.index) }
                    }
                } else {
                    // Цель ищем только среди иконок текущей страницы.
                    val pageEntries = pages.getOrElse(pager.currentPage - 1) { IntRange.EMPTY }.map { entries[it] }
                    val target = pageEntries.firstOrNull { e ->
                        e.value.key != d.key && cells[e.value.key]?.contains(d.pos) == true
                    }
                    if (target != null) {
                        val r = cells.getValue(target.value.key)
                        val iconCenter = Offset(r.center.x, r.top + iconCenterPx)
                        val onIcon = (d.pos - iconCenter).getDistance() < iconRadiusPx
                        HomeLayoutStore.update {
                            if (onIcon) HomeLayoutOps.dropOnto(it, from.index, target.index, HomeLayoutStore.newId())
                            else HomeLayoutOps.move(it, from.index, target.index)
                        }
                    } else if (pageEntries.none { it.value.key == d.key }) {
                        // Перенесли на другую страницу на свободное место — в конец этой страницы.
                        val to = HomeLayoutOps.endOfPageTarget(from.index, pageEntries.lastOrNull()?.index, layout.items.size)
                        HomeLayoutStore.update { HomeLayoutOps.move(it, from.index, to) }
                    }
                }
            }
        },
        onCancel = { drag = null },
        onPositioned = { rect -> cells[entry.value.key] = rect }
    )

    // Виджеты: системный выбор и настройка (стандартный поток AppWidgetHost).
    var pendingWidgetId by remember { mutableIntStateOf(-1) }
    var pendingWidgetInfo by remember { mutableStateOf<AppWidgetProviderInfo?>(null) }
    fun widgetDone(id: Int, ok: Boolean) {
        if (ok) HomeLayoutStore.update { HomeLayoutOps.addWidget(it, id) }
        else runCatching { LauncherWidgets.host(context).deleteAppWidgetId(id) }
        pendingWidgetId = -1
        pendingWidgetInfo = null
        if (ok) {
            window = null
            scope.launch { pager.animateScrollToPage(1) }
        }
    }
    val configureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (pendingWidgetId != -1) widgetDone(pendingWidgetId, res.resultCode == Activity.RESULT_OK)
    }
    fun configureOrAdd(id: Int, info: AppWidgetProviderInfo) {
        val configure = info.configure
        if (configure == null) {
            widgetDone(id, true)
            return
        }
        val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
            .setComponent(configure)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        if (runCatching { configureLauncher.launch(intent) }.isFailure) widgetDone(id, false)
    }
    val bindLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val id = pendingWidgetId
        val info = pendingWidgetInfo
        if (id != -1 && info != null && res.resultCode == Activity.RESULT_OK) configureOrAdd(id, info)
        else if (id != -1) widgetDone(id, false)
    }
    val pickWidget: (AppWidgetProviderInfo) -> Unit = { info ->
        val host = LauncherWidgets.host(context)
        val id = host.allocateAppWidgetId()
        pendingWidgetId = id
        pendingWidgetInfo = info
        val allowed = runCatching { widgetManager.bindAppWidgetIdIfAllowed(id, info.provider) }.getOrDefault(false)
        if (allowed) {
            configureOrAdd(id, info)
        } else {
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
            if (runCatching { bindLauncher.launch(intent) }.isFailure) widgetDone(id, false)
        }
    }
    val removeWidget: (Int) -> Unit = { id ->
        HomeLayoutStore.update { HomeLayoutOps.removeWidget(it, id) }
        runCatching { LauncherWidgets.host(context).deleteAppWidgetId(id) }
    }

    CompositionLocalProvider(LocalNotificationDots provides dots) {
        Box(Modifier.fillMaxSize().testTag("launcher")) {
            // Мягкое затемнение сверху и снизу: подписи читаются на любых обоях.
            Box(
                Modifier.fillMaxWidth().height(240.dp).background(
                    Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.40f), Color.Transparent))
                )
            )
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(420.dp).background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.52f)))
                )
            )

            Column(Modifier.fillMaxSize()) {
                HorizontalPager(
                    state = pager,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    beyondBoundsPageCount = 1,
                    userScrollEnabled = drag == null,
                    key = { if (it == 0) "svetlana" else "home_$it" }
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
                        val range = pages.getOrElse(page - 1) { IntRange.EMPTY }
                        val slice = range.map { entries[it] }
                        WorkspacePage(
                            first = page == 1,
                            compact = compact,
                            clock = uiState.clock,
                            lastReply = uiState.lastReply,
                            entries = slice,
                            widgets = if (page == 1) widgets else emptyList(),
                            widgetHeights = layout.widgetHeights,
                            onWidgetResize = { id, current, steps ->
                                HomeLayoutStore.update { HomeLayoutOps.resizeWidget(it, id, current, steps) }
                            },
                            onWidgetRemove = removeWidget,
                            appsByPkg = appsByPkg,
                            menu = menu,
                            isHome = isHome,
                            draggingKey = drag?.key,
                            dragFor = ::dragFor,
                            onLaunch = actions::launch,
                            onOpenFolder = { openFolderId = it },
                            onUngroup = { id -> HomeLayoutStore.update { HomeLayoutOps.ungroup(it, id) } },
                            onOpenSvetlana = { window = SvetlanaWindow.CHAT },
                            onSwipeUp = { drawerOpen = true },
                            onSwipeDown = actions::expandNotifications,
                            onLongPress = { homeMenu = true },
                            onRequestHome = actions::requestHomeRole,
                            swipePx = swipePx
                        )
                    }
                }

                // Док, точки страниц и строка поиска (на панели Светланы плавно исчезают).
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
                    if (!layout.coachmarkShown && layout.initialized) {
                        Coachmark(
                            onClick = {
                                HomeLayoutStore.update { it.copy(coachmarkShown = true) }
                                scope.launch { pager.animateScrollToPage(0) }
                            }
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    PageDots(count = pages.size, current = pager.currentPage - 1)
                    Spacer(Modifier.height(10.dp))
                    Dock(dock, menu, actions::launch)
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

            // Перетаскивание: зона «Убрать» сверху и иконка под пальцем.
            val d = drag
            if (d != null) {
                RemoveZone(active = d.pos.y < removeZonePx)
                Box(
                    Modifier.offset {
                        val half = 30.dp.roundToPx()
                        IntOffset(d.pos.x.roundToInt() - half, d.pos.y.roundToInt() - half)
                    }
                ) {
                    if (d.pkg != null) AppIconImage(d.pkg, 60.dp)
                    else Box(Modifier.size(60.dp).background(Color.White.copy(alpha = 0.5f), RoundedCornerShape(18.dp)))
                }
            }

            val folder = layout.folders.firstOrNull { it.id == openFolderId }
            AnimatedVisibility(visible = folder != null, enter = fadeIn(), exit = fadeOut()) {
                if (folder != null) {
                    FolderPopup(
                        folder = folder,
                        appsByPkg = appsByPkg,
                        menu = menu.forFolder { app ->
                            HomeLayoutStore.update { HomeLayoutOps.removeFromFolder(it, folder.id, app.packageName) }
                        },
                        onLaunch = { app ->
                            openFolderId = null
                            actions.launch(app)
                        },
                        onRename = { name -> HomeLayoutStore.update { HomeLayoutOps.renameFolder(it, folder.id, name) } },
                        onDismiss = { openFolderId = null }
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
                    menu = menu,
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
                    onWidgets = { homeMenu = false; window = SvetlanaWindow.WIDGETS },
                    onSettings = { homeMenu = false; window = SvetlanaWindow.HOME_SETTINGS }
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
                    SvetlanaWindow.WIDGETS -> WidgetPickerWindow(
                        placed = widgets,
                        onPick = pickWidget,
                        onRemove = removeWidget,
                        onClose = { window = null }
                    )
                    SvetlanaWindow.HOME_SETTINGS -> HomeSettingsWindow(
                        isHome = isHome,
                        dotsEnabled = remember(resumeTick) { NotificationDots.isEnabled(context) },
                        widgetCount = widgets.size,
                        actions = actions,
                        onWidgets = { window = SvetlanaWindow.WIDGETS },
                        onResetLayout = {
                            HomeLayoutStore.update { HomeLayout(widgets = it.widgets, coachmarkShown = true) }
                            window = null
                        },
                        onClose = { window = null }
                    )
                    null -> Box(Modifier)
                }
            }
        }
    }
}

@Composable
private fun WorkspacePage(
    first: Boolean,
    compact: Boolean,
    clock: String,
    lastReply: String,
    entries: List<IndexedValue<HomeItem>>,
    widgets: List<Int>,
    widgetHeights: Map<Int, Int>,
    onWidgetResize: (Int, Int, Int) -> Unit,
    onWidgetRemove: (Int) -> Unit,
    appsByPkg: Map<String, AppModel>,
    menu: AppMenu,
    isHome: Boolean,
    draggingKey: String?,
    dragFor: (IndexedValue<HomeItem>) -> TileDrag,
    onLaunch: (AppModel) -> Unit,
    onOpenFolder: (String) -> Unit,
    onUngroup: (String) -> Unit,
    onOpenSvetlana: () -> Unit,
    onSwipeUp: () -> Unit,
    onSwipeDown: () -> Unit,
    onLongPress: () -> Unit,
    onRequestHome: () -> Unit,
    swipePx: Float
) {
    val haptic = LocalHapticFeedback.current
    val latestUp by rememberUpdatedState(onSwipeUp)
    val latestDown by rememberUpdatedState(onSwipeDown)
    val latestLong by rememberUpdatedState(onLongPress)
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
                            total < -swipePx -> latestUp()
                            total > swipePx -> latestDown()
                        }
                    },
                    onVerticalDrag = { _, dy -> total += dy }
                )
            }
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    latestLong()
                })
            }
            .statusBarsPadding()
    ) {
        if (first) {
            ClockWidget(clock = clock, lastReply = lastReply, compact = compact, onOpenSvetlana = onOpenSvetlana)
            if (!isHome) DefaultHomeHint(onRequestHome)
            widgets.forEach { id ->
                Spacer(Modifier.height(12.dp))
                HomeWidget(
                    id = id,
                    heightOverrideDp = widgetHeights[id],
                    onResize = { current, steps -> onWidgetResize(id, current, steps) },
                    onRemove = { onWidgetRemove(id) }
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("home_grid")) {
            entries.chunked(LauncherModel.HOME_COLUMNS).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { entry ->
                        when (val item = entry.value) {
                            is HomeItem.App -> {
                                val app = appsByPkg[item.pkg]
                                if (app != null) {
                                    AppTile(
                                        app = app, menu = menu, onLaunch = { onLaunch(app) },
                                        onWallpaper = true, modifier = Modifier.weight(1f),
                                        drag = dragFor(entry), dragging = draggingKey == item.key
                                    )
                                } else {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                            is HomeItem.Folder -> FolderTile(
                                folder = item, onOpen = { onOpenFolder(item.id) },
                                onUngroup = { onUngroup(item.id) },
                                drag = dragFor(entry), dragging = draggingKey == item.key,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    repeat(LauncherModel.HOME_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                }
                Spacer(Modifier.height(if (compact) 2.dp else 6.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ClockWidget(clock: String, lastReply: String, compact: Boolean, onOpenSvetlana: () -> Unit) {
    val now = clock.ifBlank { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()) }
    val date = remember(clock) {
        SimpleDateFormat("EEEE, d MMMM", Locale("ru")).format(Date())
            .replaceFirstChar { it.uppercase() }
    }
    Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = if (compact) 16.dp else 28.dp)) {
        Text(
            text = now,
            style = TextStyle(
                fontSize = if (compact) 54.sp else 68.sp, fontWeight = FontWeight.Light,
                color = WallpaperText, shadow = WallpaperShadow, letterSpacing = (-1).sp
            ),
            modifier = Modifier.testTag("clock")
        )
        Text(text = date, style = TextStyle(fontSize = 17.sp, color = WallpaperText, shadow = WallpaperShadow))
        Spacer(Modifier.height(12.dp))
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
            text = "Назначить Светлану главным экраном",
            style = TextStyle(fontSize = 14.sp, color = Color(0xFF12201A), fontWeight = FontWeight.Medium)
        )
    }
}

/**
 * Виджет другого приложения (стандартный AppWidgetHostView). Долгое нажатие —
 * меню: выше / ниже / удалить. Высота по сетке 72dp.
 */
@Composable
private fun HomeWidget(id: Int, heightOverrideDp: Int?, onResize: (Int, Int) -> Unit, onRemove: () -> Unit) {
    val context = LocalContext.current
    val info = remember(id) { runCatching { AppWidgetManager.getInstance(context).getAppWidgetInfo(id) }.getOrNull() } ?: return
    val defaultDp = with(LocalDensity.current) { info.minHeight.toDp().value.roundToInt() }
    val heightDp = (heightOverrideDp ?: defaultDp).coerceIn(HomeLayoutOps.WIDGET_MIN_DP, HomeLayoutOps.WIDGET_MAX_DP)
    var menuOpen by remember { mutableStateOf(false) }
    val latestOpen by rememberUpdatedState { menuOpen = true }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .height(heightDp.dp)
            .semantics {
                contentDescription = "Виджет ${runCatching { info.loadLabel(context.packageManager) }.getOrDefault("")}"
                onLongClick(label = "Изменить виджет") { menuOpen = true; true }
            }
            .testTag("home_widget")
    ) {
        AndroidView(
            factory = { ctx ->
                LauncherWidgets.host(ctx).createView(ctx, id, info).also { v ->
                    (v as? LongPressWidgetHostView)?.onLongPress = { latestOpen() }
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (heightDp < HomeLayoutOps.WIDGET_MAX_DP) {
                DropdownMenuItem(
                    text = { Text("Выше") },
                    leadingIcon = { Icon(Icons.Outlined.UnfoldMore, contentDescription = null) },
                    onClick = { menuOpen = false; onResize(heightDp, 1) }
                )
            }
            if (heightDp > HomeLayoutOps.WIDGET_MIN_DP) {
                DropdownMenuItem(
                    text = { Text("Ниже") },
                    leadingIcon = { Icon(Icons.Outlined.UnfoldLess, contentDescription = null) },
                    onClick = { menuOpen = false; onResize(heightDp, -1) }
                )
            }
            DropdownMenuItem(
                text = { Text("Удалить виджет") },
                leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                onClick = { menuOpen = false; onRemove() }
            )
        }
    }
}

@Composable
private fun Coachmark(onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Row(
            Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(onClick = onClick)
                .testTag("coachmark")
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MiniOrb(16.dp)
            Spacer(Modifier.width(8.dp))
            Text("Свайп вправо — функции Светланы", style = TextStyle(fontSize = 13.sp, color = WallpaperText))
        }
    }
}

@Composable
private fun RemoveZone(active: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(top = 12.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        Row(
            Modifier
                .clip(RoundedCornerShape(22.dp))
                .background(if (active) Color(0xFFD9473A) else Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.RemoveCircleOutline, contentDescription = null, tint = Color.White)
            Spacer(Modifier.width(8.dp))
            Text("Убрать", style = TextStyle(fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.Medium))
        }
    }
}

@Composable
private fun PageDots(count: Int, current: Int) {
    if (count <= 1) {
        Spacer(Modifier.height(8.dp))
        return
    }
    Row(
        Modifier.fillMaxWidth().testTag("page_dots"),
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
private fun Dock(dock: List<AppModel>, menu: AppMenu, onLaunch: (AppModel) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Color.White.copy(alpha = 0.16f))
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

/**
 * Иконка приложения: касание — запуск, долгое нажатие — меню,
 * перетаскивание — перенос/папка, значок уведомлений (если включён).
 */
@Composable
internal fun AppTile(
    app: AppModel,
    menu: AppMenu,
    onLaunch: () -> Unit,
    onWallpaper: Boolean,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true,
    drag: TileDrag? = null,
    dragging: Boolean = false
) {
    var menuOpen by remember { mutableStateOf(false) }
    var showFolders by remember { mutableStateOf(false) }
    val origin = remember { PosHolder() }
    val haptic = LocalHapticFeedback.current
    val dots = LocalNotificationDots.current[app.packageName] ?: 0
    val latestLaunch by rememberUpdatedState(onLaunch)
    val latestDrag by rememberUpdatedState(drag)
    val stableDrag = remember {
        TileDrag(
            onStart = { p -> menuOpen = false; latestDrag?.onStart?.invoke(p) },
            onMove = { latestDrag?.onMove?.invoke(it) },
            onEnd = { latestDrag?.onEnd?.invoke() },
            onCancel = { latestDrag?.onCancel?.invoke() },
            onPositioned = { latestDrag?.onPositioned?.invoke(it) }
        )
    }
    val context = LocalContext.current
    val openMenu = {
        if (drag == null) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        showFolders = false
        menuOpen = true
    }
    // Ярлыки приложения (App shortcuts) — только когда меню открыто.
    val shortcuts = remember(menuOpen, app.packageName) {
        if (menuOpen) AppShortcuts.query(context, app.packageName) else emptyList()
    }
    Box(modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .onGloballyPositioned { c ->
                    val pos = c.positionInRoot()
                    origin.value = pos
                    if (drag != null) stableDrag.onPositioned(Rect(pos, c.size.toSize()))
                }
                .alpha(if (dragging) 0.25f else 1f)
                .clip(RoundedCornerShape(18.dp))
                .launcherTileGestures(
                    key = app.packageName,
                    onTap = { latestLaunch() },
                    onLongPress = openMenu,
                    drag = if (drag != null) stableDrag else null,
                    origin = { origin.value },
                    onHold = { if (drag != null) haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
                )
                .semantics {
                    contentDescription = if (dots > 0) "${app.label}, уведомлений: $dots" else app.label
                    onClick(label = "Открыть") { latestLaunch(); true }
                    onLongClick(label = "Действия с приложением") { openMenu(); true }
                }
                .padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box {
                AppIconImage(app.packageName, 54.dp)
                if (dots > 0) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .size(13.dp)
                            .background(Color.White, CircleShape)
                            .padding(2.dp)
                            .background(DotColor, CircleShape)
                            .testTag("notification_dot")
                    )
                }
            }
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
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false; showFolders = false }) {
            if (!showFolders) {
                shortcuts.forEach { sc ->
                    DropdownMenuItem(
                        text = { Text(sc.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = {
                            val bmp = remember(sc.id) { AppShortcuts.icon(context, sc) }
                            if (bmp != null) Image(bmp, contentDescription = null, modifier = Modifier.size(24.dp))
                        },
                        onClick = { menuOpen = false; AppShortcuts.start(context, sc) },
                        modifier = Modifier.testTag("app_shortcut")
                    )
                }
                if (shortcuts.isNotEmpty()) androidx.compose.material3.HorizontalDivider()
                if (menu.canPin(app)) {
                    DropdownMenuItem(
                        text = { Text(if (menu.onHome(app)) "Убрать с главного экрана" else "На главный экран") },
                        leadingIcon = { Icon(Icons.Outlined.PushPin, contentDescription = null) },
                        onClick = { menuOpen = false; menu.togglePin(app) }
                    )
                    if (menu.addToFolder != null) {
                        DropdownMenuItem(
                            text = { Text("В папку…") },
                            leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, contentDescription = null) },
                            trailingIcon = { Icon(Icons.Outlined.ChevronRight, contentDescription = null) },
                            onClick = { showFolders = true }
                        )
                    }
                }
                val remove = menu.removeFromFolder
                if (remove != null) {
                    DropdownMenuItem(
                        text = { Text("Убрать из папки") },
                        leadingIcon = { Icon(Icons.Outlined.RemoveCircleOutline, contentDescription = null) },
                        onClick = { menuOpen = false; remove(app) }
                    )
                }
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
            } else {
                val add = menu.addToFolder
                DropdownMenuItem(
                    text = { Text("Новая папка") },
                    leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, contentDescription = null) },
                    onClick = { menuOpen = false; add?.invoke(app, null) }
                )
                menu.folders().forEach { f ->
                    DropdownMenuItem(
                        text = { Text(f.name) },
                        onClick = { menuOpen = false; add?.invoke(app, f.id) }
                    )
                }
            }
        }
    }
}

/** Папка на рабочем столе: превью 2×2 и название, как в Pixel Launcher. */
@Composable
private fun FolderTile(
    folder: HomeItem.Folder,
    onOpen: () -> Unit,
    onUngroup: () -> Unit,
    drag: TileDrag,
    dragging: Boolean,
    modifier: Modifier = Modifier
) {
    val origin = remember { PosHolder() }
    val haptic = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }
    val latestOpen by rememberUpdatedState(onOpen)
    val latestDrag by rememberUpdatedState(drag)
    val stableDrag = remember {
        TileDrag(
            onStart = { latestDrag.onStart(it) },
            onMove = { latestDrag.onMove(it) },
            onEnd = { latestDrag.onEnd() },
            onCancel = { latestDrag.onCancel() },
            onPositioned = { latestDrag.onPositioned(it) }
        )
    }
    Box(modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .onGloballyPositioned { c ->
                    val pos = c.positionInRoot()
                    origin.value = pos
                    stableDrag.onPositioned(Rect(pos, c.size.toSize()))
                }
                .alpha(if (dragging) 0.25f else 1f)
                .clip(RoundedCornerShape(18.dp))
                .launcherTileGestures(
                    key = folder.key,
                    onTap = { latestOpen() },
                    onLongPress = { menuOpen = true },
                    drag = stableDrag,
                    origin = { origin.value },
                    onHold = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
                )
                .semantics {
                    contentDescription = "Папка ${folder.name}, приложений: ${folder.apps.size}"
                    onClick(label = "Открыть папку") { latestOpen(); true }
                    onLongClick(label = "Действия с папкой") { menuOpen = true; true }
                }
                .testTag("home_folder")
                .padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.32f))
                    .padding(7.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    folder.apps.take(4).chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            row.forEach { AppIconImage(it, 18.dp) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = folder.name,
                style = TextStyle(fontSize = 12.sp, color = WallpaperText, shadow = WallpaperShadow, textAlign = TextAlign.Center),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Открыть и переименовать") },
                leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                onClick = { menuOpen = false; onOpen() }
            )
            DropdownMenuItem(
                text = { Text("Расформировать папку") },
                leadingIcon = { Icon(Icons.Outlined.FolderOff, contentDescription = null) },
                onClick = { menuOpen = false; onUngroup() }
            )
        }
    }
}

@Composable
private fun FolderPopup(
    folder: HomeItem.Folder,
    appsByPkg: Map<String, AppModel>,
    menu: AppMenu,
    onLaunch: (AppModel) -> Unit,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember(folder.id) { mutableStateOf(folder.name) }
    val apps = folder.apps.mapNotNull { appsByPkg[it] }
    val focusManager = LocalFocusManager.current
    // Имя сохраняем очищенным: без пробелов по краям и не пустым.
    DisposableEffect(folder.id) {
        onDispose { onRename(HomeLayoutOps.cleanFolderName(name)) }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            .testTag("folder_popup"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .padding(horizontal = 24.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surface)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                .padding(16.dp)
        ) {
            BasicTextField(
                value = name,
                onValueChange = {
                    name = it.take(30)
                    onRename(name)
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    name = HomeLayoutOps.cleanFolderName(name)
                    onRename(name)
                    focusManager.clearFocus()
                }),
                textStyle = MaterialTheme.typography.titleLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .semantics { contentDescription = "Название папки" }
                    .testTag("folder_name")
            )
            Spacer(Modifier.height(8.dp))
            apps.chunked(LauncherModel.HOME_COLUMNS).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { app ->
                        AppTile(app, menu, { onLaunch(app) }, onWallpaper = false, modifier = Modifier.weight(1f))
                    }
                    repeat(LauncherModel.HOME_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun HomeMenu(
    onDismiss: () -> Unit,
    onWallpaper: () -> Unit,
    onWidgets: () -> Unit,
    onSettings: () -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            .testTag("home_menu"),
        contentAlignment = Alignment.BottomCenter
    ) {
        Row(
            Modifier
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 132.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            HomeMenuItem(Icons.Outlined.Wallpaper, "Обои и стиль", "menu_wallpaper", onWallpaper)
            HomeMenuItem(Icons.Outlined.Widgets, "Виджеты", "menu_widgets", onWidgets)
            HomeMenuItem(Icons.Outlined.Settings, "Настройки", "menu_settings", onSettings)
        }
    }
}

@Composable
private fun HomeMenuItem(icon: ImageVector, label: String, tag: String, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .testTag(tag)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
internal fun CloseButton(onClose: () -> Unit, description: String) {
    IconButton(onClick = onClose, modifier = Modifier.testTag("window_close")) {
        Icon(Icons.Outlined.Close, contentDescription = description, tint = MaterialTheme.colorScheme.onBackground)
    }
}

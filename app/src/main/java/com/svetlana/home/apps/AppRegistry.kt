package com.svetlana.home.apps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.UserHandle
import android.util.Log
import com.svetlana.home.core.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Хранилище пользовательских метаданных приложений: избранное, скрытые,
 * псевдонимы, время последнего использования, capability matrix.
 */
class AppRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val storeFile: File by lazy {
        File(context.filesDir, "app_registry.json").apply { parentFile?.mkdirs() }
    }

    private val _apps = MutableStateFlow<List<AppModel>>(emptyList())
    val apps: StateFlow<List<AppModel>> = _apps.asStateFlow()

    init {
        load()
    }

    private fun load() {
        scope.launch {
            val list: List<AppModel> = try {
                if (storeFile.exists() && storeFile.length() > 0) {
                    json.decodeFromString(ListSerializer(AppModel.serializer()), storeFile.readText())
                } else emptyList()
            } catch (t: Throwable) {
                Log.w(TAG, "Не удалось прочитать реестр приложений", t)
                emptyList()
            }
            _apps.value = list
        }
    }

    fun persist(apps: List<AppModel>) {
        _apps.value = apps
        scope.launch {
            try {
                storeFile.writeText(json.encodeToString(ListSerializer(AppModel.serializer()), apps))
            } catch (t: Throwable) {
                Log.w(TAG, "Не удалось сохранить реестр приложений", t)
            }
        }
    }

    fun iconFor(packageName: String): Drawable? = try {
        context.packageManager.getApplicationIcon(packageName)
    } catch (t: Throwable) { null }

    fun setFavorite(packageName: String, favorite: Boolean) {
        persist(_apps.value.map { if (it.packageName == packageName) it.copy(isFavorite = favorite) else it })
    }

    fun setHidden(packageName: String, hidden: Boolean) {
        persist(_apps.value.map { if (it.packageName == packageName) it.copy(isHidden = hidden) else it })
    }

    fun markUsed(packageName: String) {
        persist(_apps.value.map {
            if (it.packageName == packageName) it.copy(lastUsedAt = System.currentTimeMillis()) else it
        })
    }

    fun addAlias(packageName: String, alias: String) {
        persist(_apps.value.map {
            if (it.packageName == packageName && it.aliases.contains(alias).not())
                it.copy(aliases = it.aliases + alias) else it
        })
    }

    fun setCapabilities(packageName: String, caps: ControlCapabilities) {
        persist(_apps.value.map {
            if (it.packageName == packageName) it.copy(control = caps) else it
        })
    }

    fun byPackage(packageName: String): AppModel? = _apps.value.firstOrNull { it.packageName == packageName }

    companion object { private const val TAG = "AppRepository" }
}

/**
 * AppRegistry — обнаруживает доступные приложения через Android API
 * и отслеживает установку / удаление / обновление.
 */
class AppRegistry(
    private val context: Context,
    private val repository: AppRepository,
    private val permissionManager: com.svetlana.home.permissions.PermissionManager
) {

    private val pm: PackageManager get() = context.packageManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val apps: StateFlow<List<AppModel>> = repository.apps

    private val packageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val pkg = intent?.data?.schemeSpecificPart ?: return
            when (intent.action) {
                Intent.ACTION_PACKAGE_ADDED,
                Intent.ACTION_PACKAGE_INSTALL,
                Intent.ACTION_PACKAGE_CHANGED -> refresh(pkg)
                Intent.ACTION_PACKAGE_REMOVED -> remove(pkg)
                Intent.ACTION_PACKAGE_REPLACED -> refresh(pkg)
            }
        }
    }

    fun startWatching() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(packageChangeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(packageChangeReceiver, filter)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось зарегистрировать приёмник изменений пакетов", t)
        }
    }

    fun stopWatching() {
        try { context.unregisterReceiver(packageChangeReceiver) } catch (t: Throwable) { /* игнорируем */ }
    }

    /**
     * Полное сканирование через PackageManager / LauncherApps.
     * Учитываются ограничения Android на видимость пакетов.
     *
     * Аудит P0: основным источником служит queryIntentActivities по
     * MAIN/LAUNCHER — он работает всегда (с объявленным в манифесте <queries>),
     * даже когда Svetlana ещё не назначена главным экраном. LauncherApps же
     * может возвращать пустой список для неприставленного launcher, и из-за
     * этого App Drawer оказывался пустым.
     */
    fun scan(): List<AppModel> {
        val result = mutableListOf<AppModel>()
        val saved = repository.apps.value.associateBy { it.packageName }
        val seen = HashSet<String>()

        // Основной способ: queryIntentActivities по MAIN/LAUNCHER.
        // Не требует роли launcher и уважает <queries> в манифесте.
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, 0)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "queryIntentActivities не удалось", t)
            emptyList()
        }
        resolved.forEach { ri ->
            val pkg = ri.activityInfo.packageName
            if (seen.add(pkg)) result.add(build(pkg, saved[pkg]))
        }

        // Дополнительно: LauncherApps (уважает visibility для launcher).
        // Используется как источник дополнительных entry-точек, а не как
        // единственный — иначе список пуст, пока роль HOME не выдана.
        if (resolved.isEmpty()) {
            val launcherApps = try {
                context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
            } catch (t: Throwable) { null }
            val profiles = listOf(android.os.Process.myUserHandle())
            for (profile in profiles) {
                val activities = try {
                    launcherApps?.getActivityList(null, profile)
                } catch (t: Throwable) {
                    Log.w(TAG, "LauncherApps недоступен", t)
                    null
                }
                activities?.forEach { la ->
                    val pkg = la.applicationInfo.packageName
                    if (seen.add(pkg)) result.add(build(pkg, saved[pkg], profile))
                }
            }
        }

        // Системные приложения, которые не имеют launcher activity, но нужны для управления.
        addSystemEntryPoints(result, saved)

        repository.persist(result.distinctBy { it.packageName })
        return result
    }

    /**
     * Фоновое сканирование — не блокирует вызывающий поток.
     * Используется при старте приложения, чтобы App Drawer был готов.
     */
    fun scanAsync() {
        scope.launch {
            try {
                scan()
                startWatching()
                Log.i(TAG, "Реестр приложений обновлён в фоне: ${apps.value.size}")
            } catch (t: Throwable) {
                Log.e(TAG, "Фоновое сканирование не удалось", t)
            }
        }
    }

    private fun build(
        pkg: String,
        saved: AppModel?,
        profile: UserHandle = android.os.Process.myUserHandle()
    ): AppModel {
        val ai = try { pm.getApplicationInfo(pkg, 0) } catch (t: Throwable) { null }
        val base = saved ?: AppModel(packageName = pkg, label = ai?.loadLabel(pm)?.toString() ?: pkg)
        // Аудит п.5: Hands-возможности — реальные, а не оптимистичные.
        // Accessibility может управлять окном, но только если пользователь
        // сам включил сервис. Если выключен — эти capability недоступны.
        val handsActive = permissionManager.accessibilityEnabled()
        return base.copy(
            label = ai?.loadLabel(pm)?.toString() ?: base.label,
            systemApp = ai != null && (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0,
            enabled = ai?.enabled ?: true,
            versionName = try { pm.getPackageInfo(pkg, 0).versionName } catch (t: Throwable) { null },
            installedAt = try { pm.getPackageInfo(pkg, 0).firstInstallTime } catch (t: Throwable) { 0L },
            category = if (base.category != AppCategory.OTHER) base.category else categorize(pkg, ai),
            aliases = (AppAliases.builtIn[pkg] ?: emptyList()) + base.aliases,
            control = base.control.copy(
                canAccessibility = handsActive,
                canReadUI = handsActive,
                canClick = handsActive,
                canInput = handsActive,
                canScreenshot = handsActive,
                canVerify = handsActive
            )
        )
    }

    /**
     * Категория приложения по пакету и флагам (ТЗ §11).
     * Не использует скрытые API — только PackageManager.
     */
    private fun categorize(pkg: String, ai: android.content.pm.ApplicationInfo?): String {
        ai ?: return AppCategory.OTHER
        val isSystem = (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
        val isGame = (ai.flags and android.content.pm.ApplicationInfo.FLAG_IS_GAME) != 0
        return AppCategoryResolver.resolve(pkg, isSystem, isGame)
    }

    private fun addSystemEntryPoints(result: MutableList<AppModel>, saved: Map<String, AppModel>) {
        val sysTargets = listOf(
            "com.android.settings" to ControlCapabilities(canIntent = true, canDeepLink = true),
            "com.android.dialer" to ControlCapabilities(canIntent = true, canDeepLink = true, canAccessibility = false),
            "com.android.contacts" to ControlCapabilities(canIntent = true, canAccessibility = false)
        )
        for ((pkg, caps) in sysTargets) {
            val existing = result.firstOrNull { it.packageName == pkg }
            if (existing == null) {
                val ai = try { pm.getApplicationInfo(pkg, 0) } catch (t: Throwable) { null }
                if (ai != null) {
                    result.add(
                        (saved[pkg] ?: AppModel(packageName = pkg, label = ai.loadLabel(pm).toString()))
                            .copy(control = caps)
                    )
                }
            } else {
                result[result.indexOf(existing)] = existing.copy(control = caps)
            }
        }
    }

    private fun refresh(packageName: String) {
        scope.launch {
            try {
                val ai = pm.getApplicationInfo(packageName, 0)
                val saved = repository.apps.value.firstOrNull { it.packageName == packageName }
                val label = ai.loadLabel(pm).toString()
                val updated = (saved ?: AppModel(packageName, label)).copy(
                    label = label,
                    enabled = ai.enabled,
                    aliases = (AppAliases.builtIn[packageName] ?: emptyList()) + (saved?.aliases ?: emptyList())
                )
                repository.persist(
                    (repository.apps.value.filterNot { it.packageName == packageName } + updated)
                )
                Log.i(TAG, "Пакет обновлён: $packageName")
            } catch (t: Throwable) {
                // пакет удалён
                remove(packageName)
            }
        }
    }

    private fun remove(packageName: String) {
        scope.launch {
            repository.persist(repository.apps.value.filterNot { it.packageName == packageName })
            Log.i(TAG, "Пакет удалён: $packageName")
        }
    }

    /**
     * Поиск приложения по имени или русскому псевдониму.
     */
    fun resolve(spokenName: String): AppModel? {
        val q = spokenName.trim().lowercase()
        val all = apps.value
        return all.firstOrNull { it.label.lowercase() == q }
            ?: all.firstOrNull { it.packageName.lowercase() == q }
            ?: all.firstOrNull { it.aliases.any { a -> a.lowercase() == q || q.contains(a.lowercase()) } }
            ?: all.firstOrNull { it.label.lowercase().contains(q) }
    }

    fun favorites(): List<AppModel> = apps.value.filter { it.isFavorite && !it.isHidden }

    fun recent(limit: Int = 6): List<AppModel> =
        apps.value.filterNot { it.isHidden }.sortedByDescending { it.lastUsedAt }.take(limit)

    fun search(query: String): List<AppModel> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return apps.value.filterNot { it.isHidden }
        return apps.value.filterNot { it.isHidden }.filter {
            it.label.lowercase().contains(q) ||
                    it.packageName.lowercase().contains(q) ||
                    it.aliases.any { a -> a.lowercase().contains(q) }
        }
    }

    companion object { private const val TAG = "AppRegistry" }
}

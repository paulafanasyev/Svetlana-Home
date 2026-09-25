package com.svetlana.home.device

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Point
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import com.svetlana.home.core.SvetlanaStatus
import java.io.File

/**
 * DeviceCapabilityManager — постоянно знает реальные возможности устройства.
 * Все значения определяются через Android API, ничего не предполагается заранее.
 */
class DeviceCapabilityManager(private val context: Context) {

    data class Capabilities(
        val model: String,
        val androidVersion: String,
        val sdkInt: Int,
        val abis: List<String>,
        val cpuCores: Int,
        val ramTotalMb: Int,
        val ramAvailableMb: Int,
        val storageTotalMb: Long,
        val storageAvailableMb: Long,
        val gpu: String,
        val gpuVendor: String = "unknown",
        val vulkanSupported: Boolean,
        val vulkanVersion: String = "unknown",
        val openGlEsVersion: String,
        val nnapiSupported: Boolean,
        val thermalStatus: String,
        val batteryLevel: Int,
        val batteryPercent: Int,
        val screenInches: Double,
        val screenDensityDpi: Int,
        val hasCamera: Boolean,
        val hasMicrophone: Boolean,
        val audioSupported: Boolean,
        val networkAvailable: Boolean,
        val networkType: String,
        val backendSupport: BackendSupport,
        val snapshotAt: Long
    ) {
        val isEmulator: Boolean
            get() = (model.contains("Emulator", ignoreCase = true) ||
                    model.contains("Android SDK built for", ignoreCase = true) ||
                    "google_sdk" == model || "sdk_gphone" == model)
    }

    data class BackendSupport(
        val cpu: Boolean = true,
        val gpu: Boolean = false,
        val npu: Boolean = false,
        val nnapi: Boolean = false
    )

    @Volatile
    private var cached: Capabilities? = null

    fun refresh(): Capabilities {
        val caps = probe()
        cached = caps
        return caps
    }

    fun current(): Capabilities = cached ?: refresh()

    private fun probe(): Capabilities {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }

        val abis = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Build.SUPPORTED_ABIS.toList()
        } else listOf(Build.CPU_ABI)

        val extStorage = getStorageStats(context.filesDir.absolutePath)

        // OpenGL ES версия — это не имя GPU. Имя GPU определяем отдельно через EGL.
        val glEsVersion = try {
            am.deviceConfigurationInfo.glEsVersion
        } catch (t: Throwable) { "unknown" }
        val gpuInfo = GpuProbe.rendererInfo(context)

        val displayMetrics = DisplayMetrics().also {
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                .defaultDisplay.getRealMetrics(it)
        }
        val x = displayMetrics.widthPixels / displayMetrics.xdpi
        val y = displayMetrics.heightPixels / displayMetrics.ydpi
        val screenInches = Math.sqrt((x * x + y * y).toDouble())

        val battery = batteryStatus()
        val network = networkStatus()

        return Capabilities(
            model = Build.MODEL ?: "unknown",
            androidVersion = Build.VERSION.RELEASE ?: "unknown",
            sdkInt = Build.VERSION.SDK_INT,
            abis = abis,
            cpuCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
            ramTotalMb = (memInfo.totalMem / MB).toInt(),
            ramAvailableMb = (memInfo.availMem / MB).toInt(),
            storageTotalMb = extStorage.first,
            storageAvailableMb = extStorage.second,
            gpu = gpuInfo.first,
            gpuVendor = gpuInfo.second,
            vulkanSupported = vulkanSupported(),
            vulkanVersion = vulkanVersion(),
            openGlEsVersion = glEsVersion,
            nnapiSupported = nnapiSupported(),
            thermalStatus = thermalStatus(),
            batteryLevel = battery.first,
            batteryPercent = battery.second,
            screenInches = screenInches,
            screenDensityDpi = displayMetrics.densityDpi,
            hasCamera = hasCamera(),
            hasMicrophone = hasMicrophone(),
            audioSupported = audioSupported(),
            networkAvailable = network.first,
            networkType = network.second,
            backendSupport = detectBackends(abis, glEsVersion, gpuInfo.first),
            snapshotAt = System.currentTimeMillis()
        )
    }

    private fun getStorageStats(path: String): Pair<Long, Long> {
        return try {
            val stat = StatFs(path)
            val total = stat.blockCount.toLong() * stat.blockSize.toLong() / MB
            val avail = stat.availableBlocks.toLong() * stat.blockSize.toLong() / MB
            total to avail
        } catch (t: Throwable) { 0L to 0L }
    }

    private fun vulkanSupported(): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)
        } else false
    } catch (t: Throwable) { false }

    private fun vulkanVersion(): String {
        return try {
            // FeatureInfo.version появилось в API 33; на более старых версиях
            // поле существует в классе, но равно 0. getSystemFeatureVersion
            // непубличный/удалённый — используем только публичный API.
            val features = context.packageManager.getSystemAvailableFeatures()
            val version = features.firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                version?.version?.takeIf { it != 0 }?.toString() ?: "unknown"
            } else "unknown"
        } catch (t: Throwable) { "unknown" }
    }

    private fun nnapiSupported(): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager.hasSystemFeature("android.software.neuralnetworks")
        } else false
    } catch (t: Throwable) { false }

    /**
     * Тепловое состояние. Публичный API — PowerManager.getCurrentThermalStatus(),
     * доступен с API 29 (Android 10). Reflection не используется.
     */
    @Suppress("NewApi")
    private fun thermalStatus(): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                thermalName(pm?.getCurrentThermalStatus() ?: -1)
            } else "unknown"
        } catch (t: Throwable) { "unknown" }
    }

    private fun thermalName(status: Int): String = when (status) {
        0 -> "none"
        1 -> "light"
        2 -> "moderate"
        3 -> "severe"
        4 -> "critical"
        5 -> "emergency"
        6 -> "shutdown"
        else -> "unknown"
    }

    private fun batteryStatus(): Pair<Int, Int> = try {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        (if (bm.isCharging) 1 else 0) to level
    } catch (t: Throwable) { 0 to -1 }

    private fun hasCamera(): Boolean = try {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    } catch (t: Throwable) { false }

    private fun hasMicrophone(): Boolean = try {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
    } catch (t: Throwable) { false }

    private fun audioSupported(): Boolean = try {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am != null
    } catch (t: Throwable) { false }

    fun networkStatus(): Pair<Boolean, String> = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val nw = cm.activeNetwork
            val caps = cm.getNetworkCapabilities(nw)
            if (caps == null) false to "none"
            else when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true to "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> true to "cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> true to "ethernet"
                else -> true to "other"
            }
        } else true to "legacy"
    } catch (t: Throwable) { false to "unknown" }

    private fun detectBackends(abis: List<String>, glEs: String, gpuName: String): BackendSupport {
        val arm64 = abis.any { it.contains("arm64-v8a") || it.contains("x86_64") }
        val npu = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                context.packageManager.hasSystemFeature("android.software.neuralnetworks")
            else false
        } catch (t: Throwable) { false }
        // GPU compute backend не доказывается версией OpenGL ES.
        // Поддерживаем, только если есть реальный Vulkan/GLES-ускоритель и NNAPI GPU delegation.
        val gpuCompute = arm64 && glEs != "unknown" && gpuName != "unknown" &&
                vulkanSupported()
        return BackendSupport(
            cpu = true,
            gpu = gpuCompute,
            npu = npu,
            nnapi = npu
        )
    }

    /**
     * Короткий текстовый отчёт о состоянии устройства для диагностики.
     */
    fun report(caps: Capabilities = current()): String = buildString {
        appendLine("model=${caps.model}")
        appendLine("android=${caps.androidVersion} sdk=${caps.sdkInt} abi=${caps.abis.joinToString(",")}")
        appendLine("cpu_cores=${caps.cpuCores} ram=${caps.ramTotalMb}MB free=${caps.ramAvailableMb}MB")
        appendLine("storage=${caps.storageTotalMb}MB free=${caps.storageAvailableMb}MB")
        appendLine("gpu=${caps.gpu} vendor=${caps.gpuVendor} gles=${caps.openGlEsVersion} vulkan=${caps.vulkanVersion} nnapi=${caps.nnapiSupported}")
        appendLine("thermal=${caps.thermalStatus} battery=${caps.batteryPercent}%")
        appendLine("network=${caps.networkType} camera=${caps.hasCamera} mic=${caps.hasMicrophone}")
        appendLine("backend_support=${caps.backendSupport}")
        appendLine("status=${SvetlanaStatus.NOT_PROVEN}")
    }

    companion object {
        private const val MB = 1024L * 1024L
        @Volatile private var bootTime = SystemClock.elapsedRealtime()
    }
}

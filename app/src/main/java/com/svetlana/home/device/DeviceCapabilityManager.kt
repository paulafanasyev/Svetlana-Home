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
        val vulkanSupported: Boolean,
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

        val gpu = try {
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
                .deviceConfigurationInfo.glEsVersion
        } catch (t: Throwable) { "unknown" }

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
            gpu = gpu,
            vulkanSupported = vulkanSupported(),
            openGlEsVersion = gpu,
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
            backendSupport = detectBackends(abis, gpu),
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

    private fun nnapiSupported(): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager.hasSystemFeature("android.software.neuralnetworks")
        } else false
    } catch (t: Throwable) { false }

    private fun thermalStatus(): String = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cls = Class.forName("android.os.ThermalManager")
            val tm = context.getSystemService(cls)
            val status = tm?.let {
                cls.getMethod("getCurrentThermalStatus").invoke(it) as? Int
            } ?: -1
            thermalName(status)
        } else "unknown"
    } catch (t: Throwable) { "unknown" }

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

    private fun detectBackends(abis: List<String>, glEs: String): BackendSupport {
        val arm64 = abis.any { it.contains("arm64-v8a") || it.contains("x86_64") }
        val npu = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                context.packageManager.hasSystemFeature("android.software.neuralnetworks")
            else false
        } catch (t: Throwable) { false }
        return BackendSupport(
            cpu = true,
            gpu = arm64 && glEs != "unknown",
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
        appendLine("gpu=${caps.gpu} vulkan=${caps.vulkanSupported} nnapi=${caps.nnapiSupported}")
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

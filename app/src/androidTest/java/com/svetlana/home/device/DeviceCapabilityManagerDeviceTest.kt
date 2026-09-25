package com.svetlana.home.device

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.svetlana.home.SvetlanaDeviceTest
import com.svetlana.home.core.ServiceLocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device Capability Manager: реальные показатели снимаются с устройства.
 * ТЗ §29: менеджер постоянно знает CPU, GPU, RAM, storage, thermal, battery.
 *
 * Этот тест доказывает, что значения — настоящие, а не заглушки.
 */
@RunWith(AndroidJUnit4::class)
class DeviceCapabilityManagerDeviceTest : SvetlanaDeviceTest() {

    @Test
    fun deviceProfileIsReal() {
        val caps = ServiceLocator.device.refresh()

        assertNotNull("model не должен быть null", caps.model)
        assertTrue("model не должен быть empty", caps.model.isNotBlank())
        assertNotEquals("model не должен быть unknown", "unknown", caps.model)
        assertTrue("Android версия должна быть определена", caps.androidVersion.isNotBlank())
        assertTrue("SDK должен быть >= 21", caps.sdkInt >= 21)
        assertTrue("Должен быть хотя бы один ABI", caps.abis.isNotEmpty())
    }

    @Test
    fun cpuAndRamAreReal() {
        val caps = ServiceLocator.device.refresh()

        assertTrue("CPU ядер >= 1", caps.cpuCores >= 1)
        assertTrue("RAM total > 0", caps.ramTotalMb > 0)
        assertTrue("RAM available >= 0", caps.ramAvailableMb >= 0)
        assertTrue("RAM available <= total", caps.ramAvailableMb <= caps.ramTotalMb)
    }

    @Test
    fun storageIsReal() {
        val caps = ServiceLocator.device.refresh()

        assertTrue("storage total > 0", caps.storageTotalMb > 0)
        assertTrue("storage available >= 0", caps.storageAvailableMb >= 0)
        assertTrue("storage available <= total", caps.storageAvailableMb <= caps.storageTotalMb)
    }

    @Test
    fun gpuNameIsNotJustOpenGlVersion() {
        val caps = ServiceLocator.device.refresh()

        // Аудит п.14: поле gpu когда-то содержало версию OpenGL ES, а не имя GPU.
        // Версия GLES выглядит как "3.2" — это не имя GPU.
        val looksLikeVersionOnly = caps.gpu.matches(Regex("""\d+(\.\d+)?"""))
        println("GPU_NAME=${caps.gpu} GPU_VENDOR=${caps.gpuVendor} GLES=${caps.openGlEsVersion}")
        // Имя GPU может быть unknown на устройстве без EGL, но это не должно
        // быть просто версией OpenGL ES.
        assertTrue("Поле gpu не должно быть просто версией OpenGL ES",
            !looksLikeVersionOnly || caps.gpu == "unknown")
        // openGlEsVersion хранится отдельно.
        assertNotNull(caps.openGlEsVersion)
    }

    @Test
    fun thermalAndBatteryAreReal() {
        val caps = ServiceLocator.device.refresh()

        println("THERMAL=${caps.thermalStatus} BATTERY=${caps.batteryPercent}%")
        // thermalStatus может быть unknown на старых SDK, но должен быть из
        // известного множества.
        val valid = setOf("none", "light", "moderate", "severe", "critical",
            "emergency", "shutdown", "unknown")
        assertTrue("thermal status из допустимого множества: ${caps.thermalStatus}",
            caps.thermalStatus in valid)
        // battery: -1 = неизвестно, иначе 0..100
        assertTrue("battery в диапазоне",
            caps.batteryPercent == -1 || caps.batteryPercent in 0..100)
    }

    @Test
    fun networkStatusIsReported() {
        val caps = ServiceLocator.device.refresh()
        println("NETWORK=${caps.networkType}")
        // Мы не требуем наличия сети, но метод должен отработать и что-то вернуть.
        assertNotNull(caps.networkType)
    }

    @Test
    fun reportContainsAllKeyFields() {
        val caps = ServiceLocator.device.refresh()
        val report = ServiceLocator.device.report(caps)

        listOf("model=", "android=", "cpu_cores=", "ram=", "storage=",
            "gpu=", "thermal=", "battery=", "network=", "backend_support="
        ).forEach { key ->
            assertTrue("Отчёт должен содержать $key", report.contains(key))
        }
    }
}

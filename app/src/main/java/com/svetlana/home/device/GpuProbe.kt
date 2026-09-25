package com.svetlana.home.device

import android.content.Context
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.opengles.GL10

/**
 * GpuProbe — определяет реальное имя GPU и vendor через публичный EGL API.
 *
 * Важно: версия OpenGL ES НЕ является именем GPU. Раньше поле gpu содержало
 * именно glEsVersion, что было ошибкой интерпретации. Здесь берём GL_RENDERER
 * и GL_VENDOR из созданного EGL-контекста.
 *
 * Если EGL недоступен (например, на устройстве без дисплея) — возвращаем "unknown",
 * а не предполагаем наличие GPU.
 */
object GpuProbe {

    fun rendererInfo(context: Context): Pair<String, String> {
        return try {
            val egl = EGLContext.getEGL() as? EGL10 ?: return "unknown" to "unknown"
            val display: EGLDisplay = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
            if (display === EGL10.EGL_NO_DISPLAY) return "unknown" to "unknown"
            val version = IntArray(2)
            if (!egl.eglInitialize(display, version)) return "unknown" to "unknown"
            val config = chooseConfig(egl, display) ?: run {
                egl.eglTerminate(display)
                return "unknown" to "unknown"
            }
            val ctxAttrs = intArrayOf(EGL10.EGL_NONE)
            val ctx = egl.eglCreateContext(display, config, EGL10.EGL_NO_CONTEXT, ctxAttrs)
            if (ctx === EGL10.EGL_NO_CONTEXT) {
                egl.eglTerminate(display)
                return "unknown" to "unknown"
            }
            val pbufferAttrs = intArrayOf(
                EGL10.EGL_WIDTH, 1, EGL10.EGL_HEIGHT, 1,
                EGL10.EGL_NONE
            )
            val surface = egl.eglCreatePbufferSurface(display, config, pbufferAttrs)
            val madeCurrent = surface !== null && egl.eglMakeCurrent(display, surface, surface, ctx)
            val renderer: Pair<String, String> = if (madeCurrent) {
                val gl = ctx.gl as? GL10
                val name = gl?.glGetString(GL10.GL_RENDERER)?.trim()?.ifBlank { null }
                val vendor = gl?.glGetString(GL10.GL_VENDOR)?.trim()?.ifBlank { null }
                (name ?: "unknown") to (vendor ?: "unknown")
            } else "unknown" to "unknown"
            try { egl.eglMakeCurrent(display, null, null, null) } catch (_: Throwable) {}
            try { if (surface != null) egl.eglDestroySurface(display, surface) } catch (_: Throwable) {}
            try { egl.eglDestroyContext(display, ctx) } catch (_: Throwable) {}
            try { egl.eglTerminate(display) } catch (_: Throwable) {}
            renderer
        } catch (t: Throwable) {
            "unknown" to "unknown"
        }
    }

    private fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig? {
        val attrs = intArrayOf(
            EGL10.EGL_SURFACE_TYPE, EGL10.EGL_PBUFFER_BIT,
            EGL10.EGL_RENDERABLE_TYPE, 4, // EGL_OPENGL_ES2_BIT
            EGL10.EGL_RED_SIZE, 8,
            EGL10.EGL_GREEN_SIZE, 8,
            EGL10.EGL_BLUE_SIZE, 8,
            EGL10.EGL_NONE
        )
        val num = IntArray(1)
        if (!egl.eglChooseConfig(display, attrs, null, 0, num) || num[0] <= 0) return null
        val configs = arrayOfNulls<EGLConfig>(num[0])
        if (!egl.eglChooseConfig(display, attrs, configs, num[0], num)) return null
        return configs.firstOrNull()
    }
}

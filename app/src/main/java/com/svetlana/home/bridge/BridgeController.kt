package com.svetlana.home.bridge

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.svetlana.home.BuildConfig
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.concurrent.thread

/**
 * Starts the bridge that lets the Svetlana 2.0 core (Windows app) use this
 * phone: http://<phone-ip>:8080, protected by a pairing code shown on the home
 * screen and in a notification. Opt-in: off until the user enables it; every
 * enable generates a fresh code. Plain HTTP: use only on a trusted home Wi-Fi.
 */
object BridgeController {
    const val PORT = 8080
    private const val TAG = "SvetlanaBridge"
    private const val PREFS = "svetlana_bridge"
    private const val KEY_CODE = "pairing_code"
    private const val KEY_ENABLED = "enabled"
    private const val SECURE_PREFS = "svetlana_bridge_secure"
    private const val CHANNEL_ID = "svetlana_bridge"
    private const val NOTIFICATION_ID = 8080

    @Volatile
    private var server: BridgeServer? = null

    @Volatile
    private var currentCode: String? = null

    /** The bridge is opt-in: off until the user turns it on from the home screen. */
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    /** Blocking (Keystore + disk): call from a background thread. Throws if secure storage is unavailable. */
    fun setEnabled(context: Context, enabled: Boolean) {
        val app = context.applicationContext
        if (enabled) {
            // Каждое включение = новый код: старые подключения больше не работают.
            // Бросает IllegalStateException, если Keystore недоступен — тогда мост не включаем.
            rotatePairingCode(app)
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, true).apply()
            startAsync(app)
        } else {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, false).apply()
            thread(name = "svetlana-bridge-stop", isDaemon = true) { stop() }
            app.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        }
    }

    /** Non-blocking: binds the socket on a background thread if the bridge is enabled. Never throws. */
    fun startAsync(context: Context) {
        val app = context.applicationContext
        if (!isEnabled(app)) return
        thread(name = "svetlana-bridge-start", isDaemon = true) {
            try {
                start(app)
            } catch (e: Exception) {
                Log.w(TAG, "Bridge failed to start", e)
            }
        }
    }

    @Synchronized
    fun start(context: Context) {
        if (!isEnabled(context)) return
        if (server?.isRunning == true) {
            // Already running: re-post the notification (e.g. after the user
            // has just granted POST_NOTIFICATIONS).
            showNotification(context, pairingCode(context))
            return
        }
        val code = pairingCode(context)
        val router = BridgeRouter(
            tokenCheck = { BridgeToken.matches(currentCode ?: code, it) },
            handlers = AndroidBridgeHandlers(context),
            version = BuildConfig.VERSION_NAME,
        )
        val candidate = BridgeServer(PORT, router)
        try {
            candidate.start()
        } catch (e: IOException) {
            Log.w(TAG, "Port $PORT is busy, bridge not started: ${e.message}")
            return
        }
        server = candidate
        Log.i(TAG, "Bridge listening on ${address() ?: "port $PORT"}")
        showNotification(context, code)
    }

    @Synchronized
    fun stop() {
        server?.stop()
        server = null
    }

    fun pairingCode(context: Context): String {
        val prefs = securePrefs(context)
        prefs.getString(KEY_CODE, null)?.let {
            currentCode = it
            return it
        }
        return rotatePairingCode(context)
    }

    /** New code; the running server picks it up on the next request. Fails closed if it can't be saved. */
    fun rotatePairingCode(context: Context): String {
        val code = BridgeToken.generate()
        val saved = securePrefs(context).edit().putString(KEY_CODE, code).commit()
        check(saved) { "Не удалось сохранить код подключения" }
        currentCode = code
        return code
    }

    /**
     * Код хранится в EncryptedSharedPreferences (ключ в Android Keystore).
     * Fail closed: без защищённого хранилища мост не запускается.
     */
    private fun securePrefs(context: Context): SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            SECURE_PREFS,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        Log.w(TAG, "Keystore unavailable, bridge disabled", e)
        throw IllegalStateException("Защищённое хранилище недоступно, мост выключен", e)
    }

    /** http://<LAN IPv4>:8080, or null when the phone has no LAN address. */
    fun address(): String? {
        val ip = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
                ?.hostAddress
        } catch (e: Exception) {
            null
        }
        return ip?.let { "http://$it:$PORT" }
    }

    private fun showNotification(context: Context, code: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Мост Светланы", NotificationManager.IMPORTANCE_LOW),
        )
        val where = address() ?: "порт $PORT"
        val text = "Адрес: $where\nКод подключения: $code"
        // На заблокированном экране код не показываем.
        val publicVersion = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle("Светлана готова к подключению")
            .setContentText("Разблокируйте телефон, чтобы увидеть код")
            .build()
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setContentTitle("Светлана готова к подключению")
            .setContentText("$where · код $code")
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }
}

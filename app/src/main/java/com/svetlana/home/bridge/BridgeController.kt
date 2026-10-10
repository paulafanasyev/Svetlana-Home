package com.svetlana.home.bridge

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.svetlana.home.BuildConfig
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.concurrent.thread

/**
 * Starts the bridge that lets the Svetlana 2.0 core (web / Windows) use this
 * phone: http://<phone-ip>:8080, protected by a pairing code shown in a
 * notification. The code is generated once and stored on the device.
 */
object BridgeController {
    const val PORT = 8080
    private const val TAG = "SvetlanaBridge"
    private const val PREFS = "svetlana_bridge"
    private const val KEY_CODE = "pairing_code"
    private const val CHANNEL_ID = "svetlana_bridge"
    private const val NOTIFICATION_ID = 8080

    @Volatile
    private var server: BridgeServer? = null

    /** Non-blocking: binds the socket on a background thread. Never throws. */
    fun startAsync(context: Context) {
        val app = context.applicationContext
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
        if (server?.isRunning == true) {
            // Already running: re-post the notification (e.g. after the user
            // has just granted POST_NOTIFICATIONS).
            showNotification(context, pairingCode(context))
            return
        }
        val code = pairingCode(context)
        val router = BridgeRouter(
            tokenCheck = { BridgeToken.matches(code, it) },
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
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_CODE, null)?.let { return it }
        val code = BridgeToken.generate()
        prefs.edit().putString(KEY_CODE, code).apply()
        return code
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
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle("Светлана готова к подключению")
            .setContentText("$where · код $code")
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }
}

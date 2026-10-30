package com.frcam.app.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.frcam.app.R
import com.frcam.app.data.CameraRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Foreground Service en el celular que mantiene activos los sockets y el streaming
 * local hacia el reloj Wear OS incluso si la aplicación FrCam fue cerrada o la pantalla está apagada.
 */
class FrCamPhoneBridgeService : Service() {

    companion object {
        private const val TAG = "FrCamPhoneBridge"
        private const val CHANNEL_ID = "frcam_wear_bridge"
        private const val NOTIFICATION_ID = 49881

        fun start(context: Context) {
            try {
                val intent = Intent(context, FrCamPhoneBridgeService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo iniciar servicio en primer plano: ${e.message}")
            }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Iniciando FrCamPhoneBridgeService (Foreground Service)...")
        createNotificationChannel()
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)

        // Adquirir WakeLock y WifiLock para evitar que Android Doze mate los sockets en reposo
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "frcam:bridge_wakelock").apply {
                setReferenceCounted(false)
                acquire(12 * 60 * 60 * 1000L) // 12 horas máximo
            }
        } catch (_: Exception) {}

        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "frcam:bridge_wifilock").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {}

        // Iniciar el servidor LAN HTTP + UDP en el celular
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = CameraRepository(applicationContext)
                val cams = repo.camerasFlow.first()
                PhoneWearSyncEngine.startLanSyncServer(applicationContext) { cams }
            } catch (_: Exception) {
                PhoneWearSyncEngine.startLanSyncServer(applicationContext) { emptyList() }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        try { wakeLock?.release() } catch (_: Exception) {}
        try { wifiLock?.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "FrCam Wear OS Bridge",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantiene la conexión y streaming en tiempo real con el reloj Wear OS"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FrCam conectado con Wear OS")
            .setContentText("Streaming y sincronización activa en segundo plano")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}

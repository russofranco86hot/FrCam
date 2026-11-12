package com.frcam.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import com.frcam.app.MainActivity
import com.frcam.app.data.AppSettings
import com.frcam.app.data.CameraRepository
import com.frcam.app.ai.CameraAiDetector
import com.frcam.app.media.CameraMediaEngine
import com.frcam.app.media.FrameMotionDetector
import com.frcam.app.model.CameraBrand
import com.frcam.app.model.CameraConfig
import com.frcam.app.protocol.NetworkCameraScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Servicio en Primer Plano (Foreground Service) que mantiene la vigilancia activa 24/7
 * incluso si la aplicación FrCam está minimizada, cerrada o la pantalla del móvil apagada.
 *
 * Muestrea periódicamente las cámaras con detección de movimiento habilitada, procesa
 * los cuadros con el detector anti-hojas/viento y la IA de personas/mascotas/vehículos,
 * y ante una detección genera el GIF animado, emite la alerta push y lo respalda a Telegram.
 */
class CameraSurveillanceService : Service() {

    companion object {
        private const val TAG = "CameraSurveillance"
        private const val CHANNEL_ID = "frcam_surveillance_channel"
        private const val NOTIFICATION_ID = 49882

        private val snoozedUntil = ConcurrentHashMap<String, Long>()

        fun snoozeCamera(cameraId: String, durationMs: Long) {
            snoozedUntil[cameraId] = System.currentTimeMillis() + durationMs
            Log.d(TAG, "Cámara $cameraId silenciada por ${durationMs / 60000} minutos")
        }

        fun isSnoozed(cameraId: String): Boolean {
            val expire = snoozedUntil[cameraId] ?: return false
            return expire > System.currentTimeMillis()
        }

        fun startOrUpdate(context: Context) {
            try {
                val intent = Intent(context, CameraSurveillanceService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo iniciar CameraSurveillanceService: ${e.message}")
            }
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, CameraSurveillanceService::class.java)
                context.stopService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Error deteniendo CameraSurveillanceService: ${e.message}")
            }
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val motionDetectors = ConcurrentHashMap<String, FrameMotionDetector>()
    private val lastTriggerTimes = ConcurrentHashMap<String, Long>()

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Iniciando CameraSurveillanceService en primer plano...")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Iniciando vigilancia de cámaras..."))

        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "frcam:surveillance_wakelock").apply {
                acquire(24 * 60 * 60 * 1000L) // 24 horas máximo de salvaguarda
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo adquirir WakeLock: ${e.message}")
        }

        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "frcam:surveillance_wifilock").apply {
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo adquirir WifiLock: ${e.message}")
        }

        startMonitoringLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Deteniendo CameraSurveillanceService...")
        serviceScope.cancel()
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {}
    }

    private fun startMonitoringLoop() {
        serviceScope.launch {
            val repository = CameraRepository(applicationContext)
            while (isActive) {
                try {
                    if (!AppSettings.backgroundSurveillanceEnabled) {
                        updateNotification("Vigilancia en pausa (desactivada en ajustes)")
                        delay(5000L)
                        continue
                    }

                    if (!AppSettings.isInsideSurveillanceSchedule()) {
                        updateNotification("Fuera de horario de vigilancia programado")
                        delay(10000L)
                        continue
                    }

                    val allCameras = repository.camerasFlow.first()
                    val monitoredCameras = allCameras.filter { it.motionDetectionEnabled }

                    if (monitoredCameras.isEmpty()) {
                        updateNotification("Protegiendo 0 cámaras (activa la campana en tus cámaras)")
                        delay(4000L)
                        continue
                    }

                    updateNotification("Protegiendo ${monitoredCameras.size} cámara(s) • Alertas y Telegram activos")

                    // Muestrear cada cámara activa
                    for (camera in monitoredCameras) {
                        if (!isActive) break
                        if (isSnoozed(camera.id)) continue

                        val now = System.currentTimeMillis()
                        val lastAlert = lastTriggerTimes[camera.id] ?: 0L
                        if (now - lastAlert < 15000L) {
                            // En período de enfriamiento para no duplicar alertas
                            continue
                        }

                        // Obtener cuadro de muestra de forma ultra liviana
                        val sampleBitmap = fetchCameraSampleFrame(camera)
                        if (sampleBitmap != null) {
                            val detector = motionDetectors.getOrPut(camera.id) {
                                FrameMotionDetector(cooldownMs = 15000L)
                            }
                            val result = detector.inspectFrame(
                                context = applicationContext,
                                cameraId = camera.id,
                                cameraName = camera.name,
                                frameBitmap = sampleBitmap,
                                notify = false, // Notificamos con ráfaga completa abajo
                                sensitivity = AppSettings.motionSensitivity
                            )

                            if (result.isTriggered) {
                                lastTriggerTimes[camera.id] = now
                                Log.i(TAG, "¡MOVIMIENTO DETECTADO en ${camera.name}! (${result.percent}% actividad)")

                                // Capturar ráfaga de 4 cuadros posteriores espaciados 200ms para el GIF
                                val burst = mutableListOf<Bitmap>()
                                burst.addAll(result.validPreFrames)
                                burst.add(sampleBitmap)

                                for (step in 1..4) {
                                    delay(200L)
                                    val postFrame = fetchCameraSampleFrame(camera)
                                    if (postFrame != null) {
                                        burst.add(postFrame)
                                    }
                                }

                                val aiResult = if (AppSettings.aiDetectionEnabled) {
                                    CameraAiDetector.analyzeFrame(sampleBitmap)
                                } else null

                                // Generar GIF, enviar alerta push y respaldar a Telegram automáticamente
                                CameraMediaEngine.notifyMotionDetected(
                                    context = applicationContext,
                                    cameraId = camera.id,
                                    cameraName = camera.name,
                                    motionPercent = result.percent,
                                    rawBitmap = sampleBitmap,
                                    burstFrames = burst,
                                    aiDetection = aiResult
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error en ciclo de vigilancia en segundo plano", e)
                }

                // Pausa entre barridos de muestreo (1.5 segundos conserva batería y no satura el Wi-Fi)
                delay(1500L)
            }
        }
    }

    /**
     * Extrae un fotograma JPEG instantáneo de la cámara vía HTTP / snapshot de manera rápida.
     */
    private fun fetchCameraSampleFrame(camera: CameraConfig): Bitmap? {
        val host = camera.host.trim()
        if (host.isBlank() || host == "0.0.0.0") return null

        val urlsToTry = mutableListOf<String>()
        val httpPort = if (camera.httpPort > 0) camera.httpPort else 80

        // Candidatos comunes de captura instantánea por marca y protocolo
        when (camera.brand) {
            CameraBrand.V720_NAXCLOW -> {
                urlsToTry.add("http://$host:80/snapshot.jpg")
                urlsToTry.add("http://$host:8080/snapshot.jpg")
                urlsToTry.add("http://$host:80/video")
            }
            CameraBrand.V380_PRO -> {
                urlsToTry.add("http://$host:8800/snapshot.jpg")
                urlsToTry.add("http://$host:10008/snapshot.jpg")
                urlsToTry.add("http://$host:80/snapshot.jpg")
            }
            CameraBrand.HIKVISION, CameraBrand.EZVIZ -> {
                urlsToTry.add("http://$host:$httpPort/ISAPI/Streaming/channels/101/picture")
                urlsToTry.add("http://$host:$httpPort/ISAPI/Streaming/channels/102/picture")
                urlsToTry.add("http://$host:$httpPort/onvif-http/snapshot")
            }
            else -> {
                urlsToTry.add("http://$host:$httpPort/snapshot.jpg")
                urlsToTry.add("http://$host:$httpPort/video")
                urlsToTry.add("http://$host:$httpPort/stream")
                urlsToTry.add("http://$host:$httpPort/mjpeg/1")
            }
        }

        for (urlStr in urlsToTry) {
            try {
                val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 800
                    readTimeout = 1200
                    requestMethod = "GET"
                    if (camera.username.isNotBlank()) {
                        val auth = Base64.encodeToString("${camera.username}:${camera.password}".toByteArray(), Base64.NO_WRAP)
                        setRequestProperty("Authorization", "Basic $auth")
                    }
                }
                conn.connect()
                if (conn.responseCode in 200..299) {
                    val input = BufferedInputStream(conn.inputStream, 16384)
                    val buffer = ByteArrayOutputStream(32768)
                    var prev = -1
                    var capturing = false
                    val maxBytes = 400_000
                    var total = 0

                    while (total < maxBytes) {
                        val b = input.read()
                        if (b == -1) break
                        total++
                        if (!capturing) {
                            if (prev == 0xFF && b == 0xD8) {
                                capturing = true
                                buffer.reset()
                                buffer.write(0xFF)
                                buffer.write(0xD8)
                            }
                        } else {
                            buffer.write(b)
                            if (prev == 0xFF && b == 0xD9) {
                                val bytes = buffer.toByteArray()
                                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                conn.disconnect()
                                if (bmp != null) return bmp
                                break
                            }
                        }
                        prev = b
                    }
                }
                conn.disconnect()
            } catch (_: Exception) {}
        }
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Vigilancia en Segundo Plano FrCam",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitoreo continuo de cámaras ante movimiento con alertas y respaldo a Telegram."
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("🛡️ FrCam: Vigilancia Activa")
            .setContentText(statusText)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification(statusText: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(statusText))
    }
}

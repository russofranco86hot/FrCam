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
import android.media.MediaMetadataRetriever
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import com.frcam.app.MainActivity
import com.frcam.app.data.AppSettings
import com.frcam.app.data.AutoRecordMode
import com.frcam.app.data.CameraRepository
import com.frcam.app.ai.CameraAiDetector
import com.frcam.app.media.CameraMediaEngine
import com.frcam.app.media.FrameMotionDetector
import com.frcam.app.media.LiveMp4VideoRecorder
import com.frcam.app.model.CameraBrand
import com.frcam.app.model.CameraConfig
import com.frcam.app.protocol.NetworkCameraScanner
import com.frcam.app.protocol.V380NativeProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
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

    private data class ScheduledRecSession(
        val recorder: LiveMp4VideoRecorder,
        val startedAtMs: Long,
        val cameraName: String
    )

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    @Volatile
    private var cachedCameras: List<CameraConfig> = emptyList()
    private val motionDetectors = ConcurrentHashMap<String, FrameMotionDetector>()
    private val lastTriggerTimes = ConcurrentHashMap<String, Long>()
    private val lastSampleTimes = ConcurrentHashMap<String, Long>()
    private val pendingMotionHits = ConcurrentHashMap<String, Pair<Int, Long>>()
    private val scheduledRecorders = ConcurrentHashMap<String, ScheduledRecSession>()
    private val rtspCooldownUntilMs = ConcurrentHashMap<String, Long>()
    private val cameraFailBackoffUntilMs = ConcurrentHashMap<String, Long>()
    private val cameraConsecutiveFails = ConcurrentHashMap<String, Int>()

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Iniciando CameraSurveillanceService en modo ahorro de energía...")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Iniciando vigilancia de cámaras..."))

        serviceScope.launch {
            try {
                CameraRepository(applicationContext).camerasFlow.collect { list ->
                    cachedCameras = list
                }
            } catch (_: Exception) {}
        }

        startMonitoringLoop()
        startScheduledRecordingLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Deteniendo CameraSurveillanceService...")
        serviceScope.cancel()
    }

    private inline fun <T> withShortWakeLock(timeoutMs: Long = 15_000L, block: () -> T): T {
        val wl = try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "frcam:motion_burst").apply {
                setReferenceCounted(false)
                acquire(timeoutMs)
            }
        } catch (_: Exception) {
            null
        }
        return try {
            block()
        } finally {
            try {
                if (wl?.isHeld == true) wl.release()
            } catch (_: Exception) {}
        }
    }

    private fun startMonitoringLoop() {
        serviceScope.launch {
            val repository = CameraRepository(applicationContext)
            val semaphore = Semaphore(4)
            // Esperar carga inicial de cámaras antes de decidir auto-stop
            if (cachedCameras.isEmpty()) {
                cachedCameras = try { repository.camerasFlow.first() } catch (_: Exception) { emptyList() }
            }
            while (isActive) {
                var nextDelayMs = 3500L
                try {
                    val allCameras = cachedCameras
                    val monitoredCameras = if (AppSettings.backgroundSurveillanceEnabled) {
                        allCameras.filter { it.motionDetectionEnabled }
                    } else {
                        emptyList()
                    }
                    val scheduledCameras = allCameras.filter { it.autoRecordMode == AutoRecordMode.SCHEDULE }

                    if (monitoredCameras.isEmpty() && scheduledCameras.isEmpty() && scheduledRecorders.isEmpty()) {
                        Log.d(TAG, "Sin cámaras para vigilar ni grabar en horario; deteniendo servicio para ahorrar batería.")
                        stopSelf()
                        break
                    }

                    if (!AppSettings.backgroundSurveillanceEnabled || monitoredCameras.isEmpty()) {
                        updateNotification("Grabación programada en espera (${scheduledCameras.size} cámara(s))")
                        delay(15_000L)
                        continue
                    }

                    if (!AppSettings.isInsideSurveillanceSchedule()) {
                        updateNotification("Fuera de horario de vigilancia programado")
                        delay(60_000L)
                        continue
                    }

                    val activeRecCount = scheduledRecorders.size
                    val recSuffix = if (activeRecCount > 0) " • Grabando $activeRecCount en horario" else ""
                    updateNotification("Protegiendo ${monitoredCameras.size} cámara(s)$recSuffix")

                    val allInForeground = monitoredCameras.all { CameraMediaEngine.isCameraActiveInForeground(it.id) }
                    if (allInForeground) {
                        nextDelayMs = 10_000L
                    } else {
                        coroutineScope {
                            monitoredCameras.map { camera ->
                                async {
                                    semaphore.withPermit {
                                        if (isActive) {
                                            processCameraMotion(camera)
                                        }
                                    }
                                }
                            }.awaitAll()
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error en ciclo de vigilancia en segundo plano", e)
                }

                delay(nextDelayMs)
            }
        }
    }

    private suspend fun processCameraMotion(camera: CameraConfig) {
        if (isSnoozed(camera.id)) return
        // Si la cámara está abierta/activa en primer plano, UniversalCameraPlayer maneja la detección
        // para no competir por el mismo socket ni generar falsos positivos de reconexión.
        if (CameraMediaEngine.isCameraActiveInForeground(camera.id)) {
            pendingMotionHits.remove(camera.id)
            return
        }

        val now = System.currentTimeMillis()
        val backoffUntil = cameraFailBackoffUntilMs[camera.id] ?: 0L
        if (now < backoffUntil) return

        val lastAlert = lastTriggerTimes[camera.id] ?: 0L
        if (now - lastAlert < 15_000L) return

        val sampleBitmap = fetchCameraSampleFrame(camera)
        if (sampleBitmap == null) {
            val fails = (cameraConsecutiveFails[camera.id] ?: 0) + 1
            cameraConsecutiveFails[camera.id] = fails
            val backoffMs = when {
                fails >= 4 -> 60_000L
                fails >= 2 -> 30_000L
                else -> 15_000L
            }
            cameraFailBackoffUntilMs[camera.id] = now + backoffMs
            return
        }
        cameraConsecutiveFails.remove(camera.id)
        cameraFailBackoffUntilMs.remove(camera.id)

        val detector = motionDetectors.getOrPut(camera.id) {
            FrameMotionDetector(cooldownMs = 15_000L)
        }

        val lastSample = lastSampleTimes[camera.id] ?: 0L
        if (lastSample > 0L && now - lastSample > 12_000L) {
            detector.reset()
            pendingMotionHits.remove(camera.id)
        }
        lastSampleTimes[camera.id] = now

        val result = detector.inspectFrame(
            context = applicationContext,
            cameraId = camera.id,
            cameraName = camera.name,
            frameBitmap = sampleBitmap,
            notify = false,
            sensitivity = AppSettings.motionSensitivity
        )

        if (!result.isTriggered) {
            pendingMotionHits.remove(camera.id)
            sampleBitmap.recycle()
            return
        }

        // Confirmación de 2 cuadros consecutivos dentro de 10s para evitar falsas alarmas por I-frames o brillo inicial
        val prev = pendingMotionHits[camera.id]
        val hitCount = if (prev != null && now - prev.second <= 10_000L) prev.first + 1 else 1
        if (hitCount < 2) {
            pendingMotionHits[camera.id] = hitCount to now
            sampleBitmap.recycle()
            return
        }

        pendingMotionHits.remove(camera.id)
        lastTriggerTimes[camera.id] = now
        Log.i(TAG, "¡MOVIMIENTO CONFIRMADO en ${camera.name}! (${result.percent}% actividad)")

        withShortWakeLock(15_000L) {
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

    /**
     * Bucle independiente para Grabación Continua en Franja Horaria (AutoRecordMode.SCHEDULE).
     * Graba segmentos MP4 de las cámaras configuradas incluso con la app cerrada y los sube a Telegram.
     */
    private fun startScheduledRecordingLoop() {
        serviceScope.launch {
            try {
                while (isActive) {
                    var nextDelayMs = 3000L
                    try {
                        val allCameras = cachedCameras
                        val now = System.currentTimeMillis()
                        val hasAnyScheduled = allCameras.any { it.autoRecordMode == AutoRecordMode.SCHEDULE }
                        val insideSchedule = hasAnyScheduled && AppSettings.isInsideSchedule(now)
                        val targetCameras = if (insideSchedule) {
                            allCameras.filter {
                                it.autoRecordMode == AutoRecordMode.SCHEDULE &&
                                    !CameraMediaEngine.isCameraActiveInForeground(it.id)
                            }
                        } else {
                            emptyList()
                        }
                        if (!hasAnyScheduled || !insideSchedule) {
                            nextDelayMs = 30_000L
                        }
                        val targetIds = targetCameras.map { it.id }.toSet()
                        val segmentDurationMs = AppSettings.scheduleSegmentMinutes.coerceAtLeast(1) * 60_000L

                        // Detener sesiones que salieron de horario, pasaron a primer plano o cumplieron duración de segmento
                        val toStop = scheduledRecorders.entries.filter { (camId, session) ->
                            camId !in targetIds || (now - session.startedAtMs >= segmentDurationMs)
                        }.map { it.key }

                        for (camId in toStop) {
                            val session = scheduledRecorders.remove(camId) ?: continue
                            val elapsed = now - session.startedAtMs
                            withContext(Dispatchers.IO) {
                                session.recorder.stopAndSaveToGallery(
                                    sendToTelegram = elapsed >= 3_000L,
                                    captionLabel = "Grabación Continua (Franja Horaria)"
                                )
                            }
                        }

                        // Iniciar nuevas sesiones para cámaras en franja horaria
                        for (camera in targetCameras) {
                            if (!isActive) break
                            if (scheduledRecorders.containsKey(camera.id)) continue
                            val firstFrame = fetchCameraSampleFrame(camera) ?: continue
                            firstFrame.recycle()
                            val recorder = LiveMp4VideoRecorder(
                                context = applicationContext,
                                cameraName = camera.name,
                                fps = 2,
                                bitRate = 800_000
                            )
                            val started = recorder.startRecording(
                                scope = serviceScope,
                                frameProvider = { null },
                                onElapsedSeconds = {},
                                asyncFrameProvider = { fetchCameraSampleFrame(camera) }
                            )
                            if (started) {
                                scheduledRecorders[camera.id] = ScheduledRecSession(
                                    recorder = recorder,
                                    startedAtMs = System.currentTimeMillis(),
                                    cameraName = camera.name
                                )
                                Log.i(TAG, "Grabación continua horaria iniciada en segundo plano para ${camera.name}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error en bucle de grabación horaria", e)
                    }
                    delay(nextDelayMs)
                }
            } finally {
                withContext(NonCancellable) {
                    val remaining = scheduledRecorders.keys.toList()
                    for (camId in remaining) {
                        val session = scheduledRecorders.remove(camId) ?: continue
                        session.recorder.stopAndSaveToGallery(
                            sendToTelegram = (System.currentTimeMillis() - session.startedAtMs) >= 3_000L,
                            captionLabel = "Grabación Continua (Franja Horaria)"
                        )
                    }
                }
            }
        }
    }

    /**
     * Extrae un fotograma JPEG instantáneo de la cámara vía protocolo nativo (V380) o HTTP snapshot.
     */
    private suspend fun fetchCameraSampleFrame(camera: CameraConfig): Bitmap? = withContext(Dispatchers.IO) {
        // Para cámaras V380, conectar vía socket nativo al túnel y extraer el primer I-Frame H.264/H.265
        if (camera.isV380NativeProtocol()) {
            val v380Frame = try {
                V380NativeProtocol.fetchNativeSampleFrame(camera)
            } catch (_: Exception) {
                null
            }
            if (v380Frame != null) return@withContext v380Frame
        }

        val host = camera.host.trim()
        if (host.isBlank() || host == "0.0.0.0") return@withContext null

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
                urlsToTry.add("http://$host:$httpPort/cgi-bin/snapshot.cgi")
                urlsToTry.add("http://$host:$httpPort/video")
                urlsToTry.add("http://$host:$httpPort/stream")
                urlsToTry.add("http://$host:$httpPort/mjpeg/1")
            }
        }

        for (urlStr in urlsToTry) {
            val bmp = fetchHttpJpegWithAuth(urlStr, camera)
            if (bmp != null) return@withContext bmp
        }

        // Fallback RTSP para cámaras Hikvision/EZVIZ/Dahua/RTSP que no exponen snapshot HTTP
        if (camera.brand != CameraBrand.V380_PRO && camera.brand != CameraBrand.V720_NAXCLOW) {
            val cooldownUntil = rtspCooldownUntilMs[camera.id] ?: 0L
            if (System.currentTimeMillis() >= cooldownUntil) {
                val rtspUrl = camera.buildStreamUrl(useSubStream = true)
                if (rtspUrl.startsWith("rtsp://", ignoreCase = true)) {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(rtspUrl, emptyMap())
                        val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        rtspCooldownUntilMs[camera.id] = System.currentTimeMillis() + 6_000L
                        if (frame != null) return@withContext frame
                    } catch (_: Exception) {
                        rtspCooldownUntilMs[camera.id] = System.currentTimeMillis() + 60_000L
                    } finally {
                        try {
                            retriever.release()
                        } catch (_: Exception) {}
                    }
                }
            }
        }
        null
    }

    private fun fetchHttpJpegWithAuth(urlStr: String, camera: CameraConfig): Bitmap? {
        var conn: HttpURLConnection? = null
        try {
            val url = URL(urlStr)
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 900
                readTimeout = 1300
                requestMethod = "GET"
                if (camera.username.isNotBlank()) {
                    val auth = Base64.encodeToString("${camera.username}:${camera.password}".toByteArray(), Base64.NO_WRAP)
                    setRequestProperty("Authorization", "Basic $auth")
                }
            }
            conn.connect()

            if (conn.responseCode == 401 && camera.username.isNotBlank()) {
                val wwwAuth = conn.getHeaderField("WWW-Authenticate")
                conn.disconnect()
                conn = null
                if (wwwAuth != null && wwwAuth.startsWith("Digest", ignoreCase = true)) {
                    val uriPath = url.file.ifBlank { "/" }
                    val digestHeader = buildDigestAuthHeader(
                        wwwAuth = wwwAuth,
                        username = camera.username,
                        password = camera.password,
                        method = "GET",
                        uri = uriPath
                    )
                    if (digestHeader != null) {
                        conn = (url.openConnection() as HttpURLConnection).apply {
                            connectTimeout = 900
                            readTimeout = 1300
                            requestMethod = "GET"
                            setRequestProperty("Authorization", digestHeader)
                        }
                        conn.connect()
                    }
                }
            }

            if (conn != null && conn.responseCode in 200..299) {
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
                            if (bmp != null) return bmp
                            break
                        }
                    }
                    prev = b
                }
            }
        } catch (_: Exception) {
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {}
        }
        return null
    }

    private fun buildDigestAuthHeader(
        wwwAuth: String,
        username: String,
        password: String,
        method: String,
        uri: String
    ): String? {
        fun extractParam(key: String): String? {
            val regex = Regex("""$key=(?:"([^"]*)"|([^,\s]+))""", RegexOption.IGNORE_CASE)
            val m = regex.find(wwwAuth) ?: return null
            return m.groupValues[1].ifEmpty { m.groupValues[2] }
        }
        val realm = extractParam("realm") ?: return null
        val nonce = extractParam("nonce") ?: return null
        val qop = extractParam("qop")
        val opaque = extractParam("opaque")

        fun md5(input: String): String {
            val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray())
            return bytes.joinToString("") { "%02x".format(it) }
        }

        val ha1 = md5("$username:$realm:$password")
        val ha2 = md5("$method:$uri")
        val nc = "00000001"
        val cnonce = "frcam9f2a1"

        val useAuthQop = qop != null && qop.split(",").any { it.trim().equals("auth", ignoreCase = true) }
        val response = if (useAuthQop) {
            md5("$ha1:$nonce:$nc:$cnonce:auth:$ha2")
        } else {
            md5("$ha1:$nonce:$ha2")
        }

        val sb = StringBuilder()
        sb.append("Digest username=\"$username\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\", response=\"$response\"")
        if (useAuthQop) {
            sb.append(", qop=auth, nc=$nc, cnonce=\"$cnonce\"")
        }
        if (!opaque.isNullOrBlank()) {
            sb.append(", opaque=\"$opaque\"")
        }
        return sb.toString()
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

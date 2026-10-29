package com.frcam.app.media

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.Surface
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.frcam.app.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.random.Random
import com.frcam.app.data.AppSettings
import com.frcam.app.data.MotionSensitivity
import com.frcam.app.ai.CameraAiDetector
import com.frcam.app.ai.AiDetection
import com.frcam.app.ai.AiCategory

data class MotionEventRecord(
    val id: String,
    val cameraId: String,
    val cameraName: String,
    val timestampMs: Long,
    val formattedTime: String,
    val motionScorePercent: Float,
    val snapshot: Bitmap,
    val gifFile: File? = null,
    val animationFrames: List<Bitmap> = emptyList(),
    val aiLabel: String? = null
)

data class MotionDetectionResult(
    val isTriggered: Boolean,
    val percent: Float,
    val validPreFrames: List<Bitmap> = emptyList(),
    val aiResult: AiDetection? = null
)

object CameraMediaEngine {

    private const val MOTION_CHANNEL_ID = "frcam_motion_alerts"
    private val _motionEvents = MutableStateFlow<List<MotionEventRecord>>(emptyList())
    val motionEvents: StateFlow<List<MotionEventRecord>> = _motionEvents.asStateFlow()

    fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                MOTION_CHANNEL_ID,
                "Alertas de Movimiento (Estilo Ring)",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notificaciones instantáneas con clips animados de movimiento"
                enableVibration(true)
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(channel)
        }
    }

    /**
     * Determina si un fotograma capturado es completamente negro o vacío (debido a que el reproductor
     * o decodificador aún está inicializándose o conectando). Evita que los GIFs arranquen en negro.
     */
    fun isBlackOrEmptyFrame(bitmap: Bitmap): Boolean {
        if (bitmap.isRecycled) return true
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return true
        var nonBlackCount = 0
        val stepX = (w / 12).coerceAtLeast(1)
        val stepY = (h / 8).coerceAtLeast(1)
        var total = 0
        for (y in 0 until h step stepY) {
            for (x in 0 until w step stepX) {
                val c = bitmap.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                // Cualquier píxel con color o iluminación superior a la penumbra absoluta
                if (r > 18 || g > 18 || b > 18) {
                    nonBlackCount++
                }
                total++
            }
        }
        return total > 0 && (nonBlackCount.toFloat() / total.toFloat()) < 0.06f
    }

    fun createMotionGif(context: Context, cameraId: String, frames: List<Bitmap>): File? {
        val nonBlackFrames = frames.filter { !it.isRecycled && !isBlackOrEmptyFrame(it) }
        if (nonBlackFrames.isEmpty()) return null
        return try {
            val cacheDir = File(context.cacheDir, "motion_gifs").apply { mkdirs() }
            val gifFile = File(cacheDir, "motion_${cameraId}_${System.currentTimeMillis()}.gif")
            val fos = java.io.FileOutputStream(gifFile)
            val encoder = AnimatedGifEncoder()
            encoder.start(fos)
            encoder.setRepeat(0)
            encoder.setDelay(220) // ~4.5 FPS reproducción fluida del movimiento
            for (f in nonBlackFrames) {
                val scaled = Bitmap.createScaledBitmap(f, 320, 180, true)
                encoder.addFrame(scaled)
                if (scaled !== f) scaled.recycle()
            }
            encoder.finish()
            fos.close()
            gifFile
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Dispatches a Ring-style rich push notification with an animated GIF movement preview
     * and records the event in the in-app motion history.
     */
    fun notifyMotionDetected(
        context: Context,
        cameraId: String,
        cameraName: String,
        motionPercent: Float,
        rawBitmap: Bitmap,
        burstFrames: List<Bitmap> = emptyList(),
        aiDetection: AiDetection? = null
    ) {
        ensureNotificationChannel(context)

        val stampedBitmap = stampBitmapWithOverlay(rawBitmap, cameraName)
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val timeStr = sdf.format(Date())

        val framesForGif = if (burstFrames.isNotEmpty()) {
            burstFrames.map { stampBitmapWithOverlay(it, cameraName) }
        } else {
            listOf(stampedBitmap, stampedBitmap, stampedBitmap)
        }

        val gifFile = createMotionGif(context, cameraId, framesForGif)

        val hasSmartAi = aiDetection != null && aiDetection.category != AiCategory.GENERAL
        val aiBadge = if (hasSmartAi && aiDetection != null) aiDetection.displayBadge else null

        val event = MotionEventRecord(
            id = "${cameraId}_${System.currentTimeMillis()}",
            cameraId = cameraId,
            cameraName = cameraName,
            timestampMs = System.currentTimeMillis(),
            formattedTime = timeStr,
            motionScorePercent = motionPercent,
            snapshot = stampedBitmap,
            gifFile = gifFile,
            animationFrames = framesForGif,
            aiLabel = aiBadge
        )
        _motionEvents.value = (listOf(event) + _motionEvents.value).take(30)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_camera_id", cameraId)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            cameraId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val savedGifUri = gifFile?.let { saveGifToGallery(context, cameraName, it) }

        val notifTitle = when {
            aiDetection?.category == AiCategory.PERSON -> "🚶 ¡Persona detectada! — $cameraName"
            aiDetection?.category == AiCategory.PET -> "${aiDetection.emoji} ¡${aiDetection.label} detectada! — $cameraName"
            aiDetection?.category == AiCategory.VEHICLE -> "${aiDetection.emoji} ¡${aiDetection.label} detectado! — $cameraName"
            hasSmartAi && aiDetection != null -> "${aiDetection.emoji} ${aiDetection.label} — $cameraName"
            else -> "🚨 Movimiento detectado: $cameraName"
        }

        val notifText = when {
            hasSmartAi && aiDetection != null -> "${aiDetection.emoji} ${aiDetection.label} (${"%.0f".format(aiDetection.confidence * 100)}% certeza) • $timeStr • Clip animado"
            else -> "A las $timeStr (${"%.1f".format(motionPercent)}% actividad) • Clip animado"
        }

        val builder = NotificationCompat.Builder(context, MOTION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(notifTitle)
            .setContentText(notifText)
            .setLargeIcon(stampedBitmap)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        // Vista expandida ANIMADA: ViewFlipper con los cuadros del movimiento (Android no anima GIF en BigPicture)
        var expandedAnimated = false
        try {
            val picks = pickEvenly(framesForGif, 6)
            if (picks.isNotEmpty()) {
                val big = android.widget.RemoteViews(context.packageName, com.frcam.app.R.layout.notification_motion_big)
                val expandedTitle = if (hasSmartAi && aiDetection != null) {
                    "$cameraName • ${aiDetection.emoji} ${aiDetection.label} (${"%.0f".format(aiDetection.confidence * 100)}%) • $timeStr"
                } else {
                    "$cameraName • $timeStr • ${"%.1f".format(motionPercent)}% actividad"
                }
                big.setTextViewText(com.frcam.app.R.id.motion_title, expandedTitle)
                val ids = intArrayOf(
                    com.frcam.app.R.id.motion_f0, com.frcam.app.R.id.motion_f1, com.frcam.app.R.id.motion_f2,
                    com.frcam.app.R.id.motion_f3, com.frcam.app.R.id.motion_f4, com.frcam.app.R.id.motion_f5
                )
                for (i in ids.indices) {
                    val src = picks[i % picks.size]
                    val w = 400
                    val h = (w * src.height / src.width.toFloat()).toInt().coerceAtLeast(1)
                    big.setImageViewBitmap(ids[i], Bitmap.createScaledBitmap(src, w, h, true))
                }
                builder.setStyle(NotificationCompat.DecoratedCustomViewStyle())
                builder.setCustomBigContentView(big)
                expandedAnimated = true
            }
        } catch (_: Exception) {
        }
        if (!expandedAnimated) {
            builder.setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(stampedBitmap)
                    .bigLargeIcon(null as Bitmap?)
                    .setSummaryText("$cameraName • $timeStr")
            )
        }

        val contentUri: android.net.Uri? = if (gifFile != null && gifFile.exists()) {
            try {
                androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    gifFile
                )
            } catch (_: Exception) {
                null
            }
        } else null

        val targetUri = contentUri ?: savedGifUri
        if (targetUri != null) {
            // Grant explicit URI read permission to Samsung Gallery and Google Photos packages
            if (contentUri != null) {
                val packagesToGrant = listOf(
                    "com.sec.android.gallery3d",
                    "com.google.android.apps.photos",
                    "com.android.gallery3d",
                    "com.google.android.gallery3d"
                )
                for (pkg in packagesToGrant) {
                    try {
                        context.grantUriPermission(pkg, contentUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (_: Exception) {}
                }
            }

            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(targetUri, "image/gif")
                clipData = android.content.ClipData.newRawUri("FrCam Motion GIF", targetUri)
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            }
            val chooser = Intent.createChooser(viewIntent, "Ver GIF de movimiento").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val viewPending = PendingIntent.getActivity(
                context,
                targetUri.hashCode(),
                chooser,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_menu_gallery, "Ver GIF", viewPending)
        }

        NotificationManagerCompat.from(context)
            .notify(cameraId.hashCode() xor Random.Default.nextInt(1, 9999), builder.build())
    }

    private fun pickEvenly(frames: List<Bitmap>, count: Int): List<Bitmap> {
        if (frames.size <= count) return frames
        return List(count) { i -> frames[i * (frames.size - 1) / (count - 1)] }
    }

    /** Guarda el GIF del movimiento en Galería (Pictures/FrCam/Movimientos) e indexa para Samsung y Google Photos. */
    fun saveGifToGallery(context: Context, cameraName: String, gifFile: File): android.net.Uri? {
        return try {
            val nowMs = System.currentTimeMillis()
            val nowSec = nowMs / 1000
            val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            val safeName = cameraName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val fileName = "FrCam_${safeName}_${sdf.format(Date(nowMs))}.gif"
            val resolver = context.contentResolver

            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/gif")
                put(MediaStore.Images.Media.DATE_ADDED, nowSec)
                put(MediaStore.Images.Media.DATE_MODIFIED, nowSec)
                put(MediaStore.Images.Media.DATE_TAKEN, nowMs)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/FrCam/Movimientos")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { out ->
                gifFile.inputStream().use { input -> input.copyTo(out) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }

            // Notificar a Samsung Gallery y Google Photos para indexación inmediata
            try {
                val projection = arrayOf(MediaStore.Images.Media.DATA)
                val cursor = resolver.query(uri, projection, null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val path = it.getString(0)
                        if (!path.isNullOrBlank()) {
                            android.media.MediaScannerConnection.scanFile(
                                context,
                                arrayOf(path),
                                arrayOf("image/gif"),
                                null
                            )
                        }
                    }
                }
            } catch (_: Exception) {}

            uri
        } catch (_: Exception) {
            null
        }
    }
    private fun stampBitmapWithOverlay(source: Bitmap, cameraName: String): Bitmap {
        return try {
            val copy = source.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(copy)
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val label = "FrCam • $cameraName • ${sdf.format(Date())}"

            val bgPaint = Paint().apply {
                color = Color.argb(150, 0, 0, 0)
                style = Paint.Style.FILL
            }
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = (copy.height * 0.036f).coerceIn(18f, 42f)
                isFakeBoldText = true
            }
            val pad = 12f
            val textWidth = textPaint.measureText(label)
            val boxHeight = textPaint.textSize + pad * 2
            canvas.drawRect(
                0f,
                copy.height - boxHeight,
                textWidth + pad * 3,
                copy.height.toFloat(),
                bgPaint
            )
            canvas.drawText(label, pad, copy.height - pad * 1.2f, textPaint)
            copy
        } catch (_: Exception) {
            source
        }
    }

    /**
     * Saves a single JPEG snapshot into Android Gallery (Pictures/FrCam).
     */
    suspend fun saveSnapshotToGallery(
        context: Context,
        cameraName: String,
        bitmap: Bitmap
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val stamped = stampBitmapWithOverlay(bitmap, cameraName)
            val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            val safeName = cameraName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val fileName = "FrCam_${safeName}_${sdf.format(Date())}.jpg"

            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/FrCam")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@withContext Result.failure(Exception("No se pudo crear imagen en Galería"))

            resolver.openOutputStream(uri)?.use { out ->
                stamped.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }

            Result.success("Foto guardada en Galería (Pictures/FrCam/$fileName)")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

/**
 * Per-camera real-time frame motion detector.
 * Downscales frames to a 64x36 luminance grid, applies anti-leaf cluster filtering,
 * supports configurable sensitivity and triggers Google ML Kit on-device AI classification.
 */
class FrameMotionDetector(
    private val gridW: Int = 64,
    private val gridH: Int = 36,
    private val cooldownMs: Long = 10_000L
) {
    private var previousLuma: IntArray? = null
    private var warmupFramesRemaining = 3
    private var lastTriggerTimeMs = 0L
    private val rollingFrames = mutableListOf<Bitmap>()

    suspend fun inspectFrame(
        context: Context,
        cameraId: String,
        cameraName: String,
        frameBitmap: Bitmap,
        notify: Boolean = true,
        sensitivity: MotionSensitivity = AppSettings.motionSensitivity
    ): MotionDetectionResult {
        return try {
            // 1. Ignorar completamente fotogramas negros o vacíos de arranque para no contaminar el GIF
            if (CameraMediaEngine.isBlackOrEmptyFrame(frameBitmap)) {
                return MotionDetectionResult(isTriggered = false, percent = 0f)
            }

            val copyForBuffer = frameBitmap.copy(Bitmap.Config.ARGB_8888, false)
            rollingFrames.add(copyForBuffer)
            if (rollingFrames.size > 3) {
                val old = rollingFrames.removeAt(0)
                if (!old.isRecycled) old.recycle()
            }

            val scaled = Bitmap.createScaledBitmap(frameBitmap, gridW, gridH, false)
            val pixels = IntArray(gridW * gridH)
            scaled.getPixels(pixels, 0, gridW, 0, 0, gridW, gridH)

            val currentLuma = IntArray(gridW * gridH)
            for (i in pixels.indices) {
                val c = pixels[i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                currentLuma[i] = (r * 3 + g * 4 + b) shr 3
            }

            if (warmupFramesRemaining > 0) {
                warmupFramesRemaining--
                previousLuma = currentLuma
                return MotionDetectionResult(isTriggered = false, percent = 0f)
            }

            val prev = previousLuma
            previousLuma = currentLuma
            if (prev == null) return MotionDetectionResult(isTriggered = false, percent = 0f)

            var minX = gridW
            var maxX = -1
            var minY = gridH
            var maxY = -1
            var changedCells = 0

            for (y in 0 until gridH) {
                val rowOffset = y * gridW
                for (x in 0 until gridW) {
                    val idx = rowOffset + x
                    if (abs(currentLuma[idx] - prev[idx]) >= sensitivity.pixelDelta) {
                        changedCells++
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                        if (y < minY) minY = y
                        if (y > maxY) maxY = y
                    }
                }
            }

            val percent = (changedCells * 100f) / (gridW * gridH).toFloat()
            val now = System.currentTimeMillis()

            // Filtro anti-hojas/viento: evaluar compacidad del área de cambio
            var isClustered = true
            if (changedCells > 0 && maxX >= minX && maxY >= minY) {
                val bboxW = maxX - minX + 1
                val bboxH = maxY - minY + 1
                val bboxArea = bboxW * bboxH
                val compactness = changedCells.toFloat() / bboxArea.toFloat()
                val bboxScreenPercent = (bboxArea * 100f) / (gridW * gridH).toFloat()

                // Si el cambio cubre más de 35% de la pantalla pero la compacidad es ínfima,
                // son ramas/hojas agitándose con el viento de forma dispersa.
                if (bboxScreenPercent > 35f && compactness < sensitivity.clusterRatioMin) {
                    isClustered = false
                }
            }

            val meetsThreshold = percent >= sensitivity.minAreaPercent && isClustered

            if (meetsThreshold && (now - lastTriggerTimeMs) >= cooldownMs) {
                // Análisis IA inteligente si está habilitado
                val aiResult = if (AppSettings.aiDetectionEnabled) {
                    CameraAiDetector.analyzeFrame(frameBitmap)
                } else null

                // Filtro estricto IA: si el usuario configuró "Filtrar solo con IA", ignorar
                // movimientos que no sean Persona, Mascota o Vehículo (o si la IA detectó solo vegetación)
                val shouldSuppress = if (AppSettings.aiDetectionEnabled && AppSettings.aiFilterOnlySmart) {
                    aiResult == null || !aiResult.isSmartTarget || aiResult.category == AiCategory.NATURE
                } else false

                if (!shouldSuppress) {
                    lastTriggerTimeMs = now
                    val validPre = rollingFrames
                        .filter { !it.isRecycled && !CameraMediaEngine.isBlackOrEmptyFrame(it) }
                        .map { it.copy(Bitmap.Config.ARGB_8888, false) }

                    if (notify) {
                        val copyForAlert = frameBitmap.copy(Bitmap.Config.ARGB_8888, false)
                        CameraMediaEngine.notifyMotionDetected(
                            context = context,
                            cameraId = cameraId,
                            cameraName = cameraName,
                            motionPercent = percent,
                            rawBitmap = copyForAlert,
                            burstFrames = validPre + copyForAlert,
                            aiDetection = aiResult
                        )
                    }

                    return MotionDetectionResult(
                        isTriggered = true,
                        percent = percent,
                        validPreFrames = validPre,
                        aiResult = aiResult
                    )
                }
            }
            MotionDetectionResult(isTriggered = false, percent = percent)
        } catch (_: Exception) {
            MotionDetectionResult(isTriggered = false, percent = 0f)
        }
    }

    fun reset() {
        previousLuma = null
        warmupFramesRemaining = 3
        rollingFrames.forEach { if (!it.isRecycled) it.recycle() }
        rollingFrames.clear()
    }
}

/**
 * Hardware-accelerated MP4 Video Recorder that captures live frames from any camera protocol
 * (V380 Cloud/LAN, Eye4, V720, RTSP, MJPEG, Ring) and encodes a standard H.264 .mp4 file into
 * Android's Gallery (Movies/FrCam).
 */
class LiveMp4VideoRecorder(
    private val context: Context,
    private val cameraName: String,
    private val width: Int = 1280,
    private val height: Int = 720,
    private val fps: Int = 12
) {
    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private var tempFile: File? = null
    private var recordJob: Job? = null

    @Volatile
    var isRecording: Boolean = false
        private set

    fun startRecording(
        scope: CoroutineScope,
        frameProvider: () -> Bitmap?,
        onElapsedSeconds: (Int) -> Unit
    ): Boolean {
        if (isRecording) return false
        return try {
            val safeName = cameraName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            val file = File(context.cacheDir, "FrCam_${safeName}_${sdf.format(Date())}.mp4")
            tempFile = file

            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 2_500_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            }

            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = codec.createInputSurface()
            codec.start()

            val mediaMuxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            encoder = codec
            inputSurface = surface
            muxer = mediaMuxer
            trackIndex = -1
            muxerStarted = false
            isRecording = true

            recordJob = scope.launch(Dispatchers.Default) {
                val destRect = Rect(0, 0, width, height)
                val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                val frameIntervalMs = 1000L / fps
                val startMs = System.currentTimeMillis()
                val bufferInfo = MediaCodec.BufferInfo()

                while (isActive && isRecording) {
                    val loopStart = System.currentTimeMillis()
                    val bmp = withContext(Dispatchers.Main) {
                        frameProvider()
                    }
                    if (bmp != null && !bmp.isRecycled) {
                        try {
                            val canvas = surface.lockHardwareCanvas()
                            canvas.drawColor(Color.BLACK)
                            canvas.drawBitmap(bmp, null, destRect, paint)
                            surface.unlockCanvasAndPost(canvas)
                        } catch (_: Exception) {
                        }
                    }

                    drainEncoder(codec, mediaMuxer, bufferInfo, endOfStream = false)

                    val elapsedSec = ((System.currentTimeMillis() - startMs) / 1000L).toInt()
                    onElapsedSeconds(elapsedSec)

                    val spent = System.currentTimeMillis() - loopStart
                    val wait = (frameIntervalMs - spent).coerceAtLeast(10L)
                    delay(wait)
                }
            }
            true
        } catch (_: Exception) {
            isRecording = false
            false
        }
    }

    suspend fun stopAndSaveToGallery(): Result<String> = withContext(Dispatchers.IO) {
        if (!isRecording) {
            return@withContext Result.failure(Exception("No hay grabación activa"))
        }
        isRecording = false
        try {
            recordJob?.join()
        } catch (_: Exception) {
        }

        val codec = encoder
        val mediaMuxer = muxer
        val file = tempFile

        try {
            if (codec != null && mediaMuxer != null) {
                val bufferInfo = MediaCodec.BufferInfo()
                drainEncoder(codec, mediaMuxer, bufferInfo, endOfStream = true)
            }
        } catch (_: Exception) {
        }

        try {
            codec?.stop()
            codec?.release()
        } catch (_: Exception) {
        }
        try {
            inputSurface?.release()
        } catch (_: Exception) {
        }
        try {
            if (muxerStarted) {
                mediaMuxer?.stop()
            }
            mediaMuxer?.release()
        } catch (_: Exception) {
        }

        encoder = null
        inputSurface = null
        muxer = null

        if (file == null || !file.exists() || file.length() < 512L) {
            return@withContext Result.failure(Exception("El video grabado es demasiado corto o no recibió fotogramas"))
        }

        return@withContext try {
            val fileName = file.name
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/FrCam")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@withContext Result.failure(Exception("No se pudo guardar el MP4 en Galería"))

            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { input ->
                    input.copyTo(out)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            file.delete()

            Result.success("Video guardado en Galería: Movies/FrCam/$fileName")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun drainEncoder(
        codec: MediaCodec,
        mediaMuxer: MediaMuxer,
        bufferInfo: MediaCodec.BufferInfo,
        endOfStream: Boolean
    ) {
        if (endOfStream) {
            try {
                codec.signalEndOfInputStream()
            } catch (_: Exception) {
                return
            }
        }

        while (true) {
            val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
            if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break
            } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!muxerStarted) {
                    trackIndex = mediaMuxer.addTrack(codec.outputFormat)
                    mediaMuxer.start()
                    muxerStarted = true
                }
            } else if (outIndex >= 0) {
                val encodedData = codec.getOutputBuffer(outIndex)
                if (encodedData != null && bufferInfo.size > 0 && muxerStarted) {
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        mediaMuxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                    }
                }
                codec.releaseOutputBuffer(outIndex, false)
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break
                }
            } else {
                break
            }
        }
    }
}

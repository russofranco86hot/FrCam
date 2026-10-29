package com.frcam.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Calendar

/**
 * Niveles de sensibilidad del detector de movimiento.
 * Permite filtrar el viento, lluvia ligera y hojas de árboles en movimiento.
 */
enum class MotionSensitivity(
    val id: String,
    val title: String,
    val description: String,
    val minAreaPercent: Float,
    val pixelDelta: Int,
    val clusterRatioMin: Float
) {
    LOW(
        id = "low",
        title = "Baja (Anti-hojas y viento)",
        description = "Filtra hojas, ramas, lluvia y sombras. Requiere que un objeto denso (persona, vehículo o perro) ocupe al menos un 10% del encuadre.",
        minAreaPercent = 10.0f,
        pixelDelta = 32,
        clusterRatioMin = 0.22f
    ),
    MEDIUM(
        id = "medium",
        title = "Media (Equilibrada)",
        description = "Recomendada para patios y entradas. Filtra oscilaciones leves pero alerta eficazmente ante personas y mascotas.",
        minAreaPercent = 6.0f,
        pixelDelta = 26,
        clusterRatioMin = 0.15f
    ),
    HIGH(
        id = "high",
        title = "Alta (Sensible)",
        description = "Máxima sensibilidad ante cualquier cambio leve. Recomendada para interiores cerrados.",
        minAreaPercent = 3.0f,
        pixelDelta = 20,
        clusterRatioMin = 0.08f
    );

    companion object {
        fun fromId(id: String?): MotionSensitivity =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: MEDIUM
    }
}

/** Modos de grabación automática por cámara (se guardan en [com.frcam.app.model.CameraConfig.autoRecordMode]). */
object AutoRecordMode {
    const val OFF = "OFF"
    const val MOTION = "MOTION"
    const val SCHEDULE = "SCHEDULE"

    fun label(mode: String): String = when (mode) {
        MOTION -> "Al detectar movimiento"
        SCHEDULE -> "Franja horaria"
        else -> "Desactivada"
    }
}

/**
 * Ajustes generales de la aplicación (apariencia, grabación, sensibilidad y detección IA).
 * Los valores son estados de Compose: la UI se recompone sola al cambiarlos.
 */
object AppSettings {

    const val CREATOR = "russofranco86"

    private const val PREFS = "frcam_app_settings"
    private lateinit var prefs: SharedPreferences

    var amoled by mutableStateOf(true)
        private set
    var motionClipSeconds by mutableIntStateOf(20)
        private set
    var scheduleStartMinutes by mutableIntStateOf(22 * 60)
        private set
    var scheduleEndMinutes by mutableIntStateOf(6 * 60)
        private set
    var scheduleSegmentMinutes by mutableIntStateOf(5)
        private set
    var motionSensitivity by mutableStateOf(MotionSensitivity.MEDIUM)
        private set
    var aiDetectionEnabled by mutableStateOf(true)
        private set
    var aiFilterOnlySmart by mutableStateOf(false)
        private set

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        amoled = prefs.getBoolean("amoled", true)
        motionClipSeconds = prefs.getInt("motion_clip_seconds", 20)
        scheduleStartMinutes = prefs.getInt("schedule_start", 22 * 60)
        scheduleEndMinutes = prefs.getInt("schedule_end", 6 * 60)
        scheduleSegmentMinutes = prefs.getInt("schedule_segment", 5)
        motionSensitivity = MotionSensitivity.fromId(prefs.getString("motion_sensitivity", "medium"))
        aiDetectionEnabled = prefs.getBoolean("ai_detection_enabled", true)
        aiFilterOnlySmart = prefs.getBoolean("ai_filter_smart_only", false)
    }

    fun updateAmoled(value: Boolean) {
        amoled = value
        prefs.edit().putBoolean("amoled", value).apply()
    }

    fun updateMotionClipSeconds(value: Int) {
        motionClipSeconds = value.coerceIn(5, 300)
        prefs.edit().putInt("motion_clip_seconds", motionClipSeconds).apply()
    }

    fun updateMotionSensitivity(value: MotionSensitivity) {
        motionSensitivity = value
        prefs.edit().putString("motion_sensitivity", value.id).apply()
    }

    fun updateAiDetectionEnabled(value: Boolean) {
        aiDetectionEnabled = value
        prefs.edit().putBoolean("ai_detection_enabled", value).apply()
    }

    fun updateAiFilterOnlySmart(value: Boolean) {
        aiFilterOnlySmart = value
        prefs.edit().putBoolean("ai_filter_smart_only", value).apply()
    }

    fun updateSchedule(startMinutes: Int, endMinutes: Int) {
        scheduleStartMinutes = startMinutes.coerceIn(0, 1439)
        scheduleEndMinutes = endMinutes.coerceIn(0, 1439)
        prefs.edit()
            .putInt("schedule_start", scheduleStartMinutes)
            .putInt("schedule_end", scheduleEndMinutes)
            .apply()
    }

    fun updateScheduleSegmentMinutes(value: Int) {
        scheduleSegmentMinutes = value.coerceIn(1, 60)
        prefs.edit().putInt("schedule_segment", scheduleSegmentMinutes).apply()
    }

    /** ¿La hora actual cae dentro de la franja configurada? (soporta franjas que cruzan la medianoche). */
    fun isInsideSchedule(nowMillis: Long = System.currentTimeMillis()): Boolean {
        val cal = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val start = scheduleStartMinutes
        val end = scheduleEndMinutes
        return when {
            start == end -> true // franja de 24 h
            start < end -> now in start until end
            else -> now >= start || now < end
        }
    }

    fun formatMinutes(total: Int): String = "%02d:%02d".format(total / 60, total % 60)
}

package com.frcam.app.receiver

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.frcam.app.service.CameraSurveillanceService

/**
 * Receptor de acciones rápidas disparadas desde los botones de las notificaciones
 * de alerta de movimiento (ej. "Silenciar 30m").
 */
class MotionActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_SNOOZE_30M = "com.frcam.app.ACTION_SNOOZE_30M"
        const val ACTION_DISMISS = "com.frcam.app.ACTION_DISMISS"
        const val EXTRA_CAMERA_ID = "extra_camera_id"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
        const val EXTRA_CAMERA_NAME = "extra_camera_name"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val cameraId = intent.getStringExtra(EXTRA_CAMERA_ID).orEmpty()
        val notifId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        val cameraName = intent.getStringExtra(EXTRA_CAMERA_NAME) ?: "Cámara"

        if (notifId != 0) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(notifId)
        }

        when (intent.action) {
            ACTION_SNOOZE_30M -> {
                if (cameraId.isNotBlank()) {
                    CameraSurveillanceService.snoozeCamera(cameraId, 30 * 60 * 1000L)
                    Toast.makeText(context, "Alertas de \"$cameraName\" silenciadas por 30 minutos", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

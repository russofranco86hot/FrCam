package com.frcam.app.ai

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

enum class AiCategory(val title: String, val emoji: String) {
    PERSON("Persona", "🚶"),
    PET("Mascota", "🐕"),
    VEHICLE("Vehículo", "🚗"),
    NATURE("Vegetación / Viento", "🍃"),
    OBJECT("Objeto", "📦"),
    GENERAL("Movimiento", "🚨")
}

data class AiDetection(
    val category: AiCategory,
    val label: String,
    val confidence: Float,
    val emoji: String,
    val isSmartTarget: Boolean
) {
    val displayBadge: String
        get() = if (confidence > 0f) "$emoji $label ${"%.0f".format(confidence * 100)}%" else "$emoji $label"
}

/**
 * Motor de detección inteligente por IA (Google ML Kit on-device).
 * Analiza cuadros de video en tiempo real sin enviar datos a la nube para clasificar:
 * - Personas (hombre, mujer, niño, peatón)
 * - Mascotas (perros, gatos, pájaros, animales)
 * - Vehículos (autos, motos, bicicletas, camiones)
 * - Vegetación (hojas, plantas, ramas movidas por viento)
 */
object CameraAiDetector {

    private val labeler by lazy {
        val options = ImageLabelerOptions.Builder()
            .setConfidenceThreshold(0.45f)
            .build()
        ImageLabeling.getClient(options)
    }

    suspend fun analyzeFrame(bitmap: Bitmap): AiDetection {
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val labels = suspendCancellableCoroutine { cont ->
                labeler.process(image)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resume(emptyList()) }
            }

            if (labels.isEmpty()) {
                return AiDetection(AiCategory.GENERAL, "Movimiento", 0f, "🚨", false)
            }

            // 1. Detectar Personas (máxima prioridad)
            for (lbl in labels) {
                val t = lbl.text.lowercase()
                if (t in listOf("person", "human", "man", "woman", "girl", "boy", "child", "baby", "pedestrian", "crowd")) {
                    return AiDetection(
                        category = AiCategory.PERSON,
                        label = "Persona",
                        confidence = lbl.confidence,
                        emoji = "🚶",
                        isSmartTarget = true
                    )
                }
            }

            // 2. Detectar Mascotas (Perros, Gatos, Animales)
            for (lbl in labels) {
                val t = lbl.text.lowercase()
                if (t in listOf("dog", "puppy", "hound", "canidae", "canine")) {
                    return AiDetection(
                        category = AiCategory.PET,
                        label = "Perro",
                        confidence = lbl.confidence,
                        emoji = "🐕",
                        isSmartTarget = true
                    )
                }
                if (t in listOf("cat", "kitten", "feline", "felidae")) {
                    return AiDetection(
                        category = AiCategory.PET,
                        label = "Gato",
                        confidence = lbl.confidence,
                        emoji = "🐈",
                        isSmartTarget = true
                    )
                }
                if (t in listOf("pet", "animal", "mammal", "bird", "carnivore")) {
                    return AiDetection(
                        category = AiCategory.PET,
                        label = "Mascota",
                        confidence = lbl.confidence,
                        emoji = "🐾",
                        isSmartTarget = true
                    )
                }
            }

            // 3. Detectar Vehículos
            for (lbl in labels) {
                val t = lbl.text.lowercase()
                if (t in listOf("car", "vehicle", "automobile", "motorcycle", "bicycle", "truck", "van", "bus", "transportation")) {
                    val name = when {
                        "motorcycle" in t -> "Moto"
                        "bicycle" in t -> "Bicicleta"
                        "truck" in t -> "Camión"
                        else -> "Vehículo"
                    }
                    return AiDetection(
                        category = AiCategory.VEHICLE,
                        label = name,
                        confidence = lbl.confidence,
                        emoji = when (name) {
                            "Moto" -> "🏍️"
                            "Bicicleta" -> "🚲"
                            else -> "🚗"
                        },
                        isSmartTarget = true
                    )
                }
            }

            // 4. Detectar si es solo vegetación / hojas / viento
            val best = labels.maxByOrNull { it.confidence }
            val bestText = best?.text?.lowercase().orEmpty()
            if (bestText in listOf("plant", "tree", "leaf", "flora", "flower", "grass", "branch", "vegetation", "lawn")) {
                return AiDetection(
                    category = AiCategory.NATURE,
                    label = "Vegetación / Viento",
                    confidence = best?.confidence ?: 0.5f,
                    emoji = "🍃",
                    isSmartTarget = false
                )
            }

            // 5. Otro objeto genérico detectado
            if (best != null) {
                AiDetection(
                    category = AiCategory.OBJECT,
                    label = best.text,
                    confidence = best.confidence,
                    emoji = "📦",
                    isSmartTarget = false
                )
            } else {
                AiDetection(AiCategory.GENERAL, "Movimiento", 0f, "🚨", false)
            }
        } catch (_: Exception) {
            AiDetection(AiCategory.GENERAL, "Movimiento", 0f, "🚨", false)
        }
    }
}

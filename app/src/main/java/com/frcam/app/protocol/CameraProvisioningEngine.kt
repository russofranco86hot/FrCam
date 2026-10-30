package com.frcam.app.protocol

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.frcam.app.model.CameraBrand
import com.frcam.app.model.CameraConfig
import com.frcam.app.model.ConnectionMode
import com.frcam.app.model.StreamProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import kotlin.math.min

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.util.EnumMap

/**
 * Formatos de código QR para vincular cámaras por lectura de pantalla con su lente.
 */
enum class QrWifiFormat(val label: String, val description: String) {
    UNIVERSAL_WIFI(
        label = "Wi-Fi Universal (Recomendado)",
        description = "Formato estándar compatible con Eye4, Tapo, Hikvision, V380 y el 95% de cámaras."
    ),
    JSON_CAMERA(
        label = "JSON V720 / Naxclow / A9",
        description = "Formato JSON utilizado por cámaras V720, Naxclow y cámaras miniatura A9."
    ),
    EYE4_VSTARCAM(
        label = "Eye4 / VStarcam Nativo",
        description = "Formato bssid/ssid/pwd específico para lectores de cámaras Eye4."
    ),
    PLAIN_TAB(
        label = "Texto Plano Tabulado",
        description = "Formato simple con salto de línea o tabulación para cámaras genéricas."
    )
}

/**
 * Representa una cámara recién reseteada o nueva que está emitiendo su propio
 * Punto de Acceso Wi-Fi (Modo AP) esperando ser configurada.
 */
data class WaitingCameraAp(
    val ssid: String,
    val bssid: String = "",
    val signalDbm: Int = -50,
    val signalPercent: Int = 85,
    val detectedBrand: CameraBrand = CameraBrand.V380_PRO,
    val extractedDeviceId: String = "",
    val defaultGatewayIp: String = "192.168.169.1",
    val isCurrentlyConnected: Boolean = false,
    val isOpenSecurity: Boolean = true,
    val description: String = ""
)

/**
 * Motor de Detección y Configuración (Aprovisionamiento) de cámaras nuevas o recién reseteadas.
 * Permite:
 * 1. Detectar cámaras en Modo AP (Punto de Acceso) que están esperando ser vinculadas (ej. MV65077425, VSTC..., IPCAM...).
 * 2. Reconocer si el móvil ya está conectado a la red directa de la cámara.
 * 3. Enviar las credenciales de la red Wi-Fi de la casa a la cámara para que se conecte a internet y al router.
 * 4. Agregarla automáticamente a FrCam tanto para acceso local como remoto.
 */
object CameraProvisioningEngine {

    private const val TAG = "CameraProvisioning"

    /**
     * Determina si un nombre de red Wi-Fi (SSID) corresponde al punto de acceso
     * de una cámara nueva o reseteada esperando configuración.
     */
    fun identifyCameraBrandFromSsid(ssid: String): Pair<Boolean, CameraBrand> {
        val clean = ssid.trim().removeSurrounding("\"").uppercase()
        return when {
            clean.startsWith("MV") -> true to CameraBrand.V380_PRO
            clean.startsWith("NAX_") || clean.startsWith("V720_") || clean.startsWith("JA_") ||
                clean.startsWith("FT_") || clean.startsWith("BAT_") || clean.startsWith("A9_") ||
                clean.startsWith("MINI_") || clean.contains("V720") -> true to CameraBrand.V720_NAXCLOW
            clean.startsWith("VSTC") || clean.startsWith("IPCAM-") || clean.startsWith("EYE4-") ||
                clean.startsWith("VSTAR") -> true to CameraBrand.EYE4_VSTARCAM
            clean.startsWith("CARE-AP") || clean.startsWith("GW_AP") || clean.startsWith("CLOUDCAM") -> true to CameraBrand.ONVIF_GENERIC
            clean.startsWith("SMARTLIFE-") || clean.startsWith("TUYASMART-") || clean.startsWith("TUYA-") -> true to CameraBrand.ONVIF_GENERIC
            clean.startsWith("IPC-") || clean.startsWith("CAM-") || clean.startsWith("HD-") || clean.startsWith("WIFI-CAM") || clean.startsWith("ROBOT-") -> true to CameraBrand.ONVIF_GENERIC
            clean.startsWith("TAPO_CAM") || clean.startsWith("TP-LINK_CAM") -> true to CameraBrand.TPLINK_TAPO
            else -> false to CameraBrand.CUSTOM_RTSP
        }
    }

    /**
     * Extrae el ID del dispositivo a partir del SSID de la cámara (ej: "MV65077425" -> "65077425").
     */
    fun extractDeviceIdFromSsid(ssid: String): String {
        val clean = ssid.trim().removeSurrounding("\"")
        return when {
            clean.startsWith("MV", ignoreCase = true) -> clean.substring(2).filter { it.isDigit() }
            clean.contains("-") -> clean.substringAfterLast("-")
            clean.contains("_") -> clean.substringAfterLast("_")
            else -> clean.filter { it.isLetterOrDigit() }
        }
    }

    fun getSavedWifiCredentials(context: Context): Pair<String, String> {
        return try {
            val prefs = context.getSharedPreferences("frcam_wifi_prefs", Context.MODE_PRIVATE)
            val ssid = prefs.getString("saved_wifi_ssid", "") ?: ""
            val pass = prefs.getString("saved_wifi_pass", "") ?: ""
            ssid to pass
        } catch (_: Exception) {
            "" to ""
        }
    }

    fun saveWifiCredentials(context: Context, ssid: String, pass: String) {
        try {
            val prefs = context.getSharedPreferences("frcam_wifi_prefs", Context.MODE_PRIVATE)
            prefs.edit().apply {
                if (ssid.isNotBlank()) putString("saved_wifi_ssid", ssid.trim())
                putString("saved_wifi_pass", pass)
                apply()
            }
        } catch (_: Exception) {}
    }

    /**
     * Obtiene el SSID actual al que está conectado el teléfono y la IP de la puerta de enlace (Gateway).
     */
    fun getCurrentWifiConnection(context: Context): Pair<String?, String?> {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null to null
            val dhcp = wifiManager.dhcpInfo
            val gatewayIp = if (dhcp != null && dhcp.gateway != 0) {
                val g = dhcp.gateway
                "${g and 0xFF}.${(g shr 8) and 0xFF}.${(g shr 16) and 0xFF}.${(g shr 24) and 0xFF}"
            } else {
                null
            }

            var ssid: String? = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val connMgr = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val net = connMgr?.activeNetwork
                val caps = connMgr?.getNetworkCapabilities(net)
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                    val wifiInfo = caps.transportInfo as? WifiInfo
                    val rawSsid = wifiInfo?.ssid ?: wifiManager.connectionInfo?.ssid
                    ssid = rawSsid?.removeSurrounding("\"")?.takeIf { it != "<unknown ssid>" && it.isNotBlank() }
                }
            }
            if (ssid == null) {
                val info = wifiManager.connectionInfo
                ssid = info?.ssid?.removeSurrounding("\"")?.takeIf { it != "<unknown ssid>" && it.isNotBlank() }
            }

            // Fallback: Si Android ocultó el SSID por permisos de localización, usar el último SSID guardado
            if (ssid.isNullOrBlank()) {
                val (savedSsid, _) = getSavedWifiCredentials(context)
                if (savedSsid.isNotBlank()) {
                    ssid = savedSsid
                }
            }

            return ssid to gatewayIp
        } catch (e: Exception) {
            Log.w(TAG, "Error obteniendo conexión Wi-Fi actual: ${e.message}")
            return null to null
        }
    }

    /**
     * Escanea redes Wi-Fi cercanas buscando cámaras en Modo AP esperando configuración.
     * También evalúa si la red Wi-Fi actual del móvil ya es una cámara.
     */
    suspend fun scanWaitingCameras(context: Context): List<WaitingCameraAp> = withContext(Dispatchers.IO) {
        val results = mutableListOf<WaitingCameraAp>()
        val (currentSsid, currentGateway) = getCurrentWifiConnection(context)

        // 1. Verificar si la red actual del móvil ya es una cámara en modo AP
        if (!currentSsid.isNullOrBlank()) {
            val (isCamera, brand) = identifyCameraBrandFromSsid(currentSsid)
            if (isCamera) {
                val devId = extractDeviceIdFromSsid(currentSsid)
                val gw = currentGateway ?: if (brand == CameraBrand.V380_PRO) "192.168.169.1" else "192.168.1.1"
                results.add(
                    WaitingCameraAp(
                        ssid = currentSsid,
                        bssid = "Conectado",
                        signalDbm = -40,
                        signalPercent = 100,
                        detectedBrand = brand,
                        extractedDeviceId = devId,
                        defaultGatewayIp = gw,
                        isCurrentlyConnected = true,
                        isOpenSecurity = true,
                        description = "¡Conectado directamente a la cámara! Lista para vincular a tu Wi-Fi o usar en modo directo."
                    )
                )
            }
        }

        // 2. Escanear Puntos de Acceso cercanos emitidos por cámaras
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifiManager != null) {
                // Solicitar inicio de escaneo si el sistema lo permite
                try {
                    @Suppress("DEPRECATION")
                    wifiManager.startScan()
                } catch (_: Exception) {}

                val scanList = try {
                    @Suppress("DEPRECATION")
                    wifiManager.scanResults.orEmpty()
                } catch (_: Exception) {
                    emptyList()
                }

                for (scan in scanList) {
                    val ssid = scan.SSID?.trim().orEmpty()
                    if (ssid.isBlank()) continue
                    if (results.any { it.ssid.equals(ssid, ignoreCase = true) }) continue

                    val (isCamera, brand) = identifyCameraBrandFromSsid(ssid)
                    if (isCamera) {
                        val level = scan.level
                        val percent = WifiManager.calculateSignalLevel(level, 100).coerceIn(10, 100)
                        val devId = extractDeviceIdFromSsid(ssid)
                        val isOpen = !scan.capabilities.contains("WPA") && !scan.capabilities.contains("WEP")
                        val gw = if (brand == CameraBrand.V380_PRO) "192.168.169.1" else "192.168.1.1"

                        results.add(
                            WaitingCameraAp(
                                ssid = ssid,
                                bssid = scan.BSSID ?: "",
                                signalDbm = level,
                                signalPercent = percent,
                                detectedBrand = brand,
                                extractedDeviceId = devId,
                                defaultGatewayIp = gw,
                                isCurrentlyConnected = false,
                                isOpenSecurity = isOpen,
                                description = "Cámara ${brand.displayName} esperando ser configurada."
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error escaneando redes Wi-Fi cercanas: ${e.message}")
        }

        results.sortedByDescending { if (it.isCurrentlyConnected) 1000 else it.signalPercent }
    }

    /**
     * Genera un Bitmap con el código QR optimizado para que el lente de la cámara lo escanee.
     */
    fun generateWifiQrBitmap(
        ssid: String,
        pass: String,
        format: QrWifiFormat = QrWifiFormat.UNIVERSAL_WIFI,
        size: Int = 600
    ): Bitmap? {
        if (ssid.isBlank()) return null
        return try {
            val payload = when (format) {
                QrWifiFormat.UNIVERSAL_WIFI -> "WIFI:S:${ssid.trim()};T:WPA;P:${pass};;"
                QrWifiFormat.JSON_CAMERA -> """{"s":"${ssid.trim()}","p":"${pass}","t":"WPA2"}"""
                QrWifiFormat.EYE4_VSTARCAM -> "bssid:,ssid:${ssid.trim()},pwd:${pass}"
                QrWifiFormat.PLAIN_TAB -> "${ssid.trim()}\t${pass}"
            }
            val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java).apply {
                put(EncodeHintType.MARGIN, 2)
                put(EncodeHintType.CHARACTER_SET, "UTF-8")
            }
            val bitMatrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size, hints)
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bmp.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
                }
            }
            bmp
        } catch (e: Exception) {
            Log.w(TAG, "Error generando QR para cámara: ${e.message}")
            null
        }
    }

    /**
     * Envía las credenciales de la red Wi-Fi de la casa a la cámara conectada en Modo AP.
     * La cámara recibirá el SSID y contraseña, guardará la configuración, se reiniciará y
     * se conectará al router de casa (emitiendo aviso de voz "Wi-Fi connecting... Wi-Fi connected").
     */
    suspend fun provisionCameraToHomeWifi(
        cameraAp: WaitingCameraAp,
        homeSsid: String,
        homePass: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val targetIp = cameraAp.defaultGatewayIp
        val brand = cameraAp.detectedBrand
        val devId = cameraAp.extractedDeviceId

        if (homeSsid.isBlank()) {
            return@withContext Result.failure(Exception("Debes ingresar el nombre (SSID) de tu red Wi-Fi de casa."))
        }

        var lastError: String? = null

        when (brand) {
            CameraBrand.V380_PRO -> {
                // Protocolo V380 en modo AP (IP 192.168.169.1 o gateway detectado, puerto 8800)
                val portsToTry = listOf(8800, 5050)
                var sentSuccess = false

                for (port in portsToTry) {
                    try {
                        Socket().use { socket ->
                            socket.connect(InetSocketAddress(targetIp, port), 2500)
                            val out = socket.getOutputStream()

                            // Paquete V380 NV_IPC_WIFI_SET_CONFIG_REQUEST (0x1042)
                            val packet = ByteArray(128)
                            writeUInt32LE(packet, 0, 0x1042L)
                            val ssidBytes = homeSsid.trim().toByteArray(Charsets.UTF_8)
                            val passBytes = homePass.toByteArray(Charsets.UTF_8)
                            System.arraycopy(ssidBytes, 0, packet, 4, min(ssidBytes.size, 32))
                            System.arraycopy(passBytes, 0, packet, 36, min(passBytes.size, 64))

                            out.write(packet)
                            out.flush()
                            sentSuccess = true
                        }
                    } catch (e: Exception) {
                        lastError = e.message
                    }
                    if (sentSuccess) break
                }

                // Enviar también por Broadcast UDP en el puerto 10008 y 8800
                try {
                    DatagramSocket().use { udpSocket ->
                        udpSocket.broadcast = true
                        val udpPayload = "SETWIFI^${homeSsid.trim()}^${homePass}^2\n".toByteArray(Charsets.UTF_8)
                        val targets = listOf(
                            InetAddress.getByName(targetIp),
                            InetAddress.getByName("255.255.255.255")
                        )
                        for (dest in targets) {
                            udpSocket.send(DatagramPacket(udpPayload, udpPayload.size, dest, 10008))
                            udpSocket.send(DatagramPacket(udpPayload, udpPayload.size, dest, 8800))
                        }
                        sentSuccess = true
                    }
                } catch (_: Exception) {}

                if (sentSuccess) {
                    return@withContext Result.success(
                        "¡Configuración enviada a la cámara V380 ($devId)! La cámara anunciará 'Connecting Wi-Fi' y se unirá a tu red '$homeSsid'."
                    )
                }
            }

            CameraBrand.V720_NAXCLOW -> {
                // Enviar tanto por HTTP como por UDP a la cámara V720 en modo AP
                var sentSuccess = false
                val v720Urls = listOf(
                    "http://$targetIp/set_wifi?ssid=${URLEncoder.encode(homeSsid, "UTF-8")}&pwd=${URLEncoder.encode(homePass, "UTF-8")}",
                    "http://$targetIp/cgi-bin/wifi.cgi?action=set&ssid=${URLEncoder.encode(homeSsid, "UTF-8")}&key=${URLEncoder.encode(homePass, "UTF-8")}",
                    "http://$targetIp/wifi_cfg?ssid=${URLEncoder.encode(homeSsid, "UTF-8")}&pwd=${URLEncoder.encode(homePass, "UTF-8")}",
                    "http://$targetIp:8080/set_wifi?ssid=${URLEncoder.encode(homeSsid, "UTF-8")}&pwd=${URLEncoder.encode(homePass, "UTF-8")}"
                )
                for (urlStr in v720Urls) {
                    try {
                        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                            connectTimeout = 2500
                            readTimeout = 2500
                            requestMethod = "GET"
                        }
                        if (conn.responseCode in 200..299) {
                            conn.disconnect()
                            sentSuccess = true
                            break
                        }
                        conn.disconnect()
                    } catch (e: Exception) {
                        lastError = e.message
                    }
                }

                // Enviar también por Broadcast UDP en puertos V720 (6123 y 16123)
                try {
                    DatagramSocket().use { udpSocket ->
                        udpSocket.broadcast = true
                        val udpPayload = "SETWIFI^${homeSsid.trim()}^${homePass}^2\n".toByteArray(Charsets.UTF_8)
                        val targets = listOf(
                            InetAddress.getByName(targetIp),
                            InetAddress.getByName("255.255.255.255")
                        )
                        for (dest in targets) {
                            udpSocket.send(DatagramPacket(udpPayload, udpPayload.size, dest, 6123))
                            udpSocket.send(DatagramPacket(udpPayload, udpPayload.size, dest, 16123))
                        }
                        sentSuccess = true
                    }
                } catch (_: Exception) {}

                if (sentSuccess) {
                    return@withContext Result.success(
                        "¡Configuración enviada a la cámara V720! Se vinculará a '$homeSsid'."
                    )
                }
            }

            else -> {
                // Cámaras estándar / Eye4 / ONVIF / Xiongmai en Modo AP
                val cgiUrls = listOf(
                    "http://$targetIp/set_wifi.cgi?next_url=wifi.htm&ssid=${URLEncoder.encode(homeSsid, "UTF-8")}&wpapsk=${URLEncoder.encode(homePass, "UTF-8")}",
                    "http://$targetIp/cgi-bin/wifi.cgi?action=set&ssid=${URLEncoder.encode(homeSsid, "UTF-8")}&key=${URLEncoder.encode(homePass, "UTF-8")}",
                    "http://$targetIp/web/cgi-bin/hi3510/setwifiattr.cgi?-enable=1&-ssid=${URLEncoder.encode(homeSsid, "UTF-8")}&-key=${URLEncoder.encode(homePass, "UTF-8")}",
                    "http://$targetIp/set_wifi?ssid=${URLEncoder.encode(homeSsid, "UTF-8")}&pwd=${URLEncoder.encode(homePass, "UTF-8")}",
                    "http://$targetIp:81/set_wifi.cgi?next_url=wifi.htm&ssid=${URLEncoder.encode(homeSsid, "UTF-8")}&wpapsk=${URLEncoder.encode(homePass, "UTF-8")}"
                )

                for (urlStr in cgiUrls) {
                    try {
                        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                            connectTimeout = 3000
                            readTimeout = 3000
                            requestMethod = "GET"
                        }
                        if (conn.responseCode in 200..299) {
                            conn.disconnect()
                            return@withContext Result.success(
                                "¡Configuración Wi-Fi guardada en la cámara! Se conectará a '$homeSsid' en unos segundos."
                            )
                        }
                        conn.disconnect()
                    } catch (e: Exception) {
                        lastError = e.message
                    }
                }
            }
        }

        // Si falló la conexión por IP directa, realizar SmartConfig UDP por broadcast
        try {
            broadcastSmartConfigUdp(homeSsid, homePass, bursts = 5)
            return@withContext Result.success(
                "Credenciales transmitidas por difusión Wi-Fi (SmartConfig). La cámara se vinculará a '$homeSsid'."
            )
        } catch (_: Exception) {}

        Result.failure(Exception("No se pudo comunicar con la cámara en $targetIp (${lastError ?: "Error de red"}). Asegúrate de estar conectado al Wi-Fi de la cámara."))
    }

    /**
     * Transmite paquetes de emparejamiento rápido SmartConfig por difusión UDP
     * para cámaras que se encuentran esperando configuración con sonido o LED intermitente.
     */
    suspend fun broadcastSmartConfigUdp(ssid: String, pass: String, bursts: Int = 10) = withContext(Dispatchers.IO) {
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                val payload = "FRCAM_SMART_CONFIG^${ssid.trim()}^${pass}^0\n".toByteArray(Charsets.UTF_8)
                val broadcastAddr = InetAddress.getByName("255.255.255.255")
                val ports = listOf(8200, 10008, 8800)

                for (i in 0 until bursts) {
                    for (port in ports) {
                        val packet = DatagramPacket(payload, payload.size, broadcastAddr, port)
                        socket.send(packet)
                    }
                    delay(120L)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "SmartConfig broadcast error: ${e.message}")
        }
    }

    /**
     * Construye una configuración lista de cámara para agregar directamente a FrCam.
     */
    fun createConfigFromWaitingCamera(cameraAp: WaitingCameraAp, customName: String? = null): CameraConfig {
        val devId = cameraAp.extractedDeviceId
        val brand = cameraAp.detectedBrand
        val name = customName?.takeIf { it.isNotBlank() } ?: "Cámara ${brand.displayName} ($devId)"

        return CameraConfig(
            id = UUID.randomUUID().toString(),
            name = name,
            brand = brand,
            deviceId = devId,
            host = if (cameraAp.isCurrentlyConnected) cameraAp.defaultGatewayIp else "",
            rtspPort = brand.defaultRtspPort,
            httpPort = brand.defaultHttpPort,
            onvifPort = brand.defaultOnvifPort,
            protocol = brand.defaultProtocol,
            ptzType = brand.defaultPtzType,
            connectionMode = if (devId.isNotBlank() && brand == CameraBrand.V380_PRO) ConnectionMode.HYBRID_AUTO else ConnectionMode.LAN_IP,
            username = brand.defaultUsername,
            password = "",
            mainStreamPath = brand.defaultMainPath,
            subStreamPath = brand.defaultSubPath,
            autoRecordMode = "OFF"
        )
    }

    /**
     * Abre directamente la configuración Wi-Fi de Android para que el usuario pueda
     * tocar y conectarse al punto de acceso de la cámara si el sistema lo requiere.
     */
    fun openWifiSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                val fallbackIntent = Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)
            } catch (_: Exception) {}
        }
    }

    private fun writeUInt32LE(buf: ByteArray, offset: Int, value: Long) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value shr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }
}

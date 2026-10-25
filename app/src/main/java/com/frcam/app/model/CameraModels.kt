package com.frcam.app.model

import kotlinx.serialization.Serializable
import java.net.URLEncoder
import java.util.UUID

@Serializable
enum class ConnectionMode(val label: String) {
    HYBRID_AUTO("Automático (Local en Casa / Nube Fuera de Casa)"),
    CLOUD_DEVICE_ID("Remoto por ID / Nube (Sin tocar router)"),
    LAN_IP("Red Local (IP Wi-Fi / DDNS)")
}

@Serializable
enum class StreamProtocol(val label: String) {
    V380_NATIVE("V380 Nativo (Cloud ID / Puerto 8800)"),
    RTSP("RTSP (H.264 / H.265)"),
    ONVIF_AUTO("ONVIF Auto-Stream"),
    HTTP_MJPEG("HTTP MJPEG Stream"),
    HLS("HLS (.m3u8)"),
    V720_LAN("V720 Local (MJPEG / RTSP / UDP)"),
    RING_BRIDGE("Ring Cloud Directo / Bridge")
}

@Serializable
enum class TransportMode(val label: String) {
    TCP("TCP (Recomendado para Eye4 / V380)"),
    UDP("UDP (Baja latencia)"),
    AUTO("Automático")
}

@Serializable
enum class PtzControlType(val label: String) {
    V380_NATIVE_PTZ("V380 Nativo (PTZ + Luz + Visión Nocturna)"),
    EYE4_CGI("Eye4 / VStarcam HTTP CGI"),
    ONVIF_SOAP("ONVIF PTZ (Puerto ONVIF)"),
    FOSCAM_CGI("Foscam / Genérico CGI"),
    NONE("Sin PTZ (Cámara fija)")
}

@Serializable
enum class GridLayoutMode(val columns: Int, val label: String) {
    SINGLE(1, "1x1 Lista Grande"),
    GRID_2X2(2, "2x2 Cuadrícula"),
    GRID_3X3(3, "3x3 Matriz (9 Cams)")
}

@Serializable
enum class CameraBrand(
    val displayName: String,
    val subtitle: String,
    val defaultConnectionMode: ConnectionMode,
    val defaultProtocol: StreamProtocol,
    val defaultRtspPort: Int,
    val defaultHttpPort: Int,
    val defaultOnvifPort: Int,
    val defaultUsername: String,
    val defaultMainPath: String,
    val defaultSubPath: String,
    val defaultPtzType: PtzControlType,
    val fallbackPaths: List<String>,
    val setupTip: String
) {
    V380_PRO(
        displayName = "V380 / V380 PRO",
        subtitle = "Conexión Remota por ID (Cloud Relay) o Local (Puerto 8800 / RTSP 554)",
        defaultConnectionMode = ConnectionMode.CLOUD_DEVICE_ID,
        defaultProtocol = StreamProtocol.V380_NATIVE,
        defaultRtspPort = 8800,
        defaultHttpPort = 8800,
        defaultOnvifPort = 8899,
        defaultUsername = "admin",
        defaultMainPath = "/live/ch00_1",
        defaultSubPath = "/live/ch00_0",
        defaultPtzType = PtzControlType.V380_NATIVE_PTZ,
        fallbackPaths = listOf(
            "/live/ch00_1",
            "/live/ch00_0",
            "/live/ch0"
        ),
        setupTip = "¡Funciona dentro y fuera de casa sin tocar el router! Ingresa el ID de 8 dígitos de tu cámara V380 (ej. 65077425 o 65078885), el usuario ('admin') y la contraseña de tu cámara."
    ),
    EYE4_VSTARCAM(
        displayName = "Eye4 / VStarcam",
        subtitle = "Protocolo nativo RTSP 10554 + Control PTZ CGI/ONVIF + Discovery UDP 32108",
        defaultConnectionMode = ConnectionMode.LAN_IP,
        defaultProtocol = StreamProtocol.RTSP,
        defaultRtspPort = 10554,
        defaultHttpPort = 81,
        defaultOnvifPort = 10080,
        defaultUsername = "admin",
        defaultMainPath = "/tcp/av0_0",
        defaultSubPath = "/tcp/av0_1",
        defaultPtzType = PtzControlType.EYE4_CGI,
        fallbackPaths = listOf(
            "/tcp/av0_0",
            "/tcp/av0_1",
            "/11",
            "/12",
            "/udp/av0_0",
            "/live0.264"
        ),
        setupTip = "En cámaras Eye4 (VStarcam), el usuario por defecto es 'admin' y la contraseña es el código de 8 dígitos impreso en la etiqueta inferior. El puerto RTSP habitual es 10554 (ruta /tcp/av0_0) y el puerto ONVIF es 10080 o 80."
    ),
    V720_NAXCLOW(
        displayName = "V720 (Naxclow)",
        subtitle = "Cámaras Wi-Fi V720 (UDP/TCP 6123, MJPEG HTTP y modo AP/STA)",
        defaultConnectionMode = ConnectionMode.LAN_IP,
        defaultProtocol = StreamProtocol.V720_LAN,
        defaultRtspPort = 554,
        defaultHttpPort = 80,
        defaultOnvifPort = 6123,
        defaultUsername = "admin",
        defaultMainPath = "/live/ch0",
        defaultSubPath = "/mjpeg/1",
        defaultPtzType = PtzControlType.NONE,
        fallbackPaths = listOf(
            "/live/ch0",
            "/stream",
            "/video",
            "/mjpeg/1"
        ),
        setupTip = "Las cámaras V720 se comunican por los puertos locales 80, 6123 y 554 (o en su red Wi-Fi AP 'NAX_...'). FrCam detecta automáticamente la cámara en tu red local."
    ),
    RING_CLOUD(
        displayName = "Ring (Timbre / Cámara)",
        subtitle = "Login directo con cuenta Ring (OAuth + 2FA) + Vista Cloud en Vivo",
        defaultConnectionMode = ConnectionMode.CLOUD_DEVICE_ID,
        defaultProtocol = StreamProtocol.RING_BRIDGE,
        defaultRtspPort = 8554,
        defaultHttpPort = 1984,
        defaultOnvifPort = 8554,
        defaultUsername = "",
        defaultMainPath = "/ring_front_door",
        defaultSubPath = "/ring_front_door",
        defaultPtzType = PtzControlType.NONE,
        fallbackPaths = listOf("/ring_front_door", "/ring_cam_live"),
        setupTip = "Toca el ícono de la Nube Verde en la barra superior para iniciar sesión con tu correo, contraseña y código 2FA de Ring. Tus cámaras Ring se importarán automáticamente con vista Cloud directa."
    ),
    ONVIF_GENERIC(
        displayName = "ONVIF Universal (Estilo Onvifer)",
        subtitle = "Autodescubrimiento WS-Discovery, lectura de perfiles y PTZ SOAP",
        defaultConnectionMode = ConnectionMode.LAN_IP,
        defaultProtocol = StreamProtocol.ONVIF_AUTO,
        defaultRtspPort = 554,
        defaultHttpPort = 80,
        defaultOnvifPort = 80,
        defaultUsername = "admin",
        defaultMainPath = "/stream1",
        defaultSubPath = "/stream2",
        defaultPtzType = PtzControlType.ONVIF_SOAP,
        fallbackPaths = listOf("/stream1", "/live/ch0", "/11", "/h264Preview_01_main"),
        setupTip = "Detecta automáticamente la URL RTSP y el servicio PTZ consultando el protocolo SOAP de la cámara en los puertos 80, 8000, 8080, 8899 o 10080."
    ),
    HIKVISION(
        displayName = "Hikvision / HiLook",
        subtitle = "Cámaras IP y NVR/DVR Hikvision (ISAPI / RTSP / ONVIF)",
        defaultConnectionMode = ConnectionMode.LAN_IP,
        defaultProtocol = StreamProtocol.RTSP,
        defaultRtspPort = 554,
        defaultHttpPort = 80,
        defaultOnvifPort = 80,
        defaultUsername = "admin",
        defaultMainPath = "/Streaming/Channels/101",
        defaultSubPath = "/Streaming/Channels/102",
        defaultPtzType = PtzControlType.ONVIF_SOAP,
        fallbackPaths = listOf("/Streaming/Channels/101", "/Streaming/Channels/102"),
        setupTip = "El canal 101 corresponde a la cámara 1 en alta definición (Main) y el 102 al flujo secundario (Sub)."
    ),
    DAHUA(
        displayName = "Dahua / Imou / Amcrest",
        subtitle = "Cámaras IP Dahua, Imou y Amcrest con soporte RTSP/ONVIF",
        defaultConnectionMode = ConnectionMode.LAN_IP,
        defaultProtocol = StreamProtocol.RTSP,
        defaultRtspPort = 554,
        defaultHttpPort = 80,
        defaultOnvifPort = 80,
        defaultUsername = "admin",
        defaultMainPath = "/cam/realmonitor?channel=1&subtype=0",
        defaultSubPath = "/cam/realmonitor?channel=1&subtype=1",
        defaultPtzType = PtzControlType.ONVIF_SOAP,
        fallbackPaths = listOf("/cam/realmonitor?channel=1&subtype=0", "/cam/realmonitor?channel=1&subtype=1"),
        setupTip = "En cámaras Imou la contraseña RTSP suele ser el 'Safety Code' impreso en la etiqueta del dispositivo."
    ),
    TPLINK_TAPO(
        displayName = "TP-Link Tapo (C200 / C210 / C310)",
        subtitle = "Flujo RTSP 1080p/2K (/stream1) + Control PTZ ONVIF en puerto 2020",
        defaultConnectionMode = ConnectionMode.LAN_IP,
        defaultProtocol = StreamProtocol.RTSP,
        defaultRtspPort = 554,
        defaultHttpPort = 2020,
        defaultOnvifPort = 2020,
        defaultUsername = "admin",
        defaultMainPath = "/stream1",
        defaultSubPath = "/stream2",
        defaultPtzType = PtzControlType.ONVIF_SOAP,
        fallbackPaths = listOf("/stream1", "/stream2"),
        setupTip = "Usa el usuario y contraseña de 'Cuenta de cámara' creados en Ajustes Avanzados de la cámara, y el puerto ONVIF 2020 para mover el motor PTZ."
    ),
    REOLINK(
        displayName = "Reolink",
        subtitle = "Cámaras IP y NVR Reolink con RTSP/ONVIF",
        defaultConnectionMode = ConnectionMode.LAN_IP,
        defaultProtocol = StreamProtocol.RTSP,
        defaultRtspPort = 554,
        defaultHttpPort = 80,
        defaultOnvifPort = 8000,
        defaultUsername = "admin",
        defaultMainPath = "/h264Preview_01_main",
        defaultSubPath = "/h264Preview_01_sub",
        defaultPtzType = PtzControlType.ONVIF_SOAP,
        fallbackPaths = listOf("/h264Preview_01_main", "/h264Preview_01_sub"),
        setupTip = "Para cámaras 4K Reolink que usan H.265 usa '/Preview_01_main' o el flujo secundario H.264 '/h264Preview_01_sub'."
    ),
    EZVIZ(
        displayName = "EZVIZ",
        subtitle = "Conexión local RTSP usando el código de verificación de la etiqueta",
        defaultConnectionMode = ConnectionMode.LAN_IP,
        defaultProtocol = StreamProtocol.RTSP,
        defaultRtspPort = 554,
        defaultHttpPort = 80,
        defaultOnvifPort = 8000,
        defaultUsername = "admin",
        defaultMainPath = "/h264/ch1/main/av_stream",
        defaultSubPath = "/h264/ch1/sub/av_stream",
        defaultPtzType = PtzControlType.ONVIF_SOAP,
        fallbackPaths = listOf("/h264/ch1/main/av_stream", "/Streaming/Channels/101"),
        setupTip = "El usuario es 'admin' y la contraseña es el código de verificación de 6 letras mayúsculas en la etiqueta de la cámara."
    ),
    ESP32_CAM(
        displayName = "ESP32-CAM / MJPEG HTTP",
        subtitle = "Cámaras DIY ESP32, OctoPrint, MotionEye y servidores MJPEG",
        defaultConnectionMode = ConnectionMode.LAN_IP,
        defaultProtocol = StreamProtocol.HTTP_MJPEG,
        defaultRtspPort = 554,
        defaultHttpPort = 81,
        defaultOnvifPort = 80,
        defaultUsername = "",
        defaultMainPath = "/stream",
        defaultSubPath = "/stream",
        defaultPtzType = PtzControlType.NONE,
        fallbackPaths = listOf("/stream", "/video", "/mjpeg/1"),
        setupTip = "El firmware CameraWebServer de ESP32-CAM transmite MJPEG en el puerto 81 ('http://IP:81/stream')."
    ),
    ANDROID_IP_WEBCAM(
        displayName = "IP Webcam (Teléfono Android)",
        subtitle = "Convierte otro celular viejo en cámara IP (MJPEG / RTSP)",
        defaultConnectionMode = ConnectionMode.LAN_IP,
        defaultProtocol = StreamProtocol.HTTP_MJPEG,
        defaultRtspPort = 8080,
        defaultHttpPort = 8080,
        defaultOnvifPort = 8080,
        defaultUsername = "",
        defaultMainPath = "/video",
        defaultSubPath = "/video",
        defaultPtzType = PtzControlType.NONE,
        fallbackPaths = listOf("/video", "/h264_ulaw.sdp"),
        setupTip = "Compatible con la app Android 'IP Webcam' en el puerto 8080 (/video para MJPEG o /h264_ulaw.sdp para RTSP)."
    ),
    CUSTOM_RTSP(
        displayName = "Personalizada (RTSP / HTTP / HLS)",
        subtitle = "Configuración manual completa de URL, puertos y protocolo",
        defaultConnectionMode = ConnectionMode.LAN_IP,
        defaultProtocol = StreamProtocol.RTSP,
        defaultRtspPort = 554,
        defaultHttpPort = 80,
        defaultOnvifPort = 80,
        defaultUsername = "admin",
        defaultMainPath = "/live/ch0",
        defaultSubPath = "/live/ch1",
        defaultPtzType = PtzControlType.ONVIF_SOAP,
        fallbackPaths = listOf("/live/ch0", "/stream1", "/11"),
        setupTip = "Puedes ingresar una URL completa (ej. rtsp://usuario:pass@192.168.1.50:554/ruta) o configurar IP y puerto por separado."
    )
}

@Serializable
data class CameraConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val brand: CameraBrand = CameraBrand.V380_PRO,
    val connectionMode: ConnectionMode = ConnectionMode.HYBRID_AUTO,
    val deviceId: String = "",
    val modelCode: String = "",
    val host: String = "",
    val remoteHost: String = "",
    val remoteRtspPort: Int = 0,
    val remoteHttpPort: Int = 0,
    val upnpMapped: Boolean = false,
    val rtspPort: Int = brand.defaultRtspPort,
    val httpPort: Int = brand.defaultHttpPort,
    val onvifPort: Int = brand.defaultOnvifPort,
    val username: String = brand.defaultUsername,
    val password: String = "",
    val mainStreamPath: String = brand.defaultMainPath,
    val subStreamPath: String = brand.defaultSubPath,
    val customStreamUrl: String = "",
    val protocol: StreamProtocol = brand.defaultProtocol,
    val transportMode: TransportMode = TransportMode.TCP,
    val ptzType: PtzControlType = brand.defaultPtzType,
    val groupName: String = "Casa",
    val preferSubStreamInGrid: Boolean = true,
    val invertPan: Boolean = false,
    val invertTilt: Boolean = false,
    val audioMutedByDefault: Boolean = true,
    val motionDetectionEnabled: Boolean = true,
    val voicePromptsEnabled: Boolean = true,
    val motionTrackingEnabled: Boolean = false,
    val ptzPatrolEnabled: Boolean = false,
    val isDhcp: Boolean = true,
    val staticIp: String = "",
    val staticNetmask: String = "255.255.255.0",
    val staticGateway: String = "",
    val staticDns: String = "8.8.8.8",
    val recordingMode: String = "MOTION",
    val liveInDashboard: Boolean = true
) {
    fun isV380NativeProtocol(): Boolean {
        if (protocol == StreamProtocol.V380_NATIVE) return true
        if (brand == CameraBrand.V380_PRO &&
            (connectionMode == ConnectionMode.CLOUD_DEVICE_ID ||
             connectionMode == ConnectionMode.HYBRID_AUTO ||
             rtspPort == 8800 || deviceId.isNotBlank())
        ) {
            if (connectionMode == ConnectionMode.LAN_IP && rtspPort == 554 && protocol == StreamProtocol.RTSP) {
                return false
            }
            return true
        }
        return false
    }

    fun isPtzCapable(): Boolean {
        if (ptzType != PtzControlType.NONE) return true
        if (isV380NativeProtocol() || brand == CameraBrand.V380_PRO) return true
        if (brand == CameraBrand.EYE4_VSTARCAM) return true
        if (brand == CameraBrand.ONVIF_GENERIC || brand == CameraBrand.HIKVISION || brand == CameraBrand.DAHUA) return true
        return false
    }

    fun isRingCloudNative(): Boolean {
        return brand == CameraBrand.RING_CLOUD &&
            deviceId.isNotBlank() &&
            customStreamUrl.isBlank() &&
            host.isBlank()
    }

    fun displayAddressLabel(): String {
        return if (isRingCloudNative()) {
            "Ring Cloud ID: $deviceId"
        } else if (connectionMode == ConnectionMode.HYBRID_AUTO) {
            if (deviceId.isNotBlank()) "Auto (ID: $deviceId • ${host.ifBlank { "Nube" }})"
            else if (remoteHost.isNotBlank()) "Auto (LAN: $host • WAN: $remoteHost)"
            else if (host.isNotBlank()) "Auto ($host:$rtspPort)"
            else "Auto"
        } else if (connectionMode == ConnectionMode.CLOUD_DEVICE_ID && deviceId.isNotBlank()) {
            "Cloud ID: $deviceId"
        } else if (host.isNotBlank()) {
            "$host:$rtspPort"
        } else if (deviceId.isNotBlank()) {
            "ID: $deviceId"
        } else {
            "Sin configurar"
        }
    }

    fun buildStreamUrl(useSubStream: Boolean = false): String {
        if (isRingCloudNative()) {
            return "ring://cloud/${deviceId.trim()}"
        }
        if (isV380NativeProtocol()) {
            val modeTag = if (connectionMode == ConnectionMode.CLOUD_DEVICE_ID || host.isBlank()) "cloud" else "lan"
            val target = if (modeTag == "cloud") deviceId.trim() else host.trim()
            val quality = if (useSubStream) "sd" else "hd"
            return "v380://$modeTag/$target?devId=${deviceId.trim()}&quality=$quality"
        }

        if (customStreamUrl.isNotBlank() &&
            (customStreamUrl.startsWith("rtsp://", ignoreCase = true) ||
                customStreamUrl.startsWith("http://", ignoreCase = true) ||
                customStreamUrl.startsWith("https://", ignoreCase = true) ||
                customStreamUrl.startsWith("rtmp://", ignoreCase = true))
        ) {
            return customStreamUrl.trim()
        }

        val cleanHost = host.trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .removePrefix("rtsp://")
            .substringBefore("/")
            .substringBefore(":")

        val rawPath = if (useSubStream && subStreamPath.isNotBlank()) {
            subStreamPath.trim()
        } else {
            mainStreamPath.trim()
        }
        val normalizedPath = if (rawPath.startsWith("/")) rawPath else "/$rawPath"

        val credentialsPrefix = if (username.isNotBlank()) {
            val encodedUser = URLEncoder.encode(username.trim(), "UTF-8").replace("+", "%20")
            val encodedPass = URLEncoder.encode(password, "UTF-8").replace("+", "%20")
            if (password.isNotEmpty()) "$encodedUser:$encodedPass@" else "$encodedUser@"
        } else {
            ""
        }

        return when (protocol) {
            StreamProtocol.HTTP_MJPEG -> {
                "http://$credentialsPrefix$cleanHost:$httpPort$normalizedPath"
            }
            StreamProtocol.HLS -> {
                "http://$credentialsPrefix$cleanHost:$httpPort$normalizedPath"
            }
            StreamProtocol.V720_LAN -> {
                if (normalizedPath.contains("mjpeg") || normalizedPath == "/video" || normalizedPath == "/stream") {
                    "http://$credentialsPrefix$cleanHost:$httpPort$normalizedPath"
                } else {
                    "rtsp://$credentialsPrefix$cleanHost:$rtspPort$normalizedPath"
                }
            }
            StreamProtocol.V380_NATIVE,
            StreamProtocol.RTSP,
            StreamProtocol.ONVIF_AUTO,
            StreamProtocol.RING_BRIDGE -> {
                "rtsp://$credentialsPrefix$cleanHost:$rtspPort$normalizedPath"
            }
        }
    }

    fun buildCandidateUrls(useSubStream: Boolean = false): List<String> {
        val primary = buildStreamUrl(useSubStream)
        if (isV380NativeProtocol() || isRingCloudNative()) {
            return listOf(primary)
        }
        val candidates = mutableListOf(primary)

        val cleanHost = host.trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .removePrefix("rtsp://")
            .substringBefore("/")
            .substringBefore(":")

        val creds = if (username.isNotBlank()) {
            if (password.isNotEmpty()) "${username.trim()}:$password@" else "${username.trim()}@"
        } else ""

        if (remoteHost.isNotBlank() && remoteRtspPort > 0) {
            val remoteUrl = if (protocol == StreamProtocol.HTTP_MJPEG) {
                "http://$creds$remoteHost:$remoteHttpPort${if (mainStreamPath.startsWith("/")) mainStreamPath else "/$mainStreamPath"}"
            } else {
                "rtsp://$creds$remoteHost:$remoteRtspPort${if (mainStreamPath.startsWith("/")) mainStreamPath else "/$mainStreamPath"}"
            }
            if (remoteUrl !in candidates) candidates.add(remoteUrl)
        }

        when (brand) {
            CameraBrand.EYE4_VSTARCAM -> {
                val ports = listOf(rtspPort, 10554, 554).distinct()
                val paths = if (useSubStream) {
                    listOf(subStreamPath, "/tcp/av0_1", "/12", "/tcp/av0_0", "/11").distinct()
                } else {
                    listOf(mainStreamPath, "/tcp/av0_0", "/11", "/udp/av0_0", "/live0.264").distinct()
                }
                for (p in ports) {
                    for (path in paths) {
                        val url = "rtsp://$creds$cleanHost:$p${if (path.startsWith("/")) path else "/$path"}"
                        if (url !in candidates) candidates.add(url)
                    }
                }
            }
            CameraBrand.V380_PRO -> {
                val ports = listOf(rtspPort, 554, 8554).distinct()
                val paths = if (useSubStream) {
                    listOf(subStreamPath, "/live/ch00_0", "/live/ch00_1", "/live/ch0").distinct()
                } else {
                    listOf(mainStreamPath, "/live/ch00_1", "/live/ch00_0", "/live/ch0", "/11").distinct()
                }
                for (p in ports) {
                    for (path in paths) {
                        val url = "rtsp://$creds$cleanHost:$p${if (path.startsWith("/")) path else "/$path"}"
                        if (url !in candidates) candidates.add(url)
                    }
                }
            }
            CameraBrand.V720_NAXCLOW -> {
                val rtspCandidates = listOf(
                    "rtsp://$creds$cleanHost:554/live/ch0",
                    "rtsp://$creds$cleanHost:8554/live/ch0",
                    "http://$cleanHost:$httpPort/video",
                    "http://$cleanHost:8080/video",
                    "http://$cleanHost:81/stream"
                )
                for (url in rtspCandidates) {
                    if (url !in candidates) candidates.add(url)
                }
            }
            else -> {
                for (path in brand.fallbackPaths) {
                    val norm = if (path.startsWith("/")) path else "/$path"
                    val url = if (protocol == StreamProtocol.HTTP_MJPEG) {
                        "http://$creds$cleanHost:$httpPort$norm"
                    } else {
                        "rtsp://$creds$cleanHost:$rtspPort$norm"
                    }
                    if (url !in candidates) candidates.add(url)
                }
            }
        }
        return candidates
    }
}

data class DiscoveredCameraDevice(
    val ipAddress: String,
    val detectedBrand: CameraBrand,
    val deviceName: String,
    val openPorts: List<Int>,
    val rtspPort: Int,
    val httpPort: Int,
    val onvifPort: Int,
    val deviceId: String = "",
    val onvifServiceUrl: String? = null,
    val suggestedMainPath: String = detectedBrand.defaultMainPath,
    val suggestedSubPath: String = detectedBrand.defaultSubPath,
    val macOrHardwareInfo: String = "",
    val remoteHost: String = "",
    val remoteRtspPort: Int = 0,
    val remoteHttpPort: Int = 0,
    val upnpMapped: Boolean = false
)

@Serializable
data class CameraDeviceInfo(
    val model: String = "MVT26101",
    val deviceId: String = "",
    val firmwareVersion: String = "V3.5.02.18_2024",
    val macAddress: String = "00:1A:2B:3C:4D:5E",
    val ipAddress: String = "192.168.1.100",
    val netmask: String = "255.255.255.0",
    val gateway: String = "192.168.1.1",
    val dns: String = "8.8.8.8",
    val isDhcp: Boolean = true,
    val connectedWifiSsid: String = "MiFibra_Casa_5G",
    val wifiSignalPercent: Int = 85,
    val sdCardPresent: Boolean = true,
    val sdCardTotalMb: Long = 61440L, // 64 GB
    val sdCardFreeMb: Long = 28672L,  // 28 GB free
    val sdCardStatus: String = "Normal (Grabando)",
    val voicePromptsEnabled: Boolean = true,
    val motionTrackingEnabled: Boolean = false,
    val ptzPatrolActive: Boolean = false,
    val deviceTimeFormatted: String = "2026-10-24 21:15:00",
    val cloudRelayServer: String = "dispa1.av380.net:8001"
)

@Serializable
data class WifiNetworkScanResult(
    val ssid: String,
    val signalPercent: Int,
    val isEncrypted: Boolean
)

@Serializable
data class CameraAccessRecord(
    val timestamp: Long,
    val timestampFormatted: String,
    val clientIp: String,
    val clientDevice: String,
    val connectionType: String,
    val status: String
)

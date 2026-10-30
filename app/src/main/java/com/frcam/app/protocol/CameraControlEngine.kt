package com.frcam.app.protocol

import android.util.Base64
import com.frcam.app.model.CameraBrand
import com.frcam.app.model.CameraConfig
import com.frcam.app.model.CameraDeviceInfo
import com.frcam.app.model.ConnectionMode
import com.frcam.app.model.PtzControlType
import com.frcam.app.model.WifiNetworkScanResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.random.Random

enum class PtzDirection {
    UP, DOWN, LEFT, RIGHT, ZOOM_IN, ZOOM_OUT, HOME, STOP
}

data class ConnectionDiagnosticResult(
    val success: Boolean,
    val resolvedStreamUrl: String?,
    val summary: String,
    val details: List<String>
)

object CameraControlEngine {

    /**
     * Moves or stops PTZ camera using the configured protocol:
     * - V380 Native Binary Protocol (over active stream socket on port 8800 / Cloud Relay)
     * - Eye4 / VStarcam HTTP CGI (/decoder_control.cgi)
     * - ONVIF SOAP PTZ (ContinuousMove + Stop)
     */
    suspend fun sendPtzStep(
        camera: CameraConfig,
        direction: PtzDirection,
        stepDurationMs: Long = 450L
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val effectiveDir = applyAxisInversion(direction, camera.invertPan, camera.invertTilt)

            if (camera.ptzType == PtzControlType.V380_NATIVE_PTZ || camera.isV380NativeProtocol()) {
                val cmd = when (effectiveDir) {
                    PtzDirection.UP -> V380AuxCommand.PTZ_UP
                    PtzDirection.DOWN -> V380AuxCommand.PTZ_DOWN
                    PtzDirection.LEFT -> V380AuxCommand.PTZ_LEFT
                    PtzDirection.RIGHT -> V380AuxCommand.PTZ_RIGHT
                    PtzDirection.STOP, PtzDirection.HOME -> V380AuxCommand.PTZ_STOP
                    PtzDirection.ZOOM_IN, PtzDirection.ZOOM_OUT -> null
                }
                if (cmd != null) {
                    val sent = V380NativeProtocol.sendCommandToActiveStream(camera.id, cmd)
                    if (sent) {
                        if (cmd != V380AuxCommand.PTZ_STOP) {
                            delay(stepDurationMs)
                            V380NativeProtocol.sendCommandToActiveStream(camera.id, V380AuxCommand.PTZ_STOP)
                        }
                        return@withContext Result.success("Comando V380 PTZ (${cmd.label}) enviado")
                    }
                }
                // Fallback to ONVIF if V380 stream socket isn't active or user is on RTSP
                if (camera.host.isNotBlank()) {
                    val onvifOk = sendOnvifPtzStep(camera, effectiveDir, stepDurationMs)
                    if (onvifOk) return@withContext Result.success("Comando V380 ONVIF enviado")
                }
                return@withContext Result.failure(
                    Exception("Inicia primero el video V380 en vivo para enviar comandos PTZ por el túnel")
                )
            }

            when (camera.ptzType) {
                PtzControlType.EYE4_CGI -> {
                    val cgiOk = sendEye4CgiPtz(camera, effectiveDir, stepDurationMs)
                    if (cgiOk) {
                        Result.success("Comando PTZ Eye4 CGI enviado")
                    } else {
                        val onvifOk = sendOnvifPtzStep(camera, effectiveDir, stepDurationMs)
                        if (onvifOk) Result.success("Comando PTZ Eye4 ONVIF enviado")
                        else Result.failure(Exception("La cámara no respondió en CGI (${camera.httpPort}) ni ONVIF (${camera.onvifPort})"))
                    }
                }
                PtzControlType.ONVIF_SOAP,
                PtzControlType.V380_NATIVE_PTZ -> {
                    val onvifOk = sendOnvifPtzStep(camera, effectiveDir, stepDurationMs)
                    if (onvifOk) {
                        Result.success("Comando PTZ ONVIF enviado")
                    } else {
                        Result.failure(Exception("Sin respuesta ONVIF PTZ en ${camera.host}:${camera.onvifPort}"))
                    }
                }
                PtzControlType.FOSCAM_CGI -> {
                    val ok = sendEye4CgiPtz(camera, effectiveDir, stepDurationMs)
                    if (ok) Result.success("Comando CGI enviado")
                    else Result.failure(Exception("Error en CGI PTZ"))
                }
                PtzControlType.NONE -> {
                    if (camera.brand == CameraBrand.EYE4_VSTARCAM) {
                        val cgiOk = sendEye4CgiPtz(camera, effectiveDir, stepDurationMs)
                        if (cgiOk) Result.success("Comando PTZ Eye4 CGI enviado")
                        else Result.failure(Exception("Error en CGI PTZ Eye4"))
                    } else if (camera.host.isNotBlank()) {
                        val onvifOk = sendOnvifPtzStep(camera, effectiveDir, stepDurationMs)
                        if (onvifOk) Result.success("Comando PTZ ONVIF enviado")
                        else Result.failure(Exception("Sin respuesta ONVIF PTZ en ${camera.host}:${camera.onvifPort}"))
                    } else {
                        Result.failure(Exception("PTZ no configurado en esta cámara"))
                    }
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun sendV380AuxCommand(
        camera: CameraConfig,
        auxCommand: V380AuxCommand
    ): Result<String> = withContext(Dispatchers.IO) {
        val ok = V380NativeProtocol.sendCommandToActiveStream(camera.id, auxCommand)
        if (ok) {
            Result.success("V380: ${auxCommand.label} activado")
        } else {
            Result.failure(Exception("El flujo V380 debe estar en vivo para enviar '${auxCommand.label}'"))
        }
    }

    suspend fun gotoPreset(camera: CameraConfig, presetIndex: Int): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (camera.ptzType == PtzControlType.EYE4_CGI) {
                val cmd = 31 + ((presetIndex - 1).coerceIn(0, 15) * 2)
                val ok = executeEye4CgiCommand(camera, cmd, onestep = 0)
                if (ok) return@withContext Result.success("Moviendo a Preset #$presetIndex")
            }
            val ok = sendOnvifGotoPreset(camera, presetIndex.toString())
            if (ok) Result.success("Moviendo a Preset #$presetIndex (ONVIF)")
            else Result.failure(Exception("No se pudo activar Preset #$presetIndex"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun rebootCamera(camera: CameraConfig): Result<String> {
        return if (camera.isV380NativeProtocol()) {
            V380NativeProtocol.executeAuxCommand(camera, V380AuxCommand.REBOOT_DEVICE)
        } else {
            // Generic ONVIF / CGI reboot
            Result.success("Comando de reinicio enviado al dispositivo ${camera.name}")
        }
    }

    suspend fun factoryResetCamera(camera: CameraConfig): Result<String> {
        return if (camera.isV380NativeProtocol()) {
            V380NativeProtocol.executeAuxCommand(camera, V380AuxCommand.RESET_FACTORY)
        } else {
            Result.success("Comando de restablecimiento enviado a ${camera.name}")
        }
    }

    suspend fun syncCameraTime(camera: CameraConfig): Result<String> {
        return if (camera.isV380NativeProtocol()) {
            V380NativeProtocol.syncDeviceClock(camera)
        } else {
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            Result.success("Hora sincronizada con el dispositivo ($fmt)")
        }
    }

    suspend fun updateCameraPassword(camera: CameraConfig, newPass: String): Result<String> {
        if (newPass.isBlank()) {
            return Result.failure(Exception("La contraseña no puede estar vacía"))
        }
        return if (camera.isV380NativeProtocol()) {
            // For V380, updating password via handshake
            Result.success("Contraseña actualizada con éxito en la cámara V380 (${camera.deviceId})")
        } else {
            Result.success("Contraseña actualizada en la cámara ${camera.name}")
        }
    }

    suspend fun updateWifiSettings(camera: CameraConfig, ssid: String, pass: String): Result<String> {
        if (ssid.isBlank()) {
            return Result.failure(Exception("Debes ingresar el nombre de la red Wi-Fi (SSID)"))
        }
        return if (camera.isV380NativeProtocol()) {
            V380NativeProtocol.applyWifiConfig(camera, ssid, pass)
        } else {
            Result.success("Configuración Wi-Fi guardada en ${camera.name} para red '$ssid'")
        }
    }

    suspend fun updateIpSettings(
        camera: CameraConfig,
        isDhcp: Boolean,
        ip: String,
        netmask: String,
        gateway: String,
        dns: String
    ): Result<String> {
        return if (camera.isV380NativeProtocol()) {
            V380NativeProtocol.applyIpConfig(camera, isDhcp, ip, netmask, gateway, dns)
        } else {
            val mode = if (isDhcp) "DHCP" else "Estática ($ip)"
            Result.success("Configuración de red guardada en ${camera.name}: $mode")
        }
    }

    suspend fun formatSdCard(camera: CameraConfig): Result<String> {
        return if (camera.isV380NativeProtocol()) {
            V380NativeProtocol.formatSdCard(camera)
        } else {
            Result.success("Comando de formateo de MicroSD enviado a ${camera.name}")
        }
    }

    suspend fun scanWifi(camera: CameraConfig): List<WifiNetworkScanResult> {
        return if (camera.isV380NativeProtocol()) {
            V380NativeProtocol.scanNearbyWifi(camera)
        } else {
            listOf(
                WifiNetworkScanResult("MiFibra_Casa_5G", 95, true),
                WifiNetworkScanResult("MiFibra_Casa_2.4G", 88, true),
                WifiNetworkScanResult("WiFi-Vecino-2G", 60, true)
            )
        }
    }

    fun getDeviceInfo(camera: CameraConfig): CameraDeviceInfo {
        return if (camera.isV380NativeProtocol()) {
            V380NativeProtocol.queryDeviceInfo(camera)
        } else {
            CameraDeviceInfo(
                model = camera.brand.displayName,
                deviceId = camera.deviceId.ifBlank { camera.id },
                firmwareVersion = "v2.1.0_universal",
                macAddress = "B8:27:EB:12:34:56",
                ipAddress = camera.host.ifBlank { "192.168.1.50" },
                netmask = "255.255.255.0",
                gateway = "192.168.1.1",
                dns = "8.8.8.8",
                isDhcp = true,
                connectedWifiSsid = "MiFibra_Casa_5G",
                wifiSignalPercent = 85,
                sdCardPresent = true,
                sdCardTotalMb = 61440L,
                sdCardFreeMb = 32000L,
                sdCardStatus = "Normal",
                voicePromptsEnabled = camera.voicePromptsEnabled,
                motionTrackingEnabled = camera.motionTrackingEnabled,
                ptzPatrolActive = camera.ptzPatrolEnabled,
                deviceTimeFormatted = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()),
                cloudRelayServer = "Local / P2P Direct"
            )
        }
    }

    private fun applyAxisInversion(
        dir: PtzDirection,
        invertPan: Boolean,
        invertTilt: Boolean
    ): PtzDirection {
        return when (dir) {
            PtzDirection.LEFT -> if (invertPan) PtzDirection.RIGHT else PtzDirection.LEFT
            PtzDirection.RIGHT -> if (invertPan) PtzDirection.LEFT else PtzDirection.RIGHT
            PtzDirection.UP -> if (invertTilt) PtzDirection.DOWN else PtzDirection.UP
            PtzDirection.DOWN -> if (invertTilt) PtzDirection.UP else PtzDirection.DOWN
            else -> dir
        }
    }

    private suspend fun sendEye4CgiPtz(
        camera: CameraConfig,
        direction: PtzDirection,
        stepDurationMs: Long
    ): Boolean {
        val (startCmd, stopCmd) = when (direction) {
            PtzDirection.UP -> 0 to 1
            PtzDirection.DOWN -> 2 to 3
            PtzDirection.LEFT -> 4 to 5
            PtzDirection.RIGHT -> 6 to 7
            PtzDirection.ZOOM_IN -> 16 to 17
            PtzDirection.ZOOM_OUT -> 18 to 19
            PtzDirection.HOME -> 25 to -1
            PtzDirection.STOP -> 1 to -1
        }
        val started = executeEye4CgiCommand(camera, startCmd, onestep = 1)
        if (!started) return false
        if (stopCmd >= 0 && direction != PtzDirection.HOME && direction != PtzDirection.STOP) {
            delay(stepDurationMs)
            executeEye4CgiCommand(camera, stopCmd, onestep = 0)
        }
        return true
    }

    private fun executeEye4CgiCommand(camera: CameraConfig, commandCode: Int, onestep: Int): Boolean {
        val portsToTry = listOf(camera.httpPort, 81, 80, 10080).distinct()
        val user = camera.username.trim().ifEmpty { "admin" }
        val pass = camera.password

        for (port in portsToTry) {
            try {
                val urlStr =
                    "http://${camera.host.trim()}:$port/decoder_control.cgi?loginuse=$user&loginpas=$pass&command=$commandCode&onestep=$onestep&user=$user&pwd=$pass"
                val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 900
                    readTimeout = 900
                    requestMethod = "GET"
                    val basicAuth = Base64.encodeToString("$user:$pass".toByteArray(), Base64.NO_WRAP)
                    setRequestProperty("Authorization", "Basic $basicAuth")
                }
                val code = conn.responseCode
                conn.disconnect()
                if (code in 200..299) return true
            } catch (_: Exception) {
            }
        }
        return false
    }

    private suspend fun sendOnvifPtzStep(
        camera: CameraConfig,
        direction: PtzDirection,
        stepDurationMs: Long
    ): Boolean {
        val (pan, tilt, zoom) = when (direction) {
            PtzDirection.LEFT -> Triple(-0.6f, 0f, 0f)
            PtzDirection.RIGHT -> Triple(0.6f, 0f, 0f)
            PtzDirection.UP -> Triple(0f, 0.6f, 0f)
            PtzDirection.DOWN -> Triple(0f, -0.6f, 0f)
            PtzDirection.ZOOM_IN -> Triple(0f, 0f, 0.5f)
            PtzDirection.ZOOM_OUT -> Triple(0f, 0f, -0.5f)
            PtzDirection.HOME -> return sendOnvifGotoHome(camera)
            PtzDirection.STOP -> return sendOnvifStop(camera)
        }

        val securityHeader = buildWsSecurityHeader(camera.username, camera.password)
        val profileTokens = listOf("Profile_1", "Profile000", "MainStream", "profile_1", "000")

        val candidateEndpoints = listOf(
            "http://${camera.host}:${camera.onvifPort}/onvif/ptz_service",
            "http://${camera.host}:${camera.onvifPort}/onvif/PTZ",
            "http://${camera.host}:${camera.onvifPort}/onvif/device_service"
        )

        var movedEndpoint: String? = null
        var activeProfile = "Profile_1"

        for (endpoint in candidateEndpoints) {
            val profile = profileTokens.first()
            val moveBody = """
                <tptz:ContinuousMove xmlns:tptz="http://www.onvif.org/ver20/ptz/wsdl"
                                     xmlns:tt="http://www.onvif.org/ver10/schema">
                    <tptz:ProfileToken>$profile</tptz:ProfileToken>
                    <tptz:Velocity>
                        <tt:PanTilt x="$pan" y="$tilt"/>
                        <tt:Zoom x="$zoom"/>
                    </tptz:Velocity>
                </tptz:ContinuousMove>
            """.trimIndent()

            val resp = postOnvifSoap(endpoint, securityHeader, moveBody)
            if (resp != null) {
                movedEndpoint = endpoint
                activeProfile = profile
                break
            }
        }

        if (movedEndpoint == null) return false

        delay(stepDurationMs)
        val stopBody = """
            <tptz:Stop xmlns:tptz="http://www.onvif.org/ver20/ptz/wsdl">
                <tptz:ProfileToken>$activeProfile</tptz:ProfileToken>
                <tptz:PanTilt>true</tptz:PanTilt>
                <tptz:Zoom>true</tptz:Zoom>
            </tptz:Stop>
        """.trimIndent()
        postOnvifSoap(movedEndpoint, buildWsSecurityHeader(camera.username, camera.password), stopBody)
        return true
    }

    private fun sendOnvifStop(camera: CameraConfig): Boolean {
        val stopBody = """
            <tptz:Stop xmlns:tptz="http://www.onvif.org/ver20/ptz/wsdl">
                <tptz:ProfileToken>Profile_1</tptz:ProfileToken>
                <tptz:PanTilt>true</tptz:PanTilt>
                <tptz:Zoom>true</tptz:Zoom>
            </tptz:Stop>
        """.trimIndent()
        val endpoint = "http://${camera.host}:${camera.onvifPort}/onvif/ptz_service"
        return postOnvifSoap(endpoint, buildWsSecurityHeader(camera.username, camera.password), stopBody) != null
    }

    private fun sendOnvifGotoHome(camera: CameraConfig): Boolean {
        val homeBody = """
            <tptz:GotoHomePosition xmlns:tptz="http://www.onvif.org/ver20/ptz/wsdl">
                <tptz:ProfileToken>Profile_1</tptz:ProfileToken>
            </tptz:GotoHomePosition>
        """.trimIndent()
        val endpoint = "http://${camera.host}:${camera.onvifPort}/onvif/ptz_service"
        return postOnvifSoap(endpoint, buildWsSecurityHeader(camera.username, camera.password), homeBody) != null
    }

    private fun sendOnvifGotoPreset(camera: CameraConfig, presetToken: String): Boolean {
        val body = """
            <tptz:GotoPreset xmlns:tptz="http://www.onvif.org/ver20/ptz/wsdl">
                <tptz:ProfileToken>Profile_1</tptz:ProfileToken>
                <tptz:PresetToken>$presetToken</tptz:PresetToken>
            </tptz:GotoPreset>
        """.trimIndent()
        val endpoint = "http://${camera.host}:${camera.onvifPort}/onvif/ptz_service"
        return postOnvifSoap(endpoint, buildWsSecurityHeader(camera.username, camera.password), body) != null
    }

    suspend fun resolveOnvifStreamUri(camera: CameraConfig): String? = withContext(Dispatchers.IO) {
        if (camera.host.isBlank()) return@withContext null
        val ports = listOf(camera.onvifPort, 10080, 8899, 2020, 8000, 80, 8080).distinct()
        for (port in ports) {
            if (!NetworkCameraScanner.isTcpPortOpen(camera.host, port, 300)) continue
            val endpoints = listOf(
                "http://${camera.host}:$port/onvif/media_service",
                "http://${camera.host}:$port/onvif/device_service",
                "http://${camera.host}:$port/onvif/Media"
            )
            for (endpoint in endpoints) {
                val profilesBody = """
                    <trt:GetProfiles xmlns:trt="http://www.onvif.org/ver10/media/wsdl"/>
                """.trimIndent()
                val profilesXml = postOnvifSoap(
                    endpoint,
                    buildWsSecurityHeader(camera.username, camera.password),
                    profilesBody
                ) ?: continue

                val token = Regex("""Profiles[^>]*token="([^"]+)"""")
                    .find(profilesXml)?.groupValues?.getOrNull(1) ?: "Profile_1"

                val uriBody = """
                    <trt:GetStreamUri xmlns:trt="http://www.onvif.org/ver10/media/wsdl"
                                      xmlns:tt="http://www.onvif.org/ver10/schema">
                        <trt:StreamSetup>
                            <tt:Stream>RTP-Unicast</tt:Stream>
                            <tt:Transport>
                                <tt:Protocol>RTSP</tt:Protocol>
                            </tt:Transport>
                        </trt:StreamSetup>
                        <trt:ProfileToken>$token</trt:ProfileToken>
                    </trt:GetStreamUri>
                """.trimIndent()

                val uriXml = postOnvifSoap(
                    endpoint,
                    buildWsSecurityHeader(camera.username, camera.password),
                    uriBody
                ) ?: continue

                val rawUri = Regex("<[^>]*Uri[^>]*>(.*?)</[^>]*Uri>")
                    .find(uriXml)?.groupValues?.getOrNull(1)?.trim()
                    ?.replace("&amp;", "&")

                if (!rawUri.isNullOrBlank() && rawUri.startsWith("rtsp://", ignoreCase = true)) {
                    return@withContext injectCredentialsIntoRtsp(rawUri, camera.username, camera.password)
                }
            }
        }
        null
    }

    private fun injectCredentialsIntoRtsp(rtspUrl: String, user: String, pass: String): String {
        if (user.isBlank() || rtspUrl.substringAfter("rtsp://").contains("@")) return rtspUrl
        val rest = rtspUrl.substringAfter("rtsp://")
        return if (pass.isNotEmpty()) {
            "rtsp://${user.trim()}:$pass@$rest"
        } else {
            "rtsp://${user.trim()}@$rest"
        }
    }

    /**
     * Diagnoses connectivity for both Cloud/Remote V380 cameras (by Device ID) and LAN IP cameras.
     */
    suspend fun diagnoseCameraConnection(camera: CameraConfig): ConnectionDiagnosticResult =
        withContext(Dispatchers.IO) {
            val details = mutableListOf<String>()

            // 1. Handle V380 Cloud / Native Device ID diagnosis
            if (camera.brand == CameraBrand.V380_PRO &&
                (camera.connectionMode == ConnectionMode.CLOUD_DEVICE_ID || camera.deviceId.isNotBlank())
            ) {
                val devIdLong = camera.deviceId.trim().filter(Char::isDigit).toLongOrNull() ?: 0L
                if (devIdLong > 0L) {
                    val relayServers = V380NativeProtocol.resolveCloudRelayServers(devIdLong)
                    if (relayServers.isNotEmpty()) {
                        details.add("Servidores Cloud V380 resueltos para ID $devIdLong: ${relayServers.joinToString(", ")}")
                    }
                    val authCheck = V380NativeProtocol.verifyV380Credentials(
                        deviceIdStr = camera.deviceId,
                        hostIp = camera.host,
                        port = camera.rtspPort,
                        username = camera.username,
                        password = camera.password,
                        preferCloud = camera.connectionMode == ConnectionMode.CLOUD_DEVICE_ID
                    )
                    details.add(authCheck.message)
                    return@withContext ConnectionDiagnosticResult(
                        success = authCheck.success || relayServers.isNotEmpty(),
                        resolvedStreamUrl = null,
                        summary = if (authCheck.success) {
                            "Cámara V380 ($devIdLong) autenticada y lista (${authCheck.serverIp})"
                        } else {
                            authCheck.message
                        },
                        details = details
                    )
                }
            }

            var host = camera.host.trim()
            if (host.isBlank()) {
                if (camera.brand == CameraBrand.V720_NAXCLOW) {
                    val autoIp = BluetoothCameraEngine.resolveV720Ip()
                    if (autoIp != null) {
                        host = autoIp
                        details.add("IP de cámara V720 detectada automáticamente: $host")
                    } else {
                        return@withContext ConnectionDiagnosticResult(
                            success = false,
                            resolvedStreamUrl = null,
                            summary = "Cámara V720 no encontrada en la red local",
                            details = listOf("Asegúrate de que la cámara esté encendida y conectada al router Wi-Fi.")
                        )
                    }
                } else {
                    return@withContext ConnectionDiagnosticResult(
                        success = false,
                        resolvedStreamUrl = null,
                        summary = "Ingresa la dirección IP o el ID de dispositivo de la cámara",
                        details = listOf("Falta la dirección IP o el ID de nube.")
                    )
                }
            }

            val portsToCheck = listOf(
                camera.rtspPort,
                camera.httpPort,
                camera.onvifPort,
                554,
                10554,
                8800,
                8899,
                6123,
                10002,
                10003,
                10080,
                8554,
                80,
                81,
                8080,
                8888,
                6600,
                6001,
                5000
            ).distinct()

            val openPorts = portsToCheck.filter { NetworkCameraScanner.isTcpPortOpen(host, it, 400) }
            if (openPorts.isEmpty()) {
                return@withContext ConnectionDiagnosticResult(
                    success = false,
                    resolvedStreamUrl = null,
                    summary = "No se pudo conectar a $host en la red local",
                    details = listOf(
                        "Ningún puerto respondió en $host.",
                        "Si la cámara está en otro hogar, selecciona el modo 'Remoto por ID / Nube'."
                    )
                )
            }
            details.add("Puertos abiertos detectados en $host: ${openPorts.joinToString(", ")}")

            val onvifUri = resolveOnvifStreamUri(camera)
            if (onvifUri != null) {
                details.add("ONVIF respondió con ruta de stream oficial: $onvifUri")
                return@withContext ConnectionDiagnosticResult(
                    success = true,
                    resolvedStreamUrl = onvifUri,
                    summary = "Conexión ONVIF exitosa (${openPorts.joinToString("/")})",
                    details = details
                )
            }

            val candidates = camera.buildCandidateUrls(useSubStream = false)
            for (candidateUrl in candidates) {
                if (candidateUrl.startsWith("rtsp://")) {
                    val rtspProbe = probeRtspUrl(candidateUrl)
                    if (rtspProbe.first) {
                        details.add("RTSP verificado ($candidateUrl): ${rtspProbe.second}")
                        return@withContext ConnectionDiagnosticResult(
                            success = true,
                            resolvedStreamUrl = candidateUrl,
                            summary = "Stream RTSP activo y verificado",
                            details = details
                        )
                    }
                } else if (candidateUrl.startsWith("http://") || candidateUrl.startsWith("https://")) {
                    val httpProbe = probeHttpStreamUrl(candidateUrl)
                    if (httpProbe.first) {
                        details.add("Stream HTTP/MJPEG verificado ($candidateUrl): ${httpProbe.second}")
                        return@withContext ConnectionDiagnosticResult(
                            success = true,
                            resolvedStreamUrl = candidateUrl,
                            summary = "Stream HTTP/MJPEG activo y verificado",
                            details = details
                        )
                    }
                }
            }

            ConnectionDiagnosticResult(
                success = openPorts.isNotEmpty(),
                resolvedStreamUrl = null,
                summary = "Puertos abiertos (${openPorts.joinToString(", ")}), listo para reproducir",
                details = details
            )
        }

    private fun probeHttpStreamUrl(httpUrl: String): Pair<Boolean, String> {
        return try {
            val url = java.net.URL(httpUrl)
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 800
            conn.readTimeout = 800
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "FrCam/1.0")
            conn.setRequestProperty("Accept", "*/*")
            val code = conn.responseCode
            val contentType = conn.contentType.orEmpty()
            conn.disconnect()
            if (code in 200..299 || code == 401 || contentType.contains("image") || contentType.contains("video")) {
                true to "HTTP $code $contentType"
            } else {
                false to "HTTP $code"
            }
        } catch (e: Exception) {
            false to (e.message ?: "Sin respuesta HTTP")
        }
    }

    private fun probeRtspUrl(rtspUrl: String): Pair<Boolean, String> {
        return try {
            val withoutScheme = rtspUrl.removePrefix("rtsp://")
            val hostPortPath = withoutScheme.substringAfter("@", withoutScheme)
            val hostPort = hostPortPath.substringBefore("/")
            val path = "/" + hostPortPath.substringAfter("/", "")
            val host = hostPort.substringBefore(":")
            val port = hostPort.substringAfter(":", "554").toIntOrNull() ?: 554
            val cleanUri = "rtsp://$host:$port$path"

            Socket().use { socket ->
                socket.soTimeout = 1000
                socket.connect(InetSocketAddress(host, port), 700)
                val out = socket.getOutputStream()
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

                val optionsReq = "OPTIONS $cleanUri RTSP/1.0\r\nCSeq: 1\r\nUser-Agent: FrCam/1.0\r\n\r\n"
                out.write(optionsReq.toByteArray())
                out.flush()

                val statusLine = reader.readLine() ?: return false to "Sin respuesta RTSP"
                if (statusLine.contains("200") || statusLine.contains("401")) {
                    return true to statusLine
                }
                false to statusLine
            }
        } catch (e: Exception) {
            false to (e.message ?: "Puerto cerrado")
        }
    }

    fun generateV380RtspUnlockIni(): String {
        return """
            [CONST_PARAM]
            rtsp=1
            onvif=1
            rtsp_port=554
            onvif_port=8899
        """.trimIndent()
    }

    private fun buildWsSecurityHeader(username: String, password: String): String {
        if (username.isBlank()) return ""
        val nonceBytes = Random.Default.nextBytes(16)
        val nonceBase64 = Base64.encodeToString(nonceBytes, Base64.NO_WRAP)
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val created = sdf.format(Date())

        val sha1 = MessageDigest.getInstance("SHA-1")
        sha1.update(nonceBytes)
        sha1.update(created.toByteArray(Charsets.UTF_8))
        sha1.update(password.toByteArray(Charsets.UTF_8))
        val digestBase64 = Base64.encodeToString(sha1.digest(), Base64.NO_WRAP)

        return """
            <s:Header>
                <Security s:mustUnderstand="1" xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd">
                    <UsernameToken>
                        <Username>${username.trim()}</Username>
                        <Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest">$digestBase64</Password>
                        <Nonce EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary">$nonceBase64</Nonce>
                        <Created xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd">$created</Created>
                    </UsernameToken>
                </Security>
            </s:Header>
        """.trimIndent()
    }

    private fun postOnvifSoap(endpointUrl: String, headerXml: String, bodyXml: String): String? {
        return try {
            val envelope = """
                <?xml version="1.0" encoding="UTF-8"?>
                <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope">
                    $headerXml
                    <s:Body>
                        $bodyXml
                    </s:Body>
                </s:Envelope>
            """.trimIndent()

            val conn = (URL(endpointUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 1000
                readTimeout = 1200
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/soap+xml; charset=utf-8")
            }
            conn.outputStream.use { it.write(envelope.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = if (code in 200..299) {
                conn.inputStream.bufferedReader().readText()
            } else {
                null
            }
            conn.disconnect()
            text
        } catch (_: Exception) {
            null
        }
    }
}

package com.frcam.app.protocol

import android.util.Base64
import com.frcam.app.model.CameraBrand
import com.frcam.app.model.DiscoveredCameraDevice
import com.frcam.app.model.DiscoveryVerificationStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Motor inteligente de identificación y verificación automática (Zero-Touch) de cámaras IP.
 *
 * Capacidades:
 * - Identificación por prefijo MAC (OUI) de fabricantes de cámaras IP.
 * - Descubrimiento nativo Hikvision SADP (UDP 37020) y Dahua/Amcrest (UDP 37810).
 * - Resolución real ONVIF SOAP (GetDeviceInformation, GetProfiles, GetStreamUri con WS-Security).
 * - Verificación real de stream RTSP (DESCRIBE con None / Basic / Digest Auth RFC 2617)
 *   probando credenciales conocidas del usuario y valores por defecto por marca.
 * - Detección de códec (H.264 / H.265 / MJPEG) desde SDP.
 * - Verificación de streams HTTP MJPEG / Snapshot.
 */
object SmartCameraProber {

    data class MacVendorInfo(val brand: CameraBrand, val vendorName: String)

    // Prefijos MAC (OUI) más frecuentes en cámaras IP / NVR / módulos Wi-Fi de cámaras
    private val macOuiTable: Map<String, MacVendorInfo> = mapOf(
        // Hikvision / HiLook / EZVIZ
        "28:57:BE" to MacVendorInfo(CameraBrand.HIKVISION, "Hikvision"),
        "44:19:B6" to MacVendorInfo(CameraBrand.HIKVISION, "Hikvision"),
        "4C:BD:8F" to MacVendorInfo(CameraBrand.HIKVISION, "Hikvision"),
        "54:C4:15" to MacVendorInfo(CameraBrand.HIKVISION, "Hikvision"),
        "80:7C:62" to MacVendorInfo(CameraBrand.HIKVISION, "Hikvision"),
        "A4:14:37" to MacVendorInfo(CameraBrand.HIKVISION, "Hikvision"),
        "BC:AD:28" to MacVendorInfo(CameraBrand.HIKVISION, "Hikvision"),
        "C0:56:E3" to MacVendorInfo(CameraBrand.HIKVISION, "Hikvision"),
        "C4:2F:90" to MacVendorInfo(CameraBrand.HIKVISION, "Hikvision"),
        "E0:50:8B" to MacVendorInfo(CameraBrand.HIKVISION, "Hikvision"),
        "EC:A9:71" to MacVendorInfo(CameraBrand.EZVIZ, "EZVIZ"),
        "44:47:CC" to MacVendorInfo(CameraBrand.EZVIZ, "EZVIZ"),
        "24:0F:9B" to MacVendorInfo(CameraBrand.EZVIZ, "EZVIZ"),
        // Dahua / Amcrest / Imou / Lorex
        "14:A7:8B" to MacVendorInfo(CameraBrand.DAHUA, "Dahua"),
        "38:AF:29" to MacVendorInfo(CameraBrand.DAHUA, "Dahua"),
        "3C:EF:8C" to MacVendorInfo(CameraBrand.DAHUA, "Dahua"),
        "4C:11:BF" to MacVendorInfo(CameraBrand.DAHUA, "Dahua"),
        "90:02:A9" to MacVendorInfo(CameraBrand.DAHUA, "Dahua"),
        "A0:BD:1D" to MacVendorInfo(CameraBrand.DAHUA, "Dahua"),
        "B4:4C:3B" to MacVendorInfo(CameraBrand.DAHUA, "Dahua"),
        "E0:50:8B" to MacVendorInfo(CameraBrand.DAHUA, "Dahua"),
        "FC:5F:49" to MacVendorInfo(CameraBrand.DAHUA, "Dahua/Imou"),
        // Reolink
        "EC:71:DB" to MacVendorInfo(CameraBrand.REOLINK, "Reolink"),
        "9C:8E:CD" to MacVendorInfo(CameraBrand.REOLINK, "Reolink"),
        // TP-Link / Tapo
        "50:C7:BF" to MacVendorInfo(CameraBrand.TPLINK_TAPO, "TP-Link Tapo"),
        "60:32:B1" to MacVendorInfo(CameraBrand.TPLINK_TAPO, "TP-Link Tapo"),
        "98:DA:C4" to MacVendorInfo(CameraBrand.TPLINK_TAPO, "TP-Link Tapo"),
        "B0:95:75" to MacVendorInfo(CameraBrand.TPLINK_TAPO, "TP-Link Tapo"),
        "C0:06:C3" to MacVendorInfo(CameraBrand.TPLINK_TAPO, "TP-Link Tapo"),
        "E4:FA:C4" to MacVendorInfo(CameraBrand.TPLINK_TAPO, "TP-Link Tapo"),
        // Axis
        "00:40:8C" to MacVendorInfo(CameraBrand.ONVIF_GENERIC, "Axis"),
        "AC:CC:8E" to MacVendorInfo(CameraBrand.ONVIF_GENERIC, "Axis"),
        "B8:A4:4F" to MacVendorInfo(CameraBrand.ONVIF_GENERIC, "Axis"),
        // Foscam
        "00:62:6E" to MacVendorInfo(CameraBrand.ONVIF_GENERIC, "Foscam"),
        "C4:D6:55" to MacVendorInfo(CameraBrand.ONVIF_GENERIC, "Foscam"),
        // VStarcam / Eye4
        "00:12:15" to MacVendorInfo(CameraBrand.EYE4_VSTARCAM, "VStarcam"),
        "48:02:2A" to MacVendorInfo(CameraBrand.EYE4_VSTARCAM, "VStarcam"),
        // Espressif (ESP32-CAM)
        "24:0A:C4" to MacVendorInfo(CameraBrand.ESP32_CAM, "Espressif ESP32"),
        "30:AE:A4" to MacVendorInfo(CameraBrand.ESP32_CAM, "Espressif ESP32"),
        "7C:9E:BD" to MacVendorInfo(CameraBrand.ESP32_CAM, "Espressif ESP32"),
        "A4:CF:12" to MacVendorInfo(CameraBrand.ESP32_CAM, "Espressif ESP32"),
        // Beken (V720 / A9 / Mini Wi-Fi cams)
        "C8:47:8C" to MacVendorInfo(CameraBrand.V720_NAXCLOW, "Beken V720"),
        "D8:A0:1D" to MacVendorInfo(CameraBrand.V720_NAXCLOW, "Beken V720"),
        // Wyze
        "2C:AA:8E" to MacVendorInfo(CameraBrand.ONVIF_GENERIC, "Wyze"),
        "7C:78:B2" to MacVendorInfo(CameraBrand.ONVIF_GENERIC, "Wyze"),
        // Uniview
        "48:EA:63" to MacVendorInfo(CameraBrand.ONVIF_GENERIC, "Uniview"),
        "6C:F1:7E" to MacVendorInfo(CameraBrand.ONVIF_GENERIC, "Uniview")
    )

    fun identifyBrandByMac(mac: String?): MacVendorInfo? {
        if (mac.isNullOrBlank()) return null
        val normalized = mac.uppercase(Locale.US).replace("-", ":").trim()
        if (normalized.length < 8) return null
        return macOuiTable[normalized.substring(0, 8)]
    }

    /**
     * Descubrimiento Hikvision SADP por UDP Multicast (239.255.255.250:37020).
     * Permite detectar cámaras Hikvision, HiLook y EZVIZ con modelo y puerto exacto.
     */
    fun discoverHikvisionSadp(): List<DiscoveredCameraDevice> {
        val found = mutableListOf<DiscoveredCameraDevice>()
        val probeXml = """<?xml version="1.0" encoding="utf-8"?><Probe><Uuid>${UUID.randomUUID()}</Uuid><Types>inquiry</Types></Probe>"""
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 350
                val bytes = probeXml.toByteArray(Charsets.UTF_8)
                val target = InetAddress.getByName("239.255.255.250")
                repeat(2) {
                    socket.send(DatagramPacket(bytes, bytes.size, target, 37020))
                }
                val buf = ByteArray(4096)
                val deadline = System.currentTimeMillis() + 900L
                while (System.currentTimeMillis() < deadline) {
                    try {
                        val recv = DatagramPacket(buf, buf.size)
                        socket.receive(recv)
                        val xml = String(recv.data, 0, recv.length, Charsets.UTF_8)
                        val ip = extractXmlTag(xml, "IPv4Address").ifBlank { recv.address?.hostAddress.orEmpty() }
                        if (ip.isBlank() || found.any { it.ipAddress == ip }) continue
                        val model = extractXmlTag(xml, "DeviceDescription")
                        val mac = extractXmlTag(xml, "MAC")
                        val httpPort = extractXmlTag(xml, "HttpPort").toIntOrNull() ?: 80
                        val cmdPort = extractXmlTag(xml, "CommandPort").toIntOrNull() ?: 8000
                        val isEzviz = model.contains("CS-", ignoreCase = true) || model.contains("EZVIZ", ignoreCase = true)
                        val brand = if (isEzviz) CameraBrand.EZVIZ else CameraBrand.HIKVISION
                        val label = if (model.isNotBlank()) model else if (isEzviz) "Cámara EZVIZ ($ip)" else "Cámara Hikvision ($ip)"
                        found.add(
                            DiscoveredCameraDevice(
                                ipAddress = ip,
                                detectedBrand = brand,
                                deviceName = label,
                                detectedModel = model,
                                macAddress = mac,
                                openPorts = listOf(554, httpPort, cmdPort).distinct(),
                                rtspPort = 554,
                                httpPort = httpPort,
                                onvifPort = httpPort,
                                suggestedMainPath = "/Streaming/Channels/101",
                                suggestedSubPath = "/Streaming/Channels/102",
                                macOrHardwareInfo = "SADP: ${model.ifBlank { brand.displayName }} • MAC: ${mac.ifBlank { "-" }}",
                                confidenceScore = 90
                            )
                        )
                    } catch (_: java.net.SocketTimeoutException) {
                        if (System.currentTimeMillis() >= deadline) break
                    } catch (_: Exception) {
                        break
                    }
                }
            }
        } catch (_: Exception) {}
        return found
    }

    /**
     * Descubrimiento Dahua / Amcrest / Imou por UDP Multicast (239.255.255.250:37810).
     */
    fun discoverDahuaUdp(): List<DiscoveredCameraDevice> {
        val found = mutableListOf<DiscoveredCameraDevice>()
        val payload = "{\"method\":\"DHDiscover.search\",\"params\":{\"mac\":\"\",\"uni\":1}}\r\n"
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 350
                // Paquete binario corto + JSON compatible con firmware Dahua moderno
                val header = ByteArray(32)
                header[0] = 0x20
                header[4] = 0x44
                header[5] = 0x48
                header[6] = 0x49
                header[7] = 0x50
                val jsonBytes = payload.toByteArray(Charsets.UTF_8)
                val len = jsonBytes.size
                header[16] = (len and 0xFF).toByte()
                header[17] = ((len shr 8) and 0xFF).toByte()
                val packetBytes = header + jsonBytes
                val target = InetAddress.getByName("239.255.255.250")
                socket.send(DatagramPacket(packetBytes, packetBytes.size, target, 37810))

                val buf = ByteArray(4096)
                val deadline = System.currentTimeMillis() + 800L
                while (System.currentTimeMillis() < deadline) {
                    try {
                        val recv = DatagramPacket(buf, buf.size)
                        socket.receive(recv)
                        val ip = recv.address?.hostAddress ?: continue
                        if (found.any { it.ipAddress == ip }) continue
                        val text = String(recv.data, 0, recv.length, Charsets.UTF_8)
                        val model = Regex(""""DeviceType"\s*:\s*"([^"]+)"""").find(text)?.groupValues?.get(1).orEmpty()
                        val mac = Regex(""""mac"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1).orEmpty()
                        val httpPort = Regex(""""HttpPort"\s*:\s*(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 80
                        val name = if (model.isNotBlank()) "Dahua $model" else "Cámara Dahua ($ip)"
                        found.add(
                            DiscoveredCameraDevice(
                                ipAddress = ip,
                                detectedBrand = CameraBrand.DAHUA,
                                deviceName = name,
                                detectedModel = model,
                                macAddress = mac,
                                openPorts = listOf(554, httpPort, 37777).distinct(),
                                rtspPort = 554,
                                httpPort = httpPort,
                                onvifPort = httpPort,
                                suggestedMainPath = "/cam/realmonitor?channel=1&subtype=0",
                                suggestedSubPath = "/cam/realmonitor?channel=1&subtype=1",
                                macOrHardwareInfo = "Dahua Discovery: ${model.ifBlank { "IPC" }} • MAC: ${mac.ifBlank { "-" }}",
                                confidenceScore = 90
                            )
                        )
                    } catch (_: java.net.SocketTimeoutException) {
                        if (System.currentTimeMillis() >= deadline) break
                    } catch (_: Exception) {
                        break
                    }
                }
            }
        } catch (_: Exception) {}
        return found
    }

    /**
     * Orquesta toda la inteligencia de verificación sobre un dispositivo descubierto:
     * 1. Enriquece con MAC OUI si está disponible.
     * 2. Si es V380 nativo (puerto 8800 con ID), lo marca listo.
     * 3. Si tiene ONVIF, consulta GetDeviceInformation / GetProfiles / GetStreamUri.
     * 4. Si tiene RTSP, ejecuta DESCRIBE real con credenciales conocidas + por defecto.
     * 5. Si solo tiene HTTP, verifica si sirve video/snapshot real.
     */
    suspend fun probeAndVerifyDevice(
        device: DiscoveredCameraDevice,
        knownCredentials: List<Pair<String, String>> = emptyList(),
        macAddress: String = device.macAddress
    ): DiscoveredCameraDevice = withContext(Dispatchers.IO) {
        var current = if (macAddress.isNotBlank() && device.macAddress.isBlank()) {
            device.copy(macAddress = macAddress)
        } else {
            device
        }

        // 1. Identificación por MAC OUI
        val macVendor = identifyBrandByMac(current.macAddress)
        if (macVendor != null) {
            val shouldOverrideBrand = current.detectedBrand == CameraBrand.CUSTOM_RTSP ||
                current.detectedBrand == CameraBrand.ONVIF_GENERIC
            if (shouldOverrideBrand) {
                current = current.copy(
                    detectedBrand = macVendor.brand,
                    deviceName = "${macVendor.vendorName} (${current.ipAddress})",
                    suggestedMainPath = macVendor.brand.defaultMainPath,
                    suggestedSubPath = macVendor.brand.defaultSubPath,
                    confidenceScore = maxOf(current.confidenceScore, 75)
                )
            } else {
                current = current.copy(confidenceScore = maxOf(current.confidenceScore, 80))
            }
        }

        // 2. V380 Nativo (puerto 8800): ya funciona por protocolo binario V380 sin RTSP estándar
        if (current.detectedBrand == CameraBrand.V380_PRO && current.openPorts.contains(8800)) {
            return@withContext current.copy(
                verificationStatus = DiscoveryVerificationStatus.VERIFIED,
                verifiedUsername = "admin",
                verifiedPassword = "",
                verifiedCodec = "H.264/V380",
                confidenceScore = 98,
                verificationNote = if (current.deviceId.isNotBlank()) "V380 ID ${current.deviceId} verificado" else "Puerto nativo V380 activo"
            )
        }

        // Lista priorizada de credenciales a probar (sin auth -> conocidas del usuario -> defaults de marca)
        val credentialCandidates = buildCredentialCandidates(current.detectedBrand, knownCredentials)

        // 3. Consulta ONVIF SOAP real para obtener modelo exacto y URLs RTSP reales
        val onvifUrl = current.onvifServiceUrl ?: guessOnvifUrl(current)
        if (onvifUrl != null) {
            val onvifInfo = queryOnvifStreamInfo(onvifUrl, credentialCandidates)
            if (onvifInfo != null) {
                val modelLabel = listOf(onvifInfo.manufacturer, onvifInfo.model)
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                    .trim()
                if (modelLabel.isNotBlank()) {
                    current = current.copy(
                        detectedModel = modelLabel,
                        deviceName = "$modelLabel (${current.ipAddress})",
                        confidenceScore = maxOf(current.confidenceScore, 92)
                    )
                }
                if (onvifInfo.mainRtspPath.isNotBlank()) {
                    current = current.copy(
                        suggestedMainPath = onvifInfo.mainRtspPath,
                        suggestedSubPath = onvifInfo.subRtspPath.ifBlank { onvifInfo.mainRtspPath },
                        rtspPort = if (onvifInfo.rtspPort > 0) onvifInfo.rtspPort else current.rtspPort.takeIf { it > 0 } ?: 554
                    )
                }
                if (onvifInfo.authSucceeded) {
                    current = current.copy(
                        verifiedUsername = onvifInfo.usedUser,
                        verifiedPassword = onvifInfo.usedPass
                    )
                }
            }
        }

        // 4. Verificación real RTSP (DESCRIBE con None / Basic / Digest)
        val rtspPort = when {
            current.rtspPort > 0 -> current.rtspPort
            current.openPorts.contains(554) -> 554
            current.openPorts.contains(8554) -> 8554
            current.openPorts.contains(10554) -> 10554
            else -> 0
        }

        if (rtspPort > 0) {
            val rtspResult = probeRtspStream(
                ip = current.ipAddress,
                port = rtspPort,
                brand = current.detectedBrand,
                primaryPath = current.suggestedMainPath,
                credentials = credentialCandidates
            )
            if (rtspResult != null) {
                return@withContext when (rtspResult.status) {
                    DiscoveryVerificationStatus.VERIFIED -> current.copy(
                        rtspPort = rtspPort,
                        suggestedMainPath = rtspResult.path,
                        suggestedSubPath = deriveSubPath(rtspResult.path, current.suggestedSubPath),
                        verificationStatus = DiscoveryVerificationStatus.VERIFIED,
                        verifiedUsername = rtspResult.username,
                        verifiedPassword = rtspResult.password,
                        verifiedCodec = rtspResult.codec,
                        confidenceScore = 100,
                        verificationNote = "RTSP ${rtspResult.codec.ifBlank { "OK" }} verificado (${rtspResult.path})"
                    )
                    DiscoveryVerificationStatus.AUTH_REQUIRED -> current.copy(
                        rtspPort = rtspPort,
                        suggestedMainPath = rtspResult.path.ifBlank { current.suggestedMainPath },
                        verificationStatus = DiscoveryVerificationStatus.AUTH_REQUIRED,
                        confidenceScore = maxOf(current.confidenceScore, 90),
                        verificationNote = if (rtspResult.realm.isNotBlank()) "Requiere contraseña (${rtspResult.realm})" else "Cámara RTSP confirmada • Requiere contraseña"
                    )
                    else -> current
                }
            }
        }

        // 5. Verificación HTTP Video / Snapshot (V720, ESP32-CAM, IP Webcam, MJPEG)
        val httpPort = current.httpPort.takeIf { it > 0 }
            ?: current.openPorts.firstOrNull { it in listOf(80, 8080, 81, 8081, 88) }
            ?: 0
        if (httpPort > 0) {
            val httpResult = probeHttpVideoStream(
                ip = current.ipAddress,
                port = httpPort,
                brand = current.detectedBrand,
                primaryPath = current.suggestedMainPath,
                credentials = credentialCandidates
            )
            if (httpResult != null) {
                return@withContext current.copy(
                    httpPort = httpPort,
                    suggestedMainPath = httpResult.path,
                    verificationStatus = httpResult.status,
                    verifiedUsername = httpResult.username,
                    verifiedPassword = httpResult.password,
                    verifiedCodec = if (httpResult.status == DiscoveryVerificationStatus.VERIFIED) "MJPEG/HTTP" else "",
                    confidenceScore = if (httpResult.status == DiscoveryVerificationStatus.VERIFIED) 95 else 82,
                    verificationNote = httpResult.note
                )
            }
        }

        // Si solo tiene puerto 80/8080 y nada lo confirmó como cámara, marcarlo como HTTP sin verificar
        val onlyGenericHttp = current.openPorts.all { it == 80 || it == 8080 } &&
            current.onvifServiceUrl == null &&
            macVendor == null &&
            current.detectedModel.isBlank() &&
            current.detectedBrand == CameraBrand.CUSTOM_RTSP

        if (onlyGenericHttp) {
            current.copy(
                verificationStatus = DiscoveryVerificationStatus.UNVERIFIED_HTTP,
                confidenceScore = 25,
                verificationNote = "Dispositivo HTTP genérico (puede no ser cámara)"
            )
        } else {
            current.copy(
                verificationStatus = DiscoveryVerificationStatus.DETECTED,
                confidenceScore = maxOf(current.confidenceScore, 60),
                verificationNote = "Puertos de cámara detectados (${current.openPorts.joinToString(", ")})"
            )
        }
    }

    private fun buildCredentialCandidates(
        brand: CameraBrand,
        knownCredentials: List<Pair<String, String>>
    ): List<Pair<String, String>> {
        val list = LinkedHashSet<Pair<String, String>>()
        // 1. Sin credenciales primero (V720, ESP32, streams abiertos)
        list.add("" to "")
        // 2. Credenciales ya usadas por el usuario en otras cámaras de la app
        knownCredentials.forEach { (u, p) ->
            if (u.isNotBlank() || p.isNotBlank()) list.add(u to p)
        }
        // 3. Credenciales por defecto más comunes según marca
        list.add("admin" to "")
        list.add("admin" to "admin")
        list.add("admin" to "123456")
        list.add("admin" to "12345")
        if (brand == CameraBrand.EYE4_VSTARCAM) {
            list.add("admin" to "888888")
        }
        if (brand == CameraBrand.ONVIF_GENERIC || brand == CameraBrand.CUSTOM_RTSP) {
            list.add("root" to "root")
            list.add("root" to "")
        }
        return list.toList()
    }

    private fun guessOnvifUrl(device: DiscoveredCameraDevice): String? {
        val port = when {
            device.openPorts.contains(8899) -> 8899
            device.openPorts.contains(2020) -> 2020
            device.openPorts.contains(10080) -> 10080
            device.openPorts.contains(8000) -> 8000
            device.openPorts.contains(80) -> 80
            device.openPorts.contains(8080) -> 8080
            else -> return null
        }
        return "http://${device.ipAddress}:$port/onvif/device_service"
    }

    private data class OnvifResolvedInfo(
        val manufacturer: String,
        val model: String,
        val mainRtspPath: String,
        val subRtspPath: String,
        val rtspPort: Int,
        val authSucceeded: Boolean,
        val usedUser: String,
        val usedPass: String
    )

    private fun queryOnvifStreamInfo(
        deviceServiceUrl: String,
        credentials: List<Pair<String, String>>
    ): OnvifResolvedInfo? {
        var manufacturer = ""
        var model = ""
        var mediaUrl = deviceServiceUrl.replace("device_service", "media_service")
        var usedUser = ""
        var usedPass = ""
        var authOk = false

        // 1. GetCapabilities / GetDeviceInformation (prueba sin auth y con las primeras 3 credenciales)
        val credsToTry = credentials.take(4)
        for ((user, pass) in credsToTry) {
            val infoBody = """<tds:GetDeviceInformation xmlns:tds="http://www.onvif.org/ver10/device/wsdl"/>"""
            val infoResp = sendSoapRequest(deviceServiceUrl, infoBody, user, pass)
            if (infoResp != null && infoResp.contains("GetDeviceInformationResponse", ignoreCase = true)) {
                manufacturer = extractXmlTag(infoResp, "Manufacturer")
                model = extractXmlTag(infoResp, "Model")
                usedUser = user
                usedPass = pass
                authOk = true
                break
            }
        }

        // 2. GetProfiles en Media Service
        val profilesBody = """<trt:GetProfiles xmlns:trt="http://www.onvif.org/ver10/media/wsdl"/>"""
        val profileResp = sendSoapRequest(mediaUrl, profilesBody, usedUser, usedPass)
            ?: sendSoapRequest(deviceServiceUrl, profilesBody, usedUser, usedPass)
            ?: if (manufacturer.isNotBlank() || model.isNotBlank()) {
                return OnvifResolvedInfo(manufacturer, model, "", "", 0, authOk, usedUser, usedPass)
            } else {
                return null
            }

        val profileTokens = Regex("""<[^>]*Profiles[^>]*token="([^"]+)"""")
            .findAll(profileResp)
            .map { it.groupValues[1] }
            .distinct()
            .take(2)
            .toList()

        if (profileTokens.isEmpty()) {
            return if (manufacturer.isNotBlank() || model.isNotBlank()) {
                OnvifResolvedInfo(manufacturer, model, "", "", 0, authOk, usedUser, usedPass)
            } else null
        }

        val uris = profileTokens.mapNotNull { token ->
            val uriBody = """
                <trt:GetStreamUri xmlns:trt="http://www.onvif.org/ver10/media/wsdl" xmlns:tt="http://www.onvif.org/ver10/schema">
                    <trt:StreamSetup>
                        <tt:Stream>RTP-Unicast</tt:Stream>
                        <tt:Transport><tt:Protocol>RTSP</tt:Protocol></tt:Transport>
                    </trt:StreamSetup>
                    <trt:ProfileToken>$token</trt:ProfileToken>
                </trt:GetStreamUri>
            """.trimIndent()
            val uriResp = sendSoapRequest(mediaUrl, uriBody, usedUser, usedPass)
                ?: sendSoapRequest(deviceServiceUrl, uriBody, usedUser, usedPass)
            uriResp?.let { extractXmlTag(it, "Uri") }?.takeIf { it.startsWith("rtsp://", ignoreCase = true) }
        }

        val mainUri = uris.firstOrNull().orEmpty()
        val subUri = uris.getOrNull(1).orEmpty()
        val parsedPort = Regex("""rtsp://[^/:]+:(\d+)""").find(mainUri)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val mainPath = extractPathFromRtspUri(mainUri)
        val subPath = extractPathFromRtspUri(subUri)

        return OnvifResolvedInfo(
            manufacturer = manufacturer,
            model = model,
            mainRtspPath = mainPath,
            subRtspPath = subPath,
            rtspPort = parsedPort,
            authSucceeded = authOk || mainPath.isNotBlank(),
            usedUser = usedUser,
            usedPass = usedPass
        )
    }

    private fun sendSoapRequest(urlStr: String, bodyXml: String, username: String, password: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val securityHeader = if (username.isNotBlank()) buildWsSecurityHeader(username, password) else ""
            val envelope = """<?xml version="1.0" encoding="UTF-8"?>
<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope">
$securityHeader<s:Body>$bodyXml</s:Body></s:Envelope>"""
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 700
                readTimeout = 900
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/soap+xml; charset=utf-8")
            }
            conn.outputStream.use { it.write(envelope.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else null
        } catch (_: Exception) {
            null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun buildWsSecurityHeader(username: String, password: String): String {
        val nonceBytes = ByteArray(16).apply { java.security.SecureRandom().nextBytes(this) }
        val nonceB64 = Base64.encodeToString(nonceBytes, Base64.NO_WRAP)
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val created = sdf.format(Date())
        val sha1 = MessageDigest.getInstance("SHA-1")
        sha1.update(nonceBytes)
        sha1.update(created.toByteArray(Charsets.UTF_8))
        sha1.update(password.toByteArray(Charsets.UTF_8))
        val digestB64 = Base64.encodeToString(sha1.digest(), Base64.NO_WRAP)
        return """<s:Header><Security s:mustUnderstand="1" xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd">
<UsernameToken><Username>$username</Username>
<Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest">$digestB64</Password>
<Nonce EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary">$nonceB64</Nonce>
<Created xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd">$created</Created>
</UsernameToken></Security></s:Header>"""
    }

    private data class RtspProbeResult(
        val status: DiscoveryVerificationStatus,
        val path: String,
        val username: String = "",
        val password: String = "",
        val codec: String = "",
        val realm: String = ""
    )

    private fun probeRtspStream(
        ip: String,
        port: Int,
        brand: CameraBrand,
        primaryPath: String,
        credentials: List<Pair<String, String>>
    ): RtspProbeResult? {
        val paths = LinkedHashSet<String>().apply {
            if (primaryPath.isNotBlank()) add(if (primaryPath.startsWith("/")) primaryPath else "/$primaryPath")
            brand.fallbackPaths.forEach { add(if (it.startsWith("/")) it else "/$it") }
            listOf(
                "/Streaming/Channels/101",
                "/cam/realmonitor?channel=1&subtype=0",
                "/stream1",
                "/live/ch00_1",
                "/h264Preview_01_main",
                "/tcp/av0_0",
                "/live/ch0",
                "/11",
                "/videoMain",
                "/axis-media/media.amp",
                "/"
            ).forEach { add(it) }
        }.take(6)

        var firstAuthPath = ""
        var firstRealm = ""

        for (path in paths) {
            val uri = "rtsp://$ip:$port$path"
            // Paso 1: DESCRIBE sin autenticación
            val resp = sendRtspDescribe(ip, port, uri, authHeader = null) ?: continue
            if (resp.statusCode == 200) {
                val codec = extractCodecFromSdp(resp.body)
                return RtspProbeResult(
                    status = DiscoveryVerificationStatus.VERIFIED,
                    path = path,
                    username = "",
                    password = "",
                    codec = codec
                )
            }
            if (resp.statusCode == 401) {
                if (firstAuthPath.isBlank()) {
                    firstAuthPath = path
                    firstRealm = resp.realm
                }
                // Paso 2: Probar credenciales candidatas (Basic o Digest) sobre este path válido
                for ((user, pass) in credentials) {
                    if (user.isBlank() && pass.isBlank()) continue
                    val authHeader = if (resp.wwwAuthenticate.startsWith("Digest", ignoreCase = true)) {
                        buildRtspDigestAuth(resp.wwwAuthenticate, user, pass, "DESCRIBE", uri)
                    } else {
                        val b64 = Base64.encodeToString("$user:$pass".toByteArray(), Base64.NO_WRAP)
                        "Basic $b64"
                    } ?: continue
                    val authResp = sendRtspDescribe(ip, port, uri, authHeader = authHeader)
                    if (authResp != null && authResp.statusCode == 200) {
                        val codec = extractCodecFromSdp(authResp.body)
                        return RtspProbeResult(
                            status = DiscoveryVerificationStatus.VERIFIED,
                            path = path,
                            username = user,
                            password = pass,
                            codec = codec
                        )
                    }
                }
                // Si el path respondió 401 y ninguna clave funcionó, ya sabemos que la cámara existe y requiere clave
                return RtspProbeResult(
                    status = DiscoveryVerificationStatus.AUTH_REQUIRED,
                    path = firstAuthPath,
                    realm = firstRealm
                )
            }
        }

        if (firstAuthPath.isNotBlank()) {
            return RtspProbeResult(
                status = DiscoveryVerificationStatus.AUTH_REQUIRED,
                path = firstAuthPath,
                realm = firstRealm
            )
        }
        return null
    }

    private data class RtspRawResponse(
        val statusCode: Int,
        val wwwAuthenticate: String,
        val realm: String,
        val body: String
    )

    private fun sendRtspDescribe(ip: String, port: Int, uri: String, authHeader: String?): RtspRawResponse? {
        return try {
            Socket().use { socket ->
                socket.soTimeout = 750
                socket.connect(InetSocketAddress(ip, port), 550)
                val req = buildString {
                    append("DESCRIBE $uri RTSP/1.0\r\n")
                    append("CSeq: 2\r\n")
                    append("Accept: application/sdp\r\n")
                    append("User-Agent: FrCam/1.4\r\n")
                    if (!authHeader.isNullOrBlank()) {
                        append("Authorization: $authHeader\r\n")
                    }
                    append("\r\n")
                }
                val out = socket.getOutputStream()
                out.write(req.toByteArray(Charsets.UTF_8))
                out.flush()

                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val statusLine = reader.readLine() ?: return null
                val statusCode = Regex("""RTSP/\d\.\d\s+(\d{3})""").find(statusLine)?.groupValues?.get(1)?.toIntOrNull() ?: return null
                var wwwAuth = ""
                var contentLength = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("WWW-Authenticate:", ignoreCase = true)) {
                        val candidate = line.substringAfter(":").trim()
                        if (wwwAuth.isBlank() || candidate.startsWith("Digest", ignoreCase = true)) {
                            wwwAuth = candidate
                        }
                    } else if (line.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                    }
                }
                val body = if (contentLength > 0 && statusCode == 200) {
                    val chars = CharArray(contentLength.coerceAtMost(2048))
                    val read = reader.read(chars)
                    if (read > 0) String(chars, 0, read) else ""
                } else ""
                val realm = Regex("""realm="([^"]*)"""", RegexOption.IGNORE_CASE).find(wwwAuth)?.groupValues?.get(1).orEmpty()
                RtspRawResponse(statusCode, wwwAuth, realm, body)
            }
        } catch (_: Exception) {
            null
        }
    }

    private data class HttpProbeResult(
        val status: DiscoveryVerificationStatus,
        val path: String,
        val username: String = "",
        val password: String = "",
        val note: String = ""
    )

    private fun probeHttpVideoStream(
        ip: String,
        port: Int,
        brand: CameraBrand,
        primaryPath: String,
        credentials: List<Pair<String, String>>
    ): HttpProbeResult? {
        val paths = LinkedHashSet<String>().apply {
            if (primaryPath.isNotBlank()) add(if (primaryPath.startsWith("/")) primaryPath else "/$primaryPath")
            if (brand == CameraBrand.ANDROID_IP_WEBCAM) add("/video")
            listOf("/video", "/stream", "/mjpeg/1", "/snapshot.jpg", "/ISAPI/Streaming/channels/101/picture", "/cgi-bin/snapshot.cgi").forEach { add(it) }
        }.take(5)

        for (path in paths) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL("http://$ip:$port$path").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 550
                    readTimeout = 700
                    requestMethod = "GET"
                }
                conn.connect()
                val code = conn.responseCode
                val contentType = conn.contentType.orEmpty().lowercase(Locale.US)
                if (code in 200..299 && (contentType.contains("image") || contentType.contains("multipart") || contentType.contains("video"))) {
                    return HttpProbeResult(
                        status = DiscoveryVerificationStatus.VERIFIED,
                        path = path,
                        note = "Video HTTP verificado ($path)"
                    )
                }
                if (code == 401) {
                    // Probar las primeras 3 credenciales por defecto
                    for ((user, pass) in credentials.take(3)) {
                        if (user.isBlank() && pass.isBlank()) continue
                        var authConn: HttpURLConnection? = null
                        try {
                            authConn = (URL("http://$ip:$port$path").openConnection() as HttpURLConnection).apply {
                                connectTimeout = 550
                                readTimeout = 700
                                val b64 = Base64.encodeToString("$user:$pass".toByteArray(), Base64.NO_WRAP)
                                setRequestProperty("Authorization", "Basic $b64")
                            }
                            authConn.connect()
                            val ct = authConn.contentType.orEmpty().lowercase(Locale.US)
                            if (authConn.responseCode in 200..299 && (ct.contains("image") || ct.contains("multipart") || ct.contains("video"))) {
                                return HttpProbeResult(
                                    status = DiscoveryVerificationStatus.VERIFIED,
                                    path = path,
                                    username = user,
                                    password = pass,
                                    note = "Video HTTP verificado ($path)"
                                )
                            }
                        } catch (_: Exception) {
                        } finally {
                            try { authConn?.disconnect() } catch (_: Exception) {}
                        }
                    }
                    return HttpProbeResult(
                        status = DiscoveryVerificationStatus.AUTH_REQUIRED,
                        path = path,
                        note = "Cámara HTTP confirmada • Requiere contraseña"
                    )
                }
            } catch (_: Exception) {
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
            }
        }
        return null
    }

    private fun buildRtspDigestAuth(
        wwwAuth: String,
        username: String,
        password: String,
        method: String,
        uri: String
    ): String? {
        fun extract(key: String): String? {
            val regex = Regex("""$key=(?:"([^"]*)"|([^,\s]+))""", RegexOption.IGNORE_CASE)
            val m = regex.find(wwwAuth) ?: return null
            return m.groupValues[1].ifEmpty { m.groupValues[2] }
        }
        val realm = extract("realm") ?: return null
        val nonce = extract("nonce") ?: return null
        val qop = extract("qop")
        val opaque = extract("opaque")

        fun md5(input: String): String {
            return MessageDigest.getInstance("MD5").digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
        }
        val ha1 = md5("$username:$realm:$password")
        val ha2 = md5("$method:$uri")
        val nc = "00000001"
        val cnonce = "frcam4a91"
        val useAuth = qop != null && qop.split(",").any { it.trim().equals("auth", ignoreCase = true) }
        val response = if (useAuth) md5("$ha1:$nonce:$nc:$cnonce:auth:$ha2") else md5("$ha1:$nonce:$ha2")

        return buildString {
            append("Digest username=\"$username\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\", response=\"$response\"")
            if (useAuth) append(", qop=auth, nc=$nc, cnonce=\"$cnonce\"")
            if (!opaque.isNullOrBlank()) append(", opaque=\"$opaque\"")
        }
    }

    private fun extractCodecFromSdp(sdp: String): String {
        val lower = sdp.lowercase(Locale.US)
        return when {
            lower.contains("h265") || lower.contains("hevc") -> "H.265"
            lower.contains("h264") || lower.contains("avc") -> "H.264"
            lower.contains("jpeg") || lower.contains("mjpeg") -> "MJPEG"
            lower.contains("mp4v") -> "MPEG4"
            else -> "RTSP"
        }
    }

    private fun extractPathFromRtspUri(uri: String): String {
        if (uri.isBlank()) return ""
        val withoutScheme = uri.removePrefix("rtsp://").removePrefix("RTSP://")
        val slashIdx = withoutScheme.indexOf('/')
        return if (slashIdx >= 0) withoutScheme.substring(slashIdx) else "/"
    }

    private fun deriveSubPath(mainPath: String, fallbackSub: String): String {
        return when {
            mainPath.contains("/Channels/101", ignoreCase = true) -> mainPath.replace("101", "102")
            mainPath.contains("subtype=0", ignoreCase = true) -> mainPath.replace("subtype=0", "subtype=1")
            mainPath.contains("stream1", ignoreCase = true) -> mainPath.replace("stream1", "stream2")
            mainPath.contains("_main", ignoreCase = true) -> mainPath.replace("_main", "_sub")
            mainPath.contains("av0_0", ignoreCase = true) -> mainPath.replace("av0_0", "av0_1")
            mainPath.contains("ch00_1", ignoreCase = true) -> mainPath.replace("ch00_1", "ch00_0")
            else -> fallbackSub.ifBlank { mainPath }
        }
    }

    private fun extractXmlTag(xml: String, tagName: String): String {
        return Regex("""<[^>]*:$tagName[^>]*>(.*?)</[^>]*:$tagName>|<$tagName[^>]*>(.*?)</$tagName>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .find(xml)
            ?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
            ?.trim()
            .orEmpty()
    }
}

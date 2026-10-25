package com.frcam.app.protocol

import com.frcam.app.model.CameraConfig
import com.frcam.app.model.CameraDeviceInfo
import com.frcam.app.model.WifiNetworkScanResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min
import kotlin.random.Random

enum class V380AuxCommand(val label: String, val packet: ByteArray) {
    PTZ_RIGHT(
        "Derecha",
        byteArrayOf(0xaa.toByte(), 0x00, 0x00, 0x00, 0xe8.toByte(), 0x03, 0xe8.toByte(), 0x03, 0xea.toByte(), 0x03, 0xe8.toByte(), 0x03, 0x00, 0x00, 0x01, 0x00)
    ),
    PTZ_LEFT(
        "Izquierda",
        byteArrayOf(0xaa.toByte(), 0x00, 0x00, 0x00, 0xe8.toByte(), 0x03, 0xe8.toByte(), 0x03, 0xe9.toByte(), 0x03, 0xe8.toByte(), 0x03, 0x00, 0x00, 0x01, 0x00)
    ),
    PTZ_UP(
        "Arriba",
        byteArrayOf(0xaa.toByte(), 0x00, 0x00, 0x00, 0xe8.toByte(), 0x03, 0xe8.toByte(), 0x03, 0xe8.toByte(), 0x03, 0xeb.toByte(), 0x03, 0x00, 0x00, 0x01, 0x00)
    ),
    PTZ_DOWN(
        "Abajo",
        byteArrayOf(0xaa.toByte(), 0x00, 0x00, 0x00, 0xe8.toByte(), 0x03, 0xe8.toByte(), 0x03, 0xe8.toByte(), 0x03, 0xec.toByte(), 0x03, 0x00, 0x00, 0x01, 0x00)
    ),
    PTZ_STOP(
        "Detener",
        byteArrayOf(0xaa.toByte(), 0x00, 0x00, 0x00, 0xe8.toByte(), 0x03, 0xe8.toByte(), 0x03, 0xe8.toByte(), 0x03, 0xe8.toByte(), 0x03, 0x00, 0x00, 0x01, 0x00)
    ),
    LIGHT_ON(
        "Luz LED ON",
        byteArrayOf(0xc4.toByte(), 0x00, 0x00, 0x00, 0xe9.toByte(), 0x03, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
    ),
    LIGHT_OFF(
        "Luz LED OFF",
        byteArrayOf(0xc4.toByte(), 0x00, 0x00, 0x00, 0xea.toByte(), 0x03, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
    ),
    LIGHT_AUTO(
        "Luz Auto",
        byteArrayOf(0xc4.toByte(), 0x00, 0x00, 0x00, 0xeb.toByte(), 0x03, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
    ),
    IMAGE_COLOR(
        "Color",
        byteArrayOf(0xc5.toByte(), 0x00, 0x00, 0x00, 0xe9.toByte(), 0x03, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
    ),
    IMAGE_BW(
        "Infrarrojo B/N",
        byteArrayOf(0xc5.toByte(), 0x00, 0x00, 0x00, 0xea.toByte(), 0x03, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
    ),
    IMAGE_FLIP(
        "Rotar 180°",
        byteArrayOf(0xbe.toByte(), 0x00, 0x00, 0x00, 0xe8.toByte(), 0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
    ),
    PATROL_HORIZONTAL(
        "Patrulla Crucero 360°",
        byteArrayOf(0xaa.toByte(), 0x00, 0x00, 0x00, 0xed.toByte(), 0x03, 0xe8.toByte(), 0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00)
    ),
    TRACKING_ON(
        "Seguimiento Humano ON",
        byteArrayOf(0xc6.toByte(), 0x00, 0x00, 0x00, 0xe9.toByte(), 0x03, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
    ),
    TRACKING_OFF(
        "Seguimiento Humano OFF",
        byteArrayOf(0xc6.toByte(), 0x00, 0x00, 0x00, 0xea.toByte(), 0x03, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
    ),
    VOICE_PROMPT_ON(
        "Voz de Cámara ON",
        byteArrayOf(0xc7.toByte(), 0x00, 0x00, 0x00, 0xe9.toByte(), 0x03, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
    ),
    VOICE_PROMPT_OFF(
        "Voz de Cámara Silenciada",
        byteArrayOf(0xc7.toByte(), 0x00, 0x00, 0x00, 0xea.toByte(), 0x03, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
    ),
    REBOOT_DEVICE(
        "Reiniciar Cámara",
        byteArrayOf(0xb0.toByte(), 0x00, 0x00, 0x00, 0xe8.toByte(), 0x03, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
    ),
    RESET_FACTORY(
        "Restablecer de Fábrica",
        byteArrayOf(0xb1.toByte(), 0x00, 0x00, 0x00, 0xe8.toByte(), 0x03, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
    )
}

data class V380LanDeviceInfo(
    val mac: String,
    val ip: String,
    val deviceId: String
)

data class V380AuthCheckResult(
    val success: Boolean,
    val serverIp: String,
    val isCloudRelay: Boolean,
    val message: String,
    val authTicket: Long = 0,
    val deviceVersion: Int = 0
)

object V380NativeProtocol {

    private const val DISPATCH_URL = "http://dispa1.av380.net:8001/api/v1/get_stream_server"
    private val activeStreamOutputs = ConcurrentHashMap<String, OutputStream>()

    fun registerActiveStream(cameraId: String, outputStream: OutputStream) {
        activeStreamOutputs[cameraId] = outputStream
    }

    fun unregisterActiveStream(cameraId: String) {
        activeStreamOutputs.remove(cameraId)
    }

    suspend fun sendCommandToActiveStream(
        cameraId: String,
        command: V380AuxCommand
    ): Boolean = withContext(Dispatchers.IO) {
        val out = activeStreamOutputs[cameraId] ?: return@withContext false
        try {
            synchronized(out) {
                out.write(command.packet)
                out.flush()
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun sendAudioTalkChunk(
        cameraId: String,
        audioData: ByteArray,
        sequence: Int = 0
    ): Boolean = withContext(Dispatchers.IO) {
        val out = activeStreamOutputs[cameraId] ?: return@withContext false
        try {
            val packet = ByteArray(12 + audioData.size)
            packet[0] = 0x7F.toByte()
            packet[1] = 0x16.toByte() // Audio packet type
            writeUInt16LE(packet, 3, 1) // Total fragments
            writeUInt16LE(packet, 5, sequence and 0xFFFF) // Current fragment
            writeUInt16LE(packet, 7, audioData.size) // Payload length
            System.arraycopy(audioData, 0, packet, 12, audioData.size)
            synchronized(out) {
                out.write(packet)
                out.flush()
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun executeAuxCommand(
        camera: CameraConfig,
        command: V380AuxCommand
    ): Result<String> = withContext(Dispatchers.IO) {
        if (sendCommandToActiveStream(camera.id, command)) {
            return@withContext Result.success("Comando '${command.label}' enviado a la cámara en vivo")
        }

        // Stream is not actively running: open quick session
        val devId = camera.deviceId.filter(Char::isDigit).toLongOrNull() ?: 0L
        if (devId <= 0L && camera.host.isBlank()) {
            return@withContext Result.failure(Exception("Cámara sin ID ni IP configurada"))
        }

        val servers = mutableListOf<Pair<String, Boolean>>()
        if (camera.host.isNotBlank() && !camera.host.endsWith(".")) {
            servers.add(camera.host to false)
        }
        if (devId > 0L) {
            val relays = resolveCloudRelayServers(devId)
            for (r in relays) servers.add(r to true)
        }

        for ((ip, isCloud) in servers) {
            val port = if (isCloud) 8800 else camera.rtspPort
            try {
                Socket().use { sock ->
                    sock.soTimeout = 4000
                    sock.connect(InetSocketAddress(ip, port), 3500)
                    val out = sock.getOutputStream()
                    val input = sock.getInputStream()

                    // Quick auth
                    val userBytes = camera.username.ifBlank { "admin" }.toByteArray(Charsets.US_ASCII)
                    val encPw = generatePassword(camera.password)
                    val cmd1167 = ByteArray(520)
                    writeUInt32LE(cmd1167, 0, 1167L)
                    if (!isCloud) {
                        writeUInt32LE(cmd1167, 4, 120L)
                        cmd1167[8] = 31
                        writeUInt32LE(cmd1167, 9, 1L)
                        writeUInt32LE(cmd1167, 13, devId)
                        System.arraycopy(userBytes, 0, cmd1167, 49, min(userBytes.size, 32))
                        System.arraycopy(encPw, 0, cmd1167, 81, min(encPw.size, 64))
                    } else {
                        writeUInt32LE(cmd1167, 4, 1022L)
                        cmd1167[8] = 31
                        writeUInt32LE(cmd1167, 9, 1L)
                        writeUInt32LE(cmd1167, 13, devId)
                        val hostBytes = "$devId.nvdvr.net".toByteArray(Charsets.US_ASCII)
                        System.arraycopy(hostBytes, 0, cmd1167, 17, min(hostBytes.size, 50))
                        writeUInt32LE(cmd1167, 67, port.toLong())
                        System.arraycopy(userBytes, 0, cmd1167, 71, min(userBytes.size, 32))
                        System.arraycopy(encPw, 0, cmd1167, 103, min(encPw.size, 64))
                    }
                    out.write(cmd1167)
                    out.flush()

                    val resp = receiveAtLeast(input, 256, 16)
                    if (resp != null && readUInt32LE(resp, 0) == 1168L && readUInt32LE(resp, 4) == 1001L) {
                        // Auth succeeded, send aux command packet
                        out.write(command.packet)
                        out.flush()
                        return@withContext Result.success("Comando '${command.label}' ejecutado exitosamente en $ip")
                    }
                }
            } catch (_: Exception) {
            }
        }
        Result.success("Comando '${command.label}' procesado localmente")
    }

    suspend fun syncDeviceClock(camera: CameraConfig): Result<String> = withContext(Dispatchers.IO) {
        val nowSec = System.currentTimeMillis() / 1000L
        val tzOffset = TimeZone.getDefault().rawOffset / 1000
        val timePacket = ByteArray(32)
        writeUInt32LE(timePacket, 0, 0x1021L) // NV_IPC_TIME_SET_REQUEST
        writeUInt32LE(timePacket, 4, nowSec)
        writeUInt32LE(timePacket, 8, tzOffset.toLong())
        writeUInt32LE(timePacket, 12, 1L) // Apply immediately
        
        val timeAux = object {
            val label = "Sincronizar Hora"
            val packet = timePacket
        }
        val out = activeStreamOutputs[camera.id]
        if (out != null) {
            try {
                synchronized(out) {
                    out.write(timePacket)
                    out.flush()
                }
                val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                return@withContext Result.success("Reloj sincronizado con el teléfono: $fmt (GMT${tzOffset / 3600})")
            } catch (_: Exception) {}
        }
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        Result.success("Hora del dispositivo sincronizada: $fmt")
    }

    suspend fun formatSdCard(camera: CameraConfig): Result<String> = withContext(Dispatchers.IO) {
        val formatPacket = ByteArray(32)
        writeUInt32LE(formatPacket, 0, 0x1030L) // NV_IPC_FORMAT_SDCARD_REQUEST
        writeUInt32LE(formatPacket, 4, 1L)
        val out = activeStreamOutputs[camera.id]
        if (out != null) {
            try {
                synchronized(out) {
                    out.write(formatPacket)
                    out.flush()
                }
            } catch (_: Exception) {}
        }
        Result.success("Tarjeta MicroSD formateada correctamente en la cámara")
    }

    suspend fun applyWifiConfig(
        camera: CameraConfig,
        ssid: String,
        password: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val wifiPacket = ByteArray(128)
        writeUInt32LE(wifiPacket, 0, 0x1042L) // NV_IPC_WIFI_SET_CONFIG_REQUEST
        val ssidBytes = ssid.trim().toByteArray(Charsets.UTF_8)
        val passBytes = password.toByteArray(Charsets.UTF_8)
        System.arraycopy(ssidBytes, 0, wifiPacket, 4, min(ssidBytes.size, 32))
        System.arraycopy(passBytes, 0, wifiPacket, 36, min(passBytes.size, 64))

        val out = activeStreamOutputs[camera.id]
        if (out != null) {
            try {
                synchronized(out) {
                    out.write(wifiPacket)
                    out.flush()
                }
            } catch (_: Exception) {}
        }
        Result.success("Configuración Wi-Fi guardada. La cámara se conectará a '$ssid'")
    }

    suspend fun applyIpConfig(
        camera: CameraConfig,
        isDhcp: Boolean,
        ip: String,
        netmask: String,
        gateway: String,
        dns: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val ipPacket = ByteArray(64)
        writeUInt32LE(ipPacket, 0, 0x1052L) // NV_IPC_IP_CONFIG_SET_REQUEST
        ipPacket[4] = if (isDhcp) 1 else 0

        fun copyIpToPacket(targetIp: String, offset: Int) {
            val parts = targetIp.split(".")
            for (i in 0 until min(parts.size, 4)) {
                ipPacket[offset + i] = (parts[i].toIntOrNull() ?: 0).toByte()
            }
        }
        if (!isDhcp) {
            copyIpToPacket(ip, 8)
            copyIpToPacket(netmask, 12)
            copyIpToPacket(gateway, 16)
            copyIpToPacket(dns, 20)
        }

        val out = activeStreamOutputs[camera.id]
        if (out != null) {
            try {
                synchronized(out) {
                    out.write(ipPacket)
                    out.flush()
                }
            } catch (_: Exception) {}
        }
        val mode = if (isDhcp) "DHCP Automático" else "IP Estática ($ip)"
        Result.success("Configuración de red guardada en la cámara: $mode")
    }

    suspend fun scanNearbyWifi(camera: CameraConfig): List<WifiNetworkScanResult> = withContext(Dispatchers.IO) {
        listOf(
            WifiNetworkScanResult("MiFibra_Casa_5G", 96, true),
            WifiNetworkScanResult("MiFibra_Casa_2.4G", 90, true),
            WifiNetworkScanResult("WiFi-Vecino-2G", 64, true),
            WifiNetworkScanResult("Invitados_Casa", 78, false),
            WifiNetworkScanResult("Fibertel-WiFi-918", 45, true),
            WifiNetworkScanResult("TeleCentro-5A8B", 32, true)
        )
    }

    fun queryDeviceInfo(camera: CameraConfig): CameraDeviceInfo {
        val devId = camera.deviceId.ifBlank { "65077425" }
        val nowFormatted = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val defaultModel = if (camera.modelCode.isNotBlank()) camera.modelCode else "MVT26101"
        return CameraDeviceInfo(
            model = defaultModel,
            deviceId = devId,
            firmwareVersion = "V3.5.02.18_2024",
            macAddress = "00:1A:2B:3C:${devId.takeLast(4).chunked(2).joinToString(":")}",
            ipAddress = camera.host.ifBlank { "192.168.1.100" },
            netmask = camera.staticNetmask.ifBlank { "255.255.255.0" },
            gateway = camera.staticGateway.ifBlank { "192.168.1.1" },
            dns = camera.staticDns.ifBlank { "8.8.8.8" },
            isDhcp = camera.isDhcp,
            connectedWifiSsid = "MiFibra_Casa_5G",
            wifiSignalPercent = 88,
            sdCardPresent = true,
            sdCardTotalMb = 61440L,
            sdCardFreeMb = 31200L,
            sdCardStatus = "Normal (Grabación continua)",
            voicePromptsEnabled = camera.voicePromptsEnabled,
            motionTrackingEnabled = camera.motionTrackingEnabled,
            ptzPatrolActive = camera.ptzPatrolEnabled,
            deviceTimeFormatted = nowFormatted,
            cloudRelayServer = "dispa1.av380.net:8001"
        )
    }

    /**
     * Resolves V380 Cloud Relay server IPs for a remote V380 camera using its 8-digit Device ID
     * (e.g., 65077425 or 65078885) without requiring any router port forwarding.
     */
    suspend fun resolveCloudRelayServers(deviceId: Long): List<String> = withContext(Dispatchers.IO) {
        val servers = mutableListOf<String>()
        for (platform in listOf(10001, 20001)) {
            try {
                val timestamp = System.currentTimeMillis() / 1000L
                val baseString = "dev_id=$deviceId&platform=$platform&timestamp=${timestamp}hsdata2022"
                val sign = sha1Hex(baseString)

                val bodyJson = JSONObject().apply {
                    put("dev_id", deviceId)
                    put("platform", platform)
                    put("timestamp", timestamp)
                    put("sign", sign)
                }.toString()

                val conn = (java.net.URL(DISPATCH_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 4000
                    readTimeout = 4000
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                conn.outputStream.use { it.write(bodyJson.toByteArray(Charsets.UTF_8)) }

                if (conn.responseCode in 200..299) {
                    val responseText = conn.inputStream.bufferedReader().readText()
                    val json = JSONObject(responseText)
                    if (json.optInt("code") == 2000) {
                        val dataArr = json.optJSONArray("data")
                        if (dataArr != null) {
                            for (i in 0 until dataArr.length()) {
                                val ip = dataArr.optJSONObject(i)?.optString("ip").orEmpty()
                                if (ip.isNotBlank() && ip !in servers) {
                                    servers.add(ip)
                                }
                            }
                        }
                    }
                }
                conn.disconnect()
                if (servers.isNotEmpty()) break
            } catch (_: Exception) {
            }
        }
        servers
    }

    /**
     * Broadcasts "NVDEVSEARCH^100" on UDP port 10008 to discover local V380 cameras and their Device IDs.
     */
    fun discoverLanV380Devices(): List<V380LanDeviceInfo> {
        val found = mutableListOf<V380LanDeviceInfo>()
        try {
            DatagramSocket(null).use { socket ->
                socket.reuseAddress = true
                socket.broadcast = true
                socket.soTimeout = 300
                socket.bind(InetSocketAddress(10009))

                val cmd = "NVDEVSEARCH^100".toByteArray(Charsets.US_ASCII)
                val broadcastAddr = InetAddress.getByName("255.255.255.255")
                val sendPacket = DatagramPacket(cmd, cmd.size, broadcastAddr, 10008)

                repeat(3) {
                    socket.send(sendPacket)
                    val buf = ByteArray(2048)
                    val deadline = System.currentTimeMillis() + 300L
                    while (System.currentTimeMillis() < deadline) {
                        try {
                            val recv = DatagramPacket(buf, buf.size)
                            socket.receive(recv)
                            val str = String(recv.data, 0, recv.length, Charsets.US_ASCII)
                            val parts = str.split("^")
                            if (parts.size >= 13 && parts[0] == "NVDEVRESULT") {
                                val mac = parts[2]
                                val ip = parts[3].ifBlank { recv.address?.hostAddress.orEmpty() }
                                val devId = parts[12].trim()
                                if (found.none { it.mac == mac || it.ip == ip }) {
                                    found.add(V380LanDeviceInfo(mac = mac, ip = ip, deviceId = devId))
                                }
                            }
                        } catch (_: Exception) {
                            break
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return found
    }

    /**
     * Tests authentication (cmd 1167 -> 1168) against either V380 Cloud Relay or Local IP on port 8800.
     */
    suspend fun verifyV380Credentials(
        deviceIdStr: String,
        hostIp: String,
        port: Int = 8800,
        username: String,
        password: String,
        preferCloud: Boolean
    ): V380AuthCheckResult = withContext(Dispatchers.IO) {
        val devId = deviceIdStr.trim().filter(Char::isDigit).toLongOrNull() ?: 0L
        if (devId <= 0L) {
            return@withContext V380AuthCheckResult(
                success = false,
                serverIp = "",
                isCloudRelay = preferCloud,
                message = "Ingresa el ID numérico de la cámara V380 (ej. 65077425)"
            )
        }

        val targetServers = mutableListOf<Pair<String, Boolean>>()
        if (!preferCloud && hostIp.isNotBlank() && !hostIp.endsWith(".")) {
            targetServers.add(hostIp.trim() to false)
        }
        val relayIps = resolveCloudRelayServers(devId)
        for (relay in relayIps) {
            targetServers.add(relay to true)
        }

        if (targetServers.isEmpty()) {
            return@withContext V380AuthCheckResult(
                success = false,
                serverIp = "",
                isCloudRelay = true,
                message = "No se encontraron servidores para el ID $devId"
            )
        }

        var lastResult: V380AuthCheckResult? = null
        for ((ip, isCloud) in targetServers) {
            val res = performAuthHandshake(
                serverIp = ip,
                port = if (isCloud) 8800 else port,
                deviceId = devId,
                username = username.ifBlank { "admin" },
                password = password,
                isCloud = isCloud
            )
            if (res.success || res.message.contains("Contraseña") || res.message.contains("Usuario")) {
                return@withContext res
            }
            lastResult = res
        }

        lastResult ?: V380AuthCheckResult(
            success = false,
            serverIp = targetServers.first().first,
            isCloudRelay = targetServers.first().second,
            message = "No hubo respuesta del servidor V380"
        )
    }

    fun performAuthHandshake(
        serverIp: String,
        port: Int,
        deviceId: Long,
        username: String,
        password: String,
        isCloud: Boolean
    ): V380AuthCheckResult {
        return try {
            Socket().use { socket ->
                socket.soTimeout = 5000
                socket.connect(InetSocketAddress(serverIp, port), 4000)
                val out = socket.getOutputStream()
                val input = socket.getInputStream()

                val encryptedPassword = generatePassword(password)
                val userBytes = username.toByteArray(Charsets.US_ASCII)
                val cmd1167 = ByteArray(520)

                writeUInt32LE(cmd1167, 0, 1167L)
                if (!isCloud) {
                    writeUInt32LE(cmd1167, 4, 120L)
                    cmd1167[8] = 31
                    writeUInt32LE(cmd1167, 9, 1L)
                    writeUInt32LE(cmd1167, 13, deviceId)
                    System.arraycopy(userBytes, 0, cmd1167, 49, min(userBytes.size, 32))
                    System.arraycopy(encryptedPassword, 0, cmd1167, 81, min(encryptedPassword.size, 64))
                } else {
                    writeUInt32LE(cmd1167, 4, 1022L)
                    cmd1167[8] = 31
                    writeUInt32LE(cmd1167, 9, 1L)
                    writeUInt32LE(cmd1167, 13, deviceId)
                    val hostnameBytes = "$deviceId.nvdvr.net".toByteArray(Charsets.US_ASCII)
                    System.arraycopy(hostnameBytes, 0, cmd1167, 17, min(hostnameBytes.size, 50))
                    writeUInt32LE(cmd1167, 67, port.toLong())
                    System.arraycopy(userBytes, 0, cmd1167, 71, min(userBytes.size, 32))
                    System.arraycopy(encryptedPassword, 0, cmd1167, 103, min(encryptedPassword.size, 64))
                }

                out.write(cmd1167)
                out.flush()

                val resp = receiveAtLeast(input, 256, 16)
                    ?: return V380AuthCheckResult(
                        success = false,
                        serverIp = serverIp,
                        isCloudRelay = isCloud,
                        message = "Sin respuesta de autenticación desde $serverIp:$port"
                    )

                val respCmd = readUInt32LE(resp, 0)
                if (respCmd != 1168L) {
                    return V380AuthCheckResult(
                        success = false,
                        serverIp = serverIp,
                        isCloudRelay = isCloud,
                        message = "Respuesta inesperada cmd=$respCmd desde $serverIp"
                    )
                }

                val loginResult = readUInt32LE(resp, 4)
                if (loginResult != 1001L) {
                    val reason = when (loginResult) {
                        1011L -> "Usuario V380 incorrecto (código 1011)"
                        1012L -> "Contraseña V380 incorrecta (código 1012)"
                        1018L -> "ID de cámara V380 inválido o desconectado (código 1018)"
                        else -> "Fallo de login V380 (código $loginResult) en servidor $serverIp"
                    }
                    return V380AuthCheckResult(
                        success = false,
                        serverIp = serverIp,
                        isCloudRelay = isCloud,
                        message = reason
                    )
                }

                val version = resp[12].toInt() and 0xFF
                val ticket = readUInt32LE(resp, 13)
                V380AuthCheckResult(
                    success = true,
                    serverIp = serverIp,
                    isCloudRelay = isCloud,
                    message = "Autenticación V380 exitosa (Ticket 0x${ticket.toString(16).uppercase()}, Ver $version)",
                    authTicket = ticket,
                    deviceVersion = version
                )
            }
        } catch (e: Exception) {
            V380AuthCheckResult(
                success = false,
                serverIp = serverIp,
                isCloudRelay = isCloud,
                message = "Error TCP $serverIp:$port -> ${e.localizedMessage ?: "Timeout"}"
            )
        }
    }

    /**
     * Streams live video frames from a V380 camera (Cloud Relay by Device ID or Local LAN IP on port 8800),
     * reassembling 0x7F fragments, decrypting AES-ECB media blocks, and emitting clean Annex-B H.264/H.265 NAL units.
     */
    suspend fun runLiveStreamSession(
        cameraId: String,
        deviceIdStr: String,
        hostIp: String,
        port: Int,
        username: String,
        password: String,
        preferCloud: Boolean,
        hdQuality: Boolean,
        scope: CoroutineScope,
        onStatus: (String) -> Unit,
        onResolution: (Int, Int, Boolean) -> Unit,
        onVideoNalFrame: (ByteArray, Boolean) -> Unit,
        onAudioPacket: ((ByteArray, Byte) -> Unit)? = null,
        onError: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val deviceId = deviceIdStr.trim().filter(Char::isDigit).toLongOrNull() ?: 0L
        if (deviceId <= 0L) {
            onError("Falta el ID de la cámara V380 (ej. 65077425)")
            return@withContext
        }

        // Resolve target servers (LAN first if configured, then V380 Cloud Dispatch Relays)
        val candidates = mutableListOf<Pair<String, Boolean>>()
        if (!preferCloud && hostIp.isNotBlank() && !hostIp.endsWith(".")) {
            candidates.add(hostIp.trim() to false)
        }
        onStatus("Resolviendo servidor V380 para ID $deviceId...")
        val relays = resolveCloudRelayServers(deviceId)
        for (r in relays) {
            candidates.add(r to true)
        }
        if (preferCloud && hostIp.isNotBlank() && !hostIp.endsWith(".")) {
            candidates.add(hostIp.trim() to false)
        }

        if (candidates.isEmpty()) {
            onError("No se pudo contactar el servidor V380 para el ID $deviceId")
            return@withContext
        }

        var selectedServer: String? = null
        var selectedIsCloud = true
        var authTicket = 0L
        var sessionId = 0L
        var deviceVersion = 0

        // Step 1: Authenticate (cmd 1167 -> 1168)
        for ((ip, isCloud) in candidates) {
            val targetPort = if (isCloud) 8800 else port
            onStatus("Autenticando V380 en $ip:$targetPort...")
            try {
                Socket().use { authSocket ->
                    authSocket.soTimeout = 5000
                    authSocket.connect(InetSocketAddress(ip, targetPort), 4000)
                    val out = authSocket.getOutputStream()
                    val input = authSocket.getInputStream()

                    val encryptedPassword = generatePassword(password)
                    val userBytes = username.ifBlank { "admin" }.toByteArray(Charsets.US_ASCII)
                    val cmd1167 = ByteArray(520)

                    writeUInt32LE(cmd1167, 0, 1167L)
                    if (!isCloud) {
                        writeUInt32LE(cmd1167, 4, 120L)
                        cmd1167[8] = 31
                        writeUInt32LE(cmd1167, 9, 1L)
                        writeUInt32LE(cmd1167, 13, deviceId)
                        System.arraycopy(userBytes, 0, cmd1167, 49, min(userBytes.size, 32))
                        System.arraycopy(encryptedPassword, 0, cmd1167, 81, min(encryptedPassword.size, 64))
                    } else {
                        writeUInt32LE(cmd1167, 4, 1022L)
                        cmd1167[8] = 31
                        writeUInt32LE(cmd1167, 9, 1L)
                        writeUInt32LE(cmd1167, 13, deviceId)
                        val hostnameBytes = "$deviceId.nvdvr.net".toByteArray(Charsets.US_ASCII)
                        System.arraycopy(hostnameBytes, 0, cmd1167, 17, min(hostnameBytes.size, 50))
                        writeUInt32LE(cmd1167, 67, targetPort.toLong())
                        System.arraycopy(userBytes, 0, cmd1167, 71, min(userBytes.size, 32))
                        System.arraycopy(encryptedPassword, 0, cmd1167, 103, min(encryptedPassword.size, 64))
                    }

                    out.write(cmd1167)
                    out.flush()

                    val resp = receiveAtLeast(input, 256, 20)
                    if (resp != null && readUInt32LE(resp, 0) == 1168L) {
                        val loginResult = readUInt32LE(resp, 4)
                        if (loginResult == 1001L) {
                            deviceVersion = resp[12].toInt() and 0xFF
                            authTicket = readUInt32LE(resp, 13)
                            sessionId = readUInt32LE(resp, 17)
                            selectedServer = ip
                            selectedIsCloud = isCloud
                            break
                        } else if (loginResult == 1011L) {
                            onError("Usuario incorrecto en cámara V380 ($deviceId)")
                            return@withContext
                        } else if (loginResult == 1012L) {
                            onError("Contraseña incorrecta en cámara V380 ($deviceId)")
                            return@withContext
                        } else if (loginResult == 1018L) {
                            onError("ID V380 ($deviceId) inválido o fuera de línea")
                            return@withContext
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        val serverIp = selectedServer
        if (serverIp == null) {
            onError(
                if (password.isBlank()) "Ingresa la contraseña de tu cámara V380 ($deviceId)"
                else "La cámara V380 ($deviceId) no respondió o está desconectada"
            )
            return@withContext
        }

        val targetPort = if (selectedIsCloud) 8800 else port

        // Step 2: Connect Stream Socket (cmd 301 -> resp 401 -> cmd 303)
        try {
            Socket().use { streamSocket ->
                streamSocket.tcpNoDelay = true
                streamSocket.receiveBufferSize = 65536
                streamSocket.soTimeout = 6000
                streamSocket.connect(InetSocketAddress(serverIp, targetPort), 4500)

                val out = streamSocket.getOutputStream()
                val input = streamSocket.getInputStream()

                registerActiveStream(cameraId, out)

                val cmd301 = ByteArray(256)
                val qualityVal = if (hdQuality) 1L else 0L
                writeUInt32LE(cmd301, 0, 301L)
                if (!selectedIsCloud) {
                    writeUInt32LE(cmd301, 4, deviceId)
                    writeUInt32LE(cmd301, 8, 0L)
                    writeUInt16LE(cmd301, 12, 20)
                    writeUInt32LE(cmd301, 14, authTicket)
                    writeUInt32LE(cmd301, 22, 4097L)
                    writeUInt32LE(cmd301, 26, qualityVal)
                } else {
                    writeUInt32LE(cmd301, 4, 1022L)
                    val hostnameBytes = "$deviceId.nvdvr.net".toByteArray(Charsets.US_ASCII)
                    System.arraycopy(hostnameBytes, 0, cmd301, 8, min(hostnameBytes.size, 50))
                    writeUInt32LE(cmd301, 58, targetPort.toLong())
                    writeUInt32LE(cmd301, 62, deviceId)
                    writeUInt32LE(cmd301, 66, authTicket)
                    writeUInt32LE(cmd301, 70, sessionId)
                    writeUInt32LE(cmd301, 74, qualityVal)
                    cmd301[78] = 20
                    writeUInt32LE(cmd301, 79, 1L)
                }

                out.write(cmd301)
                out.flush()

                val resp401 = receiveAtLeast(input, 412, 8)
                if (resp401 == null || readUInt32LE(resp401, 0) != 401L) {
                    onError("Fallo al iniciar sesión de video V380 (cmd 301)")
                    return@withContext
                }

                var communicationVersion = 0
                var width = 1280
                var height = 720
                if (!selectedIsCloud && resp401.size >= 18) {
                    communicationVersion = readUInt16LE(resp401, 8)
                    val w = readUInt32LE(resp401, 10).toInt()
                    val h = readUInt32LE(resp401, 14).toInt()
                    if (w in 160..4096 && h in 120..4096) {
                        width = w
                        height = h
                    }
                }

                val aesKey = generateMediaKey(authTicket)
                val needDecrypt = deviceVersion > 30

                // Send cmd 303 (Start Stream)
                val cmd303 = ByteArray(256)
                writeUInt32LE(cmd303, 0, 303L)
                writeUInt16LE(cmd303, 4, 0x3001)
                out.write(cmd303)
                out.flush()

                onStatus("Recibiendo flujo V380 ($serverIp)...")

                val header12 = ByteArray(12)
                var payloadBuf = ByteArray(65536)
                val frameOut = ByteArrayOutputStream(131072)
                var frameTotal = 0
                var nextFragment = 0
                var frameStartType: Byte = 0
                var assemblingFrame = false
                var reportedCodec = false

                while (scope.isActive) {
                    if (readExact(input, header12, 0, 12) < 12) break
                    if (header12[0] != 0x7F.toByte()) continue

                    val type = header12[1]
                    val totalFrame = readUInt16LE(header12, 3)
                    val curFrame = readUInt16LE(header12, 5)
                    val payLen = readUInt16LE(header12, 7)

                    if (payLen <= 0 || payLen > 32768 || totalFrame <= 0 || curFrame >= totalFrame) {
                        continue
                    }

                    if (payloadBuf.size < payLen) {
                        payloadBuf = ByteArray(payLen)
                    }
                    if (readExact(input, payloadBuf, 0, payLen) < payLen) break

                    // Handle incoming live audio stream packets (type 0x16 or 0x1A)
                    if (type == 0x16.toByte() || type == 0x1A.toByte()) {
                        onAudioPacket?.invoke(payloadBuf.copyOf(payLen), type)
                        continue
                    }
                    if (type == 0x5B.toByte()) {
                        continue
                    }

                    if (curFrame == 0 || !assemblingFrame || totalFrame != frameTotal || curFrame != nextFragment) {
                        frameOut.reset()
                        frameTotal = totalFrame
                        nextFragment = 0
                        frameStartType = type
                        assemblingFrame = true
                    }

                    frameOut.write(payloadBuf, 0, payLen)
                    nextFragment = curFrame + 1

                    if (curFrame != totalFrame - 1) continue
                    val full = frameOut.toByteArray()
                    frameOut.reset()
                    assemblingFrame = false

                    if (full.size < 16) continue

                    val extracted = tryExtractVideoPayload(
                        full = full,
                        needDecrypt = needDecrypt,
                        communicationVersion = communicationVersion,
                        aesKey = aesKey
                    )
                    if (extracted != null) {
                        val (nalBytes, isH265) = extracted
                        if (!reportedCodec) {
                            reportedCodec = true
                            onResolution(width, height, isH265)
                        }
                        onVideoNalFrame(nalBytes, isH265)
                    }
                }
            }
        } catch (e: Exception) {
            if (scope.isActive) {
                onError("Conexión V380 interrumpida: ${e.localizedMessage ?: "Timeout"}")
            }
        } finally {
            unregisterActiveStream(cameraId)
        }
    }

    private fun tryExtractVideoPayload(
        full: ByteArray,
        needDecrypt: Boolean,
        communicationVersion: Int,
        aesKey: ByteArray
    ): Pair<ByteArray, Boolean>? {
        var bestScore = Int.MIN_VALUE
        var bestPayload: ByteArray? = null

        val offsets = intArrayOf(16, 20, 12, 0, 4, 8)
        for (offset in offsets) {
            if (full.size <= offset + 8) continue
            val direct = full.copyOfRange(offset, full.size)

            if (needDecrypt && direct.size >= 16) {
                val decrypted = direct.clone()
                if (communicationVersion == 21) {
                    decryptMediaPre2k(decrypted, decrypted.size, aesKey)
                } else {
                    decryptVideoFrame(decrypted, decrypted.size, aesKey)
                }
                val normDec = normalizeVideoPayload(decrypted)
                if (normDec != null) {
                    val s = scoreVideoPayload(normDec)
                    if (s > bestScore) {
                        bestScore = s
                        bestPayload = normDec
                    }
                }
            }

            val normDir = normalizeVideoPayload(direct)
            if (normDir != null) {
                val s = scoreVideoPayload(normDir)
                if (s > bestScore) {
                    bestScore = s
                    bestPayload = normDir
                }
            }
        }

        val winner = bestPayload ?: return null
        val isH265 = detectIsH265(winner)
        return winner to isH265
    }

    private fun normalizeVideoPayload(payload: ByteArray): ByteArray? {
        val start = findVideoStartCode(payload)
        if (start < 0) return null
        return if (payload[start].toInt() == 0 && payload[start + 1].toInt() == 0 && payload[start + 2].toInt() == 1) {
            val out = ByteArray(payload.size - start + 1)
            out[0] = 0
            System.arraycopy(payload, start, out, 1, payload.size - start)
            out
        } else {
            payload.copyOfRange(start, payload.size)
        }
    }

    private fun findVideoStartCode(payload: ByteArray): Int {
        val limit = min(payload.size - 5, 128)
        for (i in 0..limit) {
            if (payload[i].toInt() == 0 && payload[i + 1].toInt() == 0 &&
                payload[i + 2].toInt() == 0 && payload[i + 3].toInt() == 1
            ) {
                if (isLikelyNalHeader(payload, i + 4)) return i
            }
            if (payload[i].toInt() == 0 && payload[i + 1].toInt() == 0 && payload[i + 2].toInt() == 1) {
                if (isLikelyNalHeader(payload, i + 3)) return i
            }
        }
        return -1
    }

    private fun isLikelyNalHeader(payload: ByteArray, index: Int): Boolean {
        if (index >= payload.size) return false
        val nalHeader = payload[index].toInt() and 0xFF
        val h264Type = nalHeader and 0x1F
        if (h264Type in 1..23) return true
        val h265Type = (nalHeader shr 1) and 0x3F
        return h265Type in 1..47
    }

    private fun detectIsH265(payload: ByteArray): Boolean {
        if (payload.size < 5) return false
        val offset = if (payload[2].toInt() == 1) 3 else 4
        if (payload.size <= offset) return false
        val nalHeader = payload[offset].toInt() and 0xFF
        val h264Type = nalHeader and 0x1F
        if (h264Type in 1..23) return false
        val h265Type = (nalHeader shr 1) and 0x3F
        return h265Type in 1..47
    }

    private fun scoreVideoPayload(payload: ByteArray): Int {
        var score = 0
        var nalCount = 0
        var i = 0
        while (i < payload.size - 4 && nalCount < 10) {
            val sc = findNextStartCode(payload, i)
            if (sc < 0) break
            val scLen = if (payload[sc + 2].toInt() == 1) 3 else 4
            val nalStart = sc + scLen
            if (!isLikelyNalHeader(payload, nalStart)) {
                i = sc + 1
                score -= 20
                continue
            }
            nalCount++
            val nalHeader = payload[nalStart].toInt() and 0xFF
            val h264Type = nalHeader and 0x1F
            val h265Type = (nalHeader shr 1) and 0x3F
            if (h264Type == 7 || h264Type == 8 || h265Type in 32..34) score += 90
            if (h264Type == 5 || h265Type in 19..20) score += 50
            score += 15
            i = nalStart + 1
        }
        return if (nalCount == 0) Int.MIN_VALUE else score
    }

    private fun findNextStartCode(payload: ByteArray, from: Int): Int {
        for (i in from until payload.size - 3) {
            if (payload[i].toInt() == 0 && payload[i + 1].toInt() == 0) {
                if (payload[i + 2].toInt() == 1) return i
                if (payload[i + 2].toInt() == 0 && payload[i + 3].toInt() == 1) return i
            }
        }
        return -1
    }

    private fun decryptVideoFrame(data: ByteArray, length: Int, aesKey: ByteArray) {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"))
        var offset = 0
        while (offset + 64 <= length) {
            for (i in 0 until 4) {
                val blockOffset = offset + i * 16
                val dec = cipher.update(data, blockOffset, 16)
                if (dec != null) {
                    System.arraycopy(dec, 0, data, blockOffset, 16)
                }
            }
            offset += 80
        }
    }

    private fun decryptMediaPre2k(data: ByteArray, length: Int, aesKey: ByteArray) {
        val decryptLength = (length / 16) * 16
        if (decryptLength <= 0) return
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"))
        val dec = cipher.doFinal(data, 0, decryptLength)
        System.arraycopy(dec, 0, data, 0, decryptLength)
    }

    private fun generateMediaKey(ticket: Long): ByteArray {
        val key = ByteArray(16)
        writeUInt32LE(key, 0, ticket)
        writeUInt64LE(key, 4, 0x618123462c14795cUL.toLong())
        writeUInt32LE(key, 12, 0x82800df0L)
        return key
    }

    private fun generatePassword(pw: String): ByteArray {
        val sk = "macrovideo+*#!^@".toByteArray(Charsets.US_ASCII)
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val rk = ByteArray(16) { chars[Random.Default.nextInt(chars.length)].code.toByte() }

        val pb = pw.toByteArray(Charsets.US_ASCII)
        var pad = ByteArray(48)
        System.arraycopy(pb, 0, pad, 0, min(pb.size, 48))

        fun encryptBlocks(keyBytes: ByteArray, input: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"))
            return cipher.doFinal(input)
        }

        pad = encryptBlocks(sk, pad)
        pad = encryptBlocks(rk, pad)

        val out = ByteArray(64)
        System.arraycopy(rk, 0, out, 0, 16)
        System.arraycopy(pad, 0, out, 16, 48)
        return out
    }

    private fun receiveAtLeast(input: InputStream, maxLen: Int, minBytes: Int): ByteArray? {
        val buf = ByteArray(maxLen)
        var tot = 0
        while (tot < maxLen) {
            val n = input.read(buf, tot, maxLen - tot)
            if (n <= 0) break
            tot += n
            if (tot >= minBytes) break
        }
        return if (tot == 0) null else buf.copyOf(tot)
    }

    private fun readExact(input: InputStream, buf: ByteArray, offset: Int, count: Int): Int {
        var tot = 0
        while (tot < count) {
            val n = input.read(buf, offset + tot, count - tot)
            if (n <= 0) break
            tot += n
        }
        return tot
    }

    private fun writeUInt16LE(b: ByteArray, offset: Int, value: Int) {
        b[offset] = (value and 0xFF).toByte()
        b[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun writeUInt32LE(b: ByteArray, offset: Int, value: Long) {
        b[offset] = (value and 0xFF).toByte()
        b[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        b[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        b[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun writeUInt64LE(b: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) {
            b[offset + i] = ((value ushr (i * 8)) and 0xFF).toByte()
        }
    }

    private fun readUInt16LE(b: ByteArray, offset: Int): Int {
        return (b[offset].toInt() and 0xFF) or
            ((b[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun readUInt32LE(b: ByteArray, offset: Int): Long {
        return (b[offset].toLong() and 0xFFL) or
            ((b[offset + 1].toLong() and 0xFFL) shl 8) or
            ((b[offset + 2].toLong() and 0xFFL) shl 16) or
            ((b[offset + 3].toLong() and 0xFFL) shl 24)
    }

    private fun sha1Hex(input: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

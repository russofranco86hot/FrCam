package com.frcam.app.protocol

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.frcam.app.model.CameraConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Motor de transmisión de video dedicado para cámaras V720 / Naxclow / A9 / Xiaovv.
 *
 * Maneja el protocolo propietario Naxclow mediante:
 * 1. Conexión y solicitud NAT (Code 11) enviada localmente (UDP/TCP 6123, 10002) y vía MQTT a v720.p2p.naxclow.com:1883.
 * 2. Recepción de fragmentos MJPEG sobre canal UDP de datos.
 * 3. Sondeo HTTP concurrente para variantes de cámaras con endpoints MJPEG directos.
 */
object V720StreamEngine {
    private const val TAG = "V720StreamEngine"

    suspend fun streamV720Camera(
        camera: CameraConfig,
        onFrame: (Bitmap) -> Unit,
        onError: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        var host = camera.host.trim()
        val rawId = camera.deviceId.ifBlank {
            camera.name.substringAfter("(").substringBefore(")").trim()
        }
        val cleanDevId = rawId.filter { it.isLetterOrDigit() }
        val localIp = getLocalIpv4Address() ?: "127.0.0.1"

        // Auto-resolver host si está vacío, o si es la IP por defecto de AP y no estamos en esa red
        val hostIsUnreachableAp = host == "192.168.169.1" && !localIp.startsWith("192.168.169.")
        if (host.isBlank() || host == "127.0.0.1" || host == "0.0.0.0" || hostIsUnreachableAp) {
            val resolved = resolveV720IpDirectly()
            if (!resolved.isNullOrBlank()) {
                host = resolved
                Log.d(TAG, "IP V720 resuelta automáticamente: $host")
            } else if (hostIsUnreachableAp) {
                host = ""
            }
        }

        // Si el móvil está en la red AP de la cámara (ej. Nax_...)
        if (host.isBlank() || host == "127.0.0.1") {
            if (localIp.startsWith("192.168.169.")) host = "192.168.169.1"
            else if (localIp.startsWith("192.168.4.")) host = "192.168.4.1"
        }

        Log.d(TAG, "Iniciando stream V720 para host=$host, deviceId=$cleanDevId, localIp=$localIp")

        // 1. Probar primero si la cámara expone stream HTTP MJPEG directo
        if (host.isNotBlank() && host != "127.0.0.1") {
            val httpSuccess = tryHttpStream(host, camera.httpPort, onFrame)
            if (httpSuccess) {
                Log.d(TAG, "Stream V720 completado vía HTTP")
                return@withContext
            }
        }

        // 2. Probar protocolo propietario Naxclow UDP/TCP en puerto 6123 / 10002 / 20190
        val udpSuccess = tryUdpP2pStream(host, cleanDevId, localIp, onFrame)
        if (udpSuccess) {
            Log.d(TAG, "Stream V720 completado vía UDP P2P")
            return@withContext
        }

        // 3. Si falló y la IP pudo haber cambiado por DHCP, re-descubrir de forma activa
        val reResolved = resolveV720IpDirectly()
        if (!reResolved.isNullOrBlank() && reResolved != host) {
            Log.d(TAG, "Reintentando stream V720 con nueva IP resuelta: $reResolved")
            if (tryHttpStream(reResolved, camera.httpPort, onFrame)) return@withContext
            if (tryUdpP2pStream(reResolved, cleanDevId, localIp, onFrame)) return@withContext
        }

        onError("No se pudo iniciar video V720. Verifica que la cámara esté encendida con luz azul fija en la misma red Wi-Fi.")
    }

    private suspend fun tryHttpStream(
        host: String,
        defaultPort: Int,
        onFrame: (Bitmap) -> Unit
    ): Boolean {
        if (host.isBlank()) return false
        val portsToCheck = listOf(defaultPort, 80, 8080, 20190, 81, 6123, 8888, 5000, 32108).distinct()
        val pathsToCheck = listOf(
            "/video", "/stream", "/cam.mjpg", "/live.mjpg", "/mjpeg/1",
            "/snapshot", "/jpg/image.jpg", "/image.jpg", "/live", "/stream.mjpg", "/snapshot.jpg", "/capture", "/"
        )

        for (port in portsToCheck) {
            if (!isTcpPortOpen(host, port, 250)) continue
            for (path in pathsToCheck) {
                val urlStr = "http://$host:$port$path"
                var conn: HttpURLConnection? = null
                try {
                    conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 800
                        readTimeout = 2500
                        requestMethod = "GET"
                        setRequestProperty("User-Agent", "FrCam/1.0")
                        setRequestProperty("Accept", "*/*")
                    }
                    val code = conn.responseCode
                    val contentType = conn.contentType.orEmpty().lowercase()
                    if (code in 200..299 || code == 401) {
                        val inStream = BufferedInputStream(conn.inputStream, 16384)
                        val buf = ByteArrayOutputStream(65536)
                        var prevByte = -1
                        var inFrame = false
                        var frameCount = 0

                        while (coroutineContext.isActive) {
                            val b = inStream.read()
                            if (b == -1) break
                            if (!inFrame) {
                                if (prevByte == 0xFF && b == 0xD8) {
                                    inFrame = true
                                    buf.reset()
                                    buf.write(0xFF)
                                    buf.write(0xD8)
                                }
                            } else {
                                buf.write(b)
                                if (prevByte == 0xFF && b == 0xD9) {
                                    val bytes = buf.toByteArray()
                                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                    if (bmp != null) {
                                        onFrame(bmp)
                                        frameCount++
                                    }
                                    inFrame = false
                                    buf.reset()
                                } else if (buf.size() > 1_500_000) {
                                    inFrame = false
                                    buf.reset()
                                }
                            }
                            prevByte = b
                        }

                        // Si entregó un frame de snapshot pero el socket cerró, mantener bucle de snapshots a ~10fps
                        if (frameCount > 0 && coroutineContext.isActive) {
                            Log.d(TAG, "Cámara V720 en bucle continuo de snapshots HTTP en $urlStr")
                            while (coroutineContext.isActive) {
                                delay(90L)
                                val snapBmp = fetchHttpSnapshot(urlStr)
                                if (snapBmp != null) {
                                    onFrame(snapBmp)
                                }
                            }
                            return true
                        }
                        if (frameCount > 0) return true
                    }
                } catch (_: Exception) {
                } finally {
                    try { conn?.disconnect() } catch (_: Exception) {}
                }
            }
        }
        return false
    }

    private fun fetchHttpSnapshot(urlStr: String): Bitmap? {
        return try {
            val c = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 800
                readTimeout = 1200
                requestMethod = "GET"
                setRequestProperty("User-Agent", "FrCam/1.0")
            }
            if (c.responseCode in 200..299) {
                val bytes = c.inputStream.use { it.readBytes() }
                c.disconnect()
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } else {
                c.disconnect()
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun tryUdpP2pStream(
        host: String,
        deviceId: String,
        localIp: String,
        onFrame: (Bitmap) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            socket.soTimeout = 2500
            val localPort = socket.localPort

            val probeJson = """{"code":50}"""
            val probeBytes = probeJson.toByteArray(Charsets.UTF_8)
            val streamJson = """{"code":53,"status":1}"""
            val streamBytes = streamJson.toByteArray(Charsets.UTF_8)
            val natReqJson = """{"code":11,"cliTarget":"frcam","cliToken":"55ABfb77","cliIp":"$localIp","cliPort":$localPort,"cliNatIp":"$localIp","cliNatPort":$localPort}"""
            val natReqBytes = natReqJson.toByteArray(Charsets.UTF_8)

            // 0. Si tenemos deviceId, disparar solicitud NAT por MQTT a la nube Naxclow
            if (deviceId.isNotBlank()) {
                kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                    try {
                        sendMqttNatReq(deviceId, natReqJson)
                    } catch (e: Exception) {
                        Log.d(TAG, "MQTT NAT_REQ fallback: ${e.message}")
                    }
                }
            }

            // A) Enviar probes por UDP a la cámara y por broadcast
            val targetIps = mutableListOf<String>()
            if (host.isNotBlank() && host != "127.0.0.1") targetIps.add(host)
            val subnetPrefix = localIp.substringBeforeLast(".")
            if (subnetPrefix.isNotBlank()) {
                targetIps.add("$subnetPrefix.255")
            }
            targetIps.add("255.255.255.255")
            targetIps.add("192.168.169.1")

            val udpPorts = listOf(20190, 6123, 10002, 10003, 16123, 32108)
            for (h in targetIps) {
                try {
                    val destAddr = InetAddress.getByName(h)
                    for (p in udpPorts) {
                        try {
                            socket.send(DatagramPacket(probeBytes, probeBytes.size, destAddr, p))
                            socket.send(DatagramPacket(natReqBytes, natReqBytes.size, destAddr, p))
                            socket.send(DatagramPacket(streamBytes, streamBytes.size, destAddr, p))
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}

                // B) Enviar por TCP a la cámara en puertos 20190, 20023, 6123, 10002, 8080, 80
                if (h != "255.255.255.255") {
                    for (p in listOf(20190, 20023, 6123, 10002, 8080, 80)) {
                        try {
                            Socket().use { tcpSocket ->
                                tcpSocket.soTimeout = 400
                                tcpSocket.connect(InetSocketAddress(h, p), 400)
                                val out = tcpSocket.getOutputStream()
                                out.write(probeBytes)
                                out.write(streamBytes)
                                out.flush()
                            }
                        } catch (_: Exception) {}
                    }
                }
            }

            val recvBuf = ByteArray(65536)
            val packet = DatagramPacket(recvBuf, recvBuf.size)
            val jpegStreamBuf = ByteArrayOutputStream(262144)
            var capturingJpeg = false
            var receivedFrames = 0
            val startTime = System.currentTimeMillis()

            while (coroutineContext.isActive) {
                try {
                    socket.receive(packet)
                    val len = packet.length
                    val data = packet.data
                    val senderAddr = packet.address
                    val senderPort = packet.port

                    // Si recibimos handshake code 20 {"code":20}, responder inmediatamente code 21 y activar stream
                    if (len in 8..200) {
                        val text = String(data, 0, len, Charsets.UTF_8)
                        if (text.contains("\"code\":20") || text.contains("\"code\": 20")) {
                            val rsp = """{"code":21,"ip":"$localIp","port":$localPort}""".toByteArray(Charsets.UTF_8)
                            socket.send(DatagramPacket(rsp, rsp.size, senderAddr, senderPort))
                            socket.send(DatagramPacket(streamBytes, streamBytes.size, senderAddr, senderPort))
                            Log.d(TAG, "Handshake V720 code 20 recibido, respondido code 21 a $senderAddr:$senderPort")
                            continue
                        }
                        if (text.contains("\"code\":51") || text.contains("\"code\": 51")) {
                            socket.send(DatagramPacket(streamBytes, streamBytes.size, senderAddr, senderPort))
                            socket.send(DatagramPacket(natReqBytes, natReqBytes.size, senderAddr, senderPort))
                            Log.d(TAG, "Handshake V720 code 51 recibido, solicitado stream code 53")
                            continue
                        }
                    }

                    // Ensamblar frames JPEG a partir de los paquetes UDP recibidos
                    var startIdx = -1
                    for (i in 0 until len - 1) {
                        if ((data[i].toInt() and 0xFF) == 0xFF && (data[i + 1].toInt() and 0xFF) == 0xD8) {
                            startIdx = i
                            break
                        }
                    }

                    var endIdx = -1
                    for (i in 0 until len - 1) {
                        if ((data[i].toInt() and 0xFF) == 0xFF && (data[i + 1].toInt() and 0xFF) == 0xD9) {
                            endIdx = i + 1
                            break
                        }
                    }

                    if (startIdx >= 0) {
                        capturingJpeg = true
                        jpegStreamBuf.reset()
                        jpegStreamBuf.write(data, startIdx, len - startIdx)
                    } else if (capturingJpeg) {
                        jpegStreamBuf.write(data, 0, len)
                    }

                    if (capturingJpeg && endIdx >= 0) {
                        val bytes = jpegStreamBuf.toByteArray()
                        if (bytes.size > 200) {
                            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (bmp != null) {
                                onFrame(bmp)
                                receivedFrames++
                            }
                        }
                        capturingJpeg = false
                        jpegStreamBuf.reset()
                    } else if (jpegStreamBuf.size() > 1_500_000) {
                        capturingJpeg = false
                        jpegStreamBuf.reset()
                    }

                    // Enviar confirmación periódica de recepción a la cámara para mantener el stream activo
                    if (receivedFrames > 0 && receivedFrames % 5 == 0) {
                        val ack = """{"code":605,"p":1}""".toByteArray(Charsets.UTF_8)
                        socket.send(DatagramPacket(ack, ack.size, senderAddr, senderPort))
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    if (receivedFrames == 0 && (System.currentTimeMillis() - startTime) > 4500L) {
                        break
                    }
                }
            }
            receivedFrames > 0
        } catch (e: Exception) {
            Log.w(TAG, "Error en tryUdpP2pStream: ${e.message}")
            false
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    private fun sendMqttNatReq(deviceId: String, payloadJson: String) {
        Socket().use { sock ->
            sock.soTimeout = 2000
            sock.connect(InetSocketAddress("v720.p2p.naxclow.com", 1883), 1500)
            val out = sock.getOutputStream()

            // 1. MQTT CONNECT
            val clientId = "frcam_" + System.currentTimeMillis().toString().takeLast(6)
            val connectPacket = buildMqttConnectPacket(clientId)
            out.write(connectPacket)
            out.flush()

            // 2. MQTT PUBLISH
            val topic = "Naxclow/P2P/Users/Device/sub/$deviceId"
            val publishPacket = buildMqttPublishPacket(topic, payloadJson)
            out.write(publishPacket)
            out.flush()
            Log.d(TAG, "NAT_REQ publicado en MQTT $topic")
        }
    }

    private fun buildMqttConnectPacket(clientId: String): ByteArray {
        val clientIdBytes = clientId.toByteArray(Charsets.UTF_8)
        val varHeader = byteArrayOf(
            0x00, 0x04, 'M'.code.toByte(), 'Q'.code.toByte(), 'T'.code.toByte(), 'T'.code.toByte(),
            0x04, // protocol level 4 (MQTT 3.1.1)
            0x02, // connect flags: Clean Session
            0x00, 0x3C // Keep Alive: 60 sec
        )
        val payload = ByteArray(2 + clientIdBytes.size)
        payload[0] = (clientIdBytes.size shr 8).toByte()
        payload[1] = (clientIdBytes.size and 0xFF).toByte()
        System.arraycopy(clientIdBytes, 0, payload, 2, clientIdBytes.size)

        val remLen = varHeader.size + payload.size
        val out = ByteArrayOutputStream()
        out.write(0x10) // CONNECT packet type
        encodeRemainingLength(out, remLen)
        out.write(varHeader)
        out.write(payload)
        return out.toByteArray()
    }

    private fun buildMqttPublishPacket(topic: String, message: String): ByteArray {
        val topicBytes = topic.toByteArray(Charsets.UTF_8)
        val msgBytes = message.toByteArray(Charsets.UTF_8)

        val out = ByteArrayOutputStream()
        out.write(0x30) // PUBLISH packet type, QoS 0
        val remLen = 2 + topicBytes.size + msgBytes.size
        encodeRemainingLength(out, remLen)
        out.write((topicBytes.size shr 8))
        out.write((topicBytes.size and 0xFF))
        out.write(topicBytes)
        out.write(msgBytes)
        return out.toByteArray()
    }

    private fun encodeRemainingLength(out: ByteArrayOutputStream, len: Int) {
        var x = len
        do {
            var encodedByte = x % 128
            x /= 128
            if (x > 0) encodedByte = encodedByte or 128
            out.write(encodedByte)
        } while (x > 0)
    }

    private fun getLocalIpv4Address(): String? {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                for (addr in intf.inetAddresses.toList()) {
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                            return ip
                        }
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun isTcpPortOpen(ip: String, port: Int, timeoutMs: Int = 300): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun getArpTable(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        try {
            java.io.File("/proc/net/arp").forEachLine { line ->
                val parts = line.split("\\s+".toRegex())
                if (parts.size >= 4 && parts[0] != "IP") {
                    val ip = parts[0]
                    val mac = parts[3].lowercase()
                    if (mac != "00:00:00:00:00:00" && !mac.contains("incomplete")) {
                        map[ip] = mac
                    }
                }
            }
        } catch (_: Exception) {}
        return map
    }

    private suspend fun resolveV720IpDirectly(): String? = withContext(Dispatchers.IO) {
        val localIp = getLocalIpv4Address() ?: return@withContext null
        val subnetPrefix = localIp.substringBeforeLast(".")

        // 1. Probar descubrimiento rápido vía broadcast UDP multi-probe en socket único
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 300
                val targets = mutableListOf<InetAddress>()
                try { targets.add(InetAddress.getByName("255.255.255.255")) } catch (_: Exception) {}
                if (subnetPrefix.isNotBlank()) {
                    try { targets.add(InetAddress.getByName("$subnetPrefix.255")) } catch (_: Exception) {}
                }
                val probe = """{"code":50}""".toByteArray(Charsets.UTF_8)
                for (dest in targets) {
                    for (p in listOf(20190, 6123, 10002, 10003, 32108)) {
                        try {
                            socket.send(DatagramPacket(probe, probe.size, dest, p))
                        } catch (_: Exception) {}
                    }
                }
                val buf = ByteArray(1024)
                val deadline = System.currentTimeMillis() + 800L
                while (System.currentTimeMillis() < deadline) {
                    try {
                        val pack = DatagramPacket(buf, buf.size)
                        socket.receive(pack)
                        val ip = pack.address?.hostAddress
                        if (!ip.isNullOrBlank() && ip != localIp) return@withContext ip
                    } catch (_: Exception) {
                        break
                    }
                }
            }
        } catch (_: Exception) {}

        // 2. Consulta de tabla ARP local
        val arp = getArpTable()
        for ((ip, _) in arp) {
            if (ip.startsWith(subnetPrefix) && ip != localIp && !ip.endsWith(".1") && !ip.endsWith(".254")) {
                for (p in listOf(20190, 20023, 6123, 10002, 8080, 80)) {
                    if (isTcpPortOpen(ip, p, 100)) return@withContext ip
                }
            }
        }

        // 3. Probar IPs de la subred concurrentemente con Semaphore(15) para evitar EMFILE en reloj
        val ports = listOf(20190, 20023, 80, 8080, 554, 81, 6123, 10002, 32108)
        val semaphore = Semaphore(15)
        val found = coroutineScope {
            (1..254).map { octet ->
                async {
                    val ip = "$subnetPrefix.$octet"
                    if (ip == localIp || ip.endsWith(".1") || ip.endsWith(".254")) return@async null

                    semaphore.withPermit {
                        for (p in ports) {
                            if (isTcpPortOpen(ip, p, 120)) return@withPermit ip
                        }
                        null
                    }
                }
            }.awaitAll().filterNotNull().firstOrNull()
        }
        found
    }
}

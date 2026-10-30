package com.frcam.app.protocol

import com.frcam.app.model.CameraBrand
import com.frcam.app.model.DiscoveredCameraDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object NetworkCameraScanner {

    fun getLocalIpv4Address(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                for (addr in intf.inetAddresses.toList()) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
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

    /**
     * Checks if a target host/IP is on the same local IPv4 subnet as this Android device.
     * When false, the app automatically switches to Cloud Relay / UPnP WAN IP without requiring router setup.
     */
    fun isSameLocalSubnet(targetHostOrIp: String): Boolean {
        if (targetHostOrIp.isBlank()) return false
        val cleanHost = targetHostOrIp.trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .removePrefix("rtsp://")
            .substringBefore("/")
            .substringBefore(":")
        val localIp = getLocalIpv4Address() ?: return false
        val localPrefix = localIp.substringBeforeLast(".")
        val targetPrefix = cleanHost.substringBeforeLast(".")
        return localPrefix == targetPrefix
    }

    /**
     * Runs:
     * 1. V380 Native UDP Broadcast ("NVDEVSEARCH^100" on port 10008 -> extracts IP, MAC, and 8-digit V380 Device ID)
     * 2. ONVIF WS-Discovery UDP Multicast (239.255.255.250:3702)
     * 3. Fast Concurrent Subnet Port Fingerprinting (Eye4 10554/10080, V380 8800/554, V720 6123/80, Tapo 2020, ONVIF)
     */
    suspend fun scanLocalNetwork(
        onProgress: (Float, String) -> Unit,
        onDeviceFound: (DiscoveredCameraDevice) -> Unit
    ): List<DiscoveredCameraDevice> = withContext(Dispatchers.IO) {
        val localIp = getLocalIpv4Address()
        val subnetPrefix = localIp?.substringBeforeLast(".") ?: "192.168.1"

        val discovered = ConcurrentHashMap<String, DiscoveredCameraDevice>()

        onProgress(0.04f, "Detectando router y gateway UPnP para acceso fuera de casa...")
        val gateway = try {
            UpnpIgdManager.discoverGateway()
        } catch (_: Exception) {
            null
        }
        val gatewayWanIp = gateway?.externalWanIp

        onProgress(0.08f, "Buscando cámaras V380 por broadcast UDP (puerto 10008)...")
        val v380BroadcastDevices = V380NativeProtocol.discoverLanV380Devices()
        for (v380 in v380BroadcastDevices) {
            val dev = DiscoveredCameraDevice(
                ipAddress = v380.ip,
                detectedBrand = CameraBrand.V380_PRO,
                deviceName = "V380 PRO (${v380.deviceId})",
                openPorts = listOf(8800),
                rtspPort = 8800,
                httpPort = 8800,
                onvifPort = 8899,
                deviceId = v380.deviceId,
                suggestedMainPath = "/live/ch00_1",
                suggestedSubPath = "/live/ch00_0",
                macOrHardwareInfo = "ID V380: ${v380.deviceId} • MAC: ${v380.mac}",
                remoteHost = gatewayWanIp.orEmpty()
            )
            discovered[v380.ip] = dev
            onDeviceFound(dev)
        }

        onProgress(0.12f, "Buscando cámaras Eye4 (VStarcam) y V720 por difusión...")
        val eye4Devices = discoverEye4ViaUdpBroadcast()
        for (eye4 in eye4Devices) {
            if (!discovered.containsKey(eye4.ipAddress)) {
                discovered[eye4.ipAddress] = eye4
                onDeviceFound(eye4)
            }
        }
        val v720Devices = discoverV720ViaUdpBroadcast()
        for (v720 in v720Devices) {
            if (!discovered.containsKey(v720.ipAddress)) {
                discovered[v720.ipAddress] = v720
                onDeviceFound(v720)
            }
        }

        // Consultar tabla ARP para detectar cámaras activas (V720, A9, Beken, Tuya, etc.)
        try {
            val arpTable = BluetoothCameraEngine.getArpTable()
            for ((arpIp, mac) in arpTable) {
                if (arpIp.startsWith(subnetPrefix) && arpIp != localIp && !discovered.containsKey(arpIp) && !arpIp.endsWith(".1") && !arpIp.endsWith(".254")) {
                    val open = probeCameraPorts(arpIp, timeoutMs = 300)
                    if (open.isNotEmpty()) {
                        val dev = classifyDevice(arpIp, open, null, gatewayWanIp)
                        discovered[arpIp] = dev
                        onDeviceFound(dev)
                    }
                }
            }
        } catch (_: Exception) {}

        onProgress(0.15f, "Enviando sonda UDP ONVIF WS-Discovery en $subnetPrefix.0/24...")
        val onvifMap = discoverOnvifViaUdpMulticast()

        for ((ip, serviceUrl) in onvifMap) {
            if (ip == localIp || discovered.containsKey(ip)) continue
            val openPorts = probeCameraPorts(ip, timeoutMs = 260)
            val device = classifyDevice(ip, openPorts, serviceUrl, gatewayWanIp)
            discovered[ip] = device
            onDeviceFound(device)
        }

        onProgress(0.19f, "Buscando dispositivos SSDP/UPnP (Hikvision, Dahua, Foscam, Axis, Reolink...)...")
        val ssdpDevices = discoverSsdpCameras()
        for ((ip, info) in ssdpDevices) {
            if (ip == localIp || discovered.containsKey(ip)) continue
            val ports = probeCameraPorts(ip, timeoutMs = 300)
            val dev = classifyDevice(ip, ports, onvifMap[ip], gatewayWanIp)
            val named = dev.copy(
                deviceName = info.friendlyName.ifBlank { dev.deviceName },
                macOrHardwareInfo = "SSDP: ${info.summary}"
            )
            discovered[ip] = named
            onDeviceFound(named)
        }
        onProgress(0.22f, "Escaneando puertos Eye4 (10554), V380 (8800), V720 (6123) y RTSP en $subnetPrefix.1-254...")

        val semaphore = Semaphore(48)
        var completedHosts = 0

        coroutineScope {
            val jobs = (1..254).map { hostOctet ->
                async {
                    val ip = "$subnetPrefix.$hostOctet"
                    if (ip == localIp || discovered.containsKey(ip)) {
                        completedHosts++
                        return@async
                    }
                    // Excluir la IP de administración web del Router / Gateway doméstico (ej. 192.168.1.1 o .254)
                    val isRouterGateway = ip.endsWith(".1") || ip.endsWith(".254") || ip == gatewayWanIp
                    semaphore.withPermit {
                        val openPorts = probeCameraPorts(ip, timeoutMs = 220)
                        if (openPorts.isNotEmpty()) {
                            // Si es el router, solo aceptarlo si tiene explícitamente puertos de video/cámara abiertos (554, 8554, 8800, 10554, 6123)
                            if (isRouterGateway && !openPorts.any { it in listOf(554, 8554, 8800, 10554, 6123, 16123) }) {
                                completedHosts++
                                return@withPermit
                            }

                            val isCameraCandidate = openPorts.any {
                                it in listOf(10554, 10080, 8800, 8899, 6123, 16123, 10002, 10003, 10000, 554, 8554, 2020, 8000, 81, 6600, 6001, 8001, 9988, 37777, 34567, 8081, 88, 7070, 8888, 8088, 5000)
                            } || openPorts.contains(8080) || openPorts.contains(80)

                            if (isCameraCandidate) {
                                val validDevice = if (openPorts == listOf(80) || openPorts == listOf(8080) || openPorts == listOf(80, 8080)) {
                                    isLikelyCameraHttp(ip, if (openPorts.contains(80)) 80 else 8080)
                                } else {
                                    true
                                }

                                if (validDevice) {
                                    val device = classifyDevice(ip, openPorts, onvifMap[ip], gatewayWanIp)
                                    discovered[ip] = device
                                    onDeviceFound(device)
                                }
                            }
                        }
                    }
                    completedHosts++
                    if (completedHosts % 15 == 0) {
                        val fraction = 0.22f + (completedHosts / 254f) * 0.76f
                        onProgress(
                            fraction.coerceAtMost(0.98f),
                            "Escaneando $subnetPrefix.$completedHosts (${discovered.size} encontradas)..."
                        )
                    }
                }
            }
            jobs.awaitAll()
        }

        onProgress(1.0f, "Escaneo completado: ${discovered.size} cámara(s) detectada(s)")
        discovered.values.sortedBy {
            it.ipAddress.substringAfterLast(".").toIntOrNull() ?: 999
        }
    }

    fun discoverEye4ViaUdpBroadcast(): List<DiscoveredCameraDevice> {
        val found = mutableListOf<DiscoveredCameraDevice>()
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 350
                val broadcastAddr = InetAddress.getByName("255.255.255.255")
                val searchBytes = "VSTC_SEARCH\n".toByteArray(Charsets.US_ASCII)
                val p8600 = DatagramPacket(searchBytes, searchBytes.size, broadcastAddr, 8600)
                val p32108 = DatagramPacket(searchBytes, searchBytes.size, broadcastAddr, 32108)
                repeat(2) {
                    socket.send(p8600)
                    socket.send(p32108)
                }
                val buf = ByteArray(2048)
                val deadline = System.currentTimeMillis() + 500L
                while (System.currentTimeMillis() < deadline) {
                    try {
                        val recv = DatagramPacket(buf, buf.size)
                        socket.receive(recv)
                        val ip = recv.address?.hostAddress ?: continue
                        val text = String(recv.data, 0, recv.length, Charsets.UTF_8)
                        if (found.none { it.ipAddress == ip }) {
                            val uid = text.substringAfter("uid=").substringBefore("&").substringBefore(";").trim().takeIf { it.isNotBlank() } ?: "VStarcam"
                            found.add(
                                DiscoveredCameraDevice(
                                    ipAddress = ip,
                                    detectedBrand = CameraBrand.EYE4_VSTARCAM,
                                    deviceName = "Cámara Eye4 / VStarcam ($ip)",
                                    openPorts = listOf(10554, 81),
                                    rtspPort = 10554,
                                    httpPort = 81,
                                    onvifPort = 10080,
                                    deviceId = uid,
                                    suggestedMainPath = "/tcp/av0_0",
                                    suggestedSubPath = "/tcp/av0_1",
                                    macOrHardwareInfo = "Eye4 UDP Discovery • UID: $uid"
                                )
                            )
                        }
                    } catch (_: Exception) {
                        break
                    }
                }
            }
        } catch (_: Exception) {}
        return found
    }

    fun discoverV720ViaUdpBroadcast(): List<DiscoveredCameraDevice> {
        val found = mutableListOf<DiscoveredCameraDevice>()
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 400
                val broadcastAddr = InetAddress.getByName("255.255.255.255")
                val probePayloads = listOf(
                    "{\"code\":50}".toByteArray(Charsets.UTF_8),
                    "{\"code\":10}".toByteArray(Charsets.UTF_8),
                    "{\"cmd\":\"search\"}\n".toByteArray(Charsets.UTF_8),
                    "NAX_DISCOVER\n".toByteArray(Charsets.US_ASCII),
                    "DISCOVER\n".toByteArray(Charsets.US_ASCII),
                    byteArrayOf(0x00, 0x00, 0x00, 0x00)
                )
                val targetPorts = listOf(20190, 6123, 10002, 10003, 16123, 6600, 32108)
                for (port in targetPorts) {
                    for (payload in probePayloads) {
                        try {
                            val packet = DatagramPacket(payload, payload.size, broadcastAddr, port)
                            socket.send(packet)
                        } catch (_: Exception) {}
                    }
                }
                val buf = ByteArray(2048)
                val deadline = System.currentTimeMillis() + 800L
                while (System.currentTimeMillis() < deadline) {
                    try {
                        val recv = DatagramPacket(buf, buf.size)
                        socket.receive(recv)
                        val ip = recv.address?.hostAddress ?: continue
                        if (found.none { it.ipAddress == ip }) {
                            val recvPort = recv.port
                            val text = String(buf, 0, recv.length, Charsets.UTF_8)
                            val devId = if (text.contains("\"devTarget\"")) {
                                text.substringAfter("\"devTarget\"").substringAfter(":").substringBefore(",").substringBefore("}").replace("\"", "").trim()
                            } else ""

                            found.add(
                                DiscoveredCameraDevice(
                                    ipAddress = ip,
                                    detectedBrand = CameraBrand.V720_NAXCLOW,
                                    deviceName = if (devId.isNotBlank()) "Cámara V720 ($devId)" else "Cámara V720 Naxclow ($ip)",
                                    deviceId = devId,
                                    openPorts = listOf(recvPort, 80),
                                    rtspPort = 554,
                                    httpPort = 80,
                                    onvifPort = recvPort,
                                    suggestedMainPath = "/video",
                                    suggestedSubPath = "/video",
                                    macOrHardwareInfo = "V720 UDP Broadcast (Puerto $recvPort)"
                                )
                            )
                        }
                    } catch (_: Exception) {
                        break
                    }
                }
            }
        } catch (_: Exception) {}
        return found
    }

    private fun probeCameraPorts(ip: String, timeoutMs: Int): List<Int> {
        val open = mutableListOf<Int>()
        val primaryPorts = listOf(
            20190, 20023, 10554, 8800, 6123, 10002, 10003, 16123, 554, 8899, 10080, 8554, 2020, 8000, 81, 8080, 80, 6600, 6001, 8888, 5000, 8088, 9988, 37777, 34567, 8081, 88, 7070, 23
        )
        for (port in primaryPorts) {
            if (isTcpPortOpen(ip, port, timeoutMs)) {
                open.add(port)
            }
        }
        return open
    }

    fun isTcpPortOpen(ip: String, port: Int, timeoutMs: Int = 350): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun isLikelyCameraHttp(ip: String, port: Int): Boolean {
        if (ip.endsWith(".1") || ip.endsWith(".254")) return false
        return try {
            Socket().use { socket ->
                socket.soTimeout = 400
                socket.connect(InetSocketAddress(ip, port), 350)
                val out = socket.getOutputStream()
                val req = "GET / HTTP/1.0\r\nHost: $ip\r\n\r\n"
                out.write(req.toByteArray())
                out.flush()
                val inStream = socket.getInputStream()
                val buffer = ByteArray(2048)
                val readCount = inStream.read(buffer)
                if (readCount <= 0) return false
                val resp = String(buffer, 0, readCount, Charsets.UTF_8).lowercase()
                resp.contains("ipcam") ||
                    resp.contains("onvif") ||
                    resp.contains("vstarcam") ||
                    resp.contains("eye4") ||
                    resp.contains("naxclow") ||
                    resp.contains("v720") ||
                    resp.contains("hikvision") ||
                    resp.contains("dahua") ||
                    resp.contains("mjpeg") ||
                    resp.contains("camera") ||
                    resp.contains("realm=\"ip") ||
                    resp.contains("realm=\"cam") ||
                    resp.contains("realm=\"v720") ||
                    resp.contains("video/x-motion-jpeg") ||
                    resp.contains("multipart/x-mixed-replace") ||
                    resp.contains("live.mjpg") ||
                    resp.contains("snapshot") ||
                    resp.contains("server: rt-thread") ||
                    resp.contains("beken") ||
                    resp.contains("webcam")
            }
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun classifyDevice(
        ip: String,
        openPorts: List<Int>,
        onvifServiceUrl: String?,
        gatewayWanIp: String? = null
    ): DiscoveredCameraDevice {
        val coarseDevice = when {
            openPorts.contains(10554) || openPorts.contains(10080) -> {
                DiscoveredCameraDevice(
                    ipAddress = ip,
                    detectedBrand = CameraBrand.EYE4_VSTARCAM,
                    deviceName = "Cámara Eye4 / VStarcam ($ip)",
                    openPorts = openPorts,
                    rtspPort = if (openPorts.contains(10554)) 10554 else 554,
                    httpPort = if (openPorts.contains(81)) 81 else 80,
                    onvifPort = if (openPorts.contains(10080)) 10080 else 80,
                    onvifServiceUrl = onvifServiceUrl,
                    suggestedMainPath = "/tcp/av0_0",
                    suggestedSubPath = "/tcp/av0_1",
                    macOrHardwareInfo = "Eye4 Native RTSP (${openPorts.joinToString("/")})"
                )
            }

            openPorts.contains(8800) -> {
                val unicast = V380NativeProtocol.queryV380DeviceIdUnicast(ip)
                val devId = unicast?.deviceId?.ifBlank { null } ?: V380NativeProtocol.queryV380DeviceIdOverTcp(ip, 8800)
                val mac = unicast?.mac?.ifBlank { null }
                val devName = if (devId.isNotBlank()) "V380 PRO ($devId)" else "Cámara V380 PRO ($ip)"
                val hwInfo = if (mac != null) "ID V380: $devId • MAC: $mac" else if (devId.isNotBlank()) "ID V380: $devId" else "V380 Nativo Puerto 8800"
                DiscoveredCameraDevice(
                    ipAddress = ip,
                    detectedBrand = CameraBrand.V380_PRO,
                    deviceName = devName,
                    openPorts = openPorts,
                    rtspPort = 8800,
                    httpPort = 8800,
                    onvifPort = if (openPorts.contains(8899)) 8899 else 80,
                    deviceId = devId,
                    onvifServiceUrl = onvifServiceUrl,
                    suggestedMainPath = "/live/ch00_1",
                    suggestedSubPath = "/live/ch00_0",
                    macOrHardwareInfo = hwInfo
                )
            }

            openPorts.any { it in listOf(20190, 20023, 6123, 10002, 10003, 16123, 6600, 6001, 10000) } -> {
                DiscoveredCameraDevice(
                    ipAddress = ip,
                    detectedBrand = CameraBrand.V720_NAXCLOW,
                    deviceName = "Cámara V720 Naxclow ($ip)",
                    openPorts = openPorts,
                    rtspPort = 554,
                    httpPort = if (openPorts.contains(8080)) 8080 else 80,
                    onvifPort = openPorts.firstOrNull { it in listOf(20190, 6123, 10002, 10003, 16123) } ?: 20190,
                    onvifServiceUrl = onvifServiceUrl,
                    suggestedMainPath = "/live/ch0",
                    suggestedSubPath = "/mjpeg/1",
                    macOrHardwareInfo = "V720 Naxclow (${openPorts.joinToString("/")})"
                )
            }

            openPorts.contains(2020) -> {
                DiscoveredCameraDevice(
                    ipAddress = ip,
                    detectedBrand = CameraBrand.TPLINK_TAPO,
                    deviceName = "Cámara TP-Link Tapo ($ip)",
                    openPorts = openPorts,
                    rtspPort = 554,
                    httpPort = 2020,
                    onvifPort = 2020,
                    onvifServiceUrl = onvifServiceUrl,
                    suggestedMainPath = "/stream1",
                    suggestedSubPath = "/stream2",
                    macOrHardwareInfo = "Tapo RTSP 554 + ONVIF 2020"
                )
            }

            openPorts.contains(8000) && openPorts.contains(554) -> {
                DiscoveredCameraDevice(
                    ipAddress = ip,
                    detectedBrand = CameraBrand.HIKVISION,
                    deviceName = "Cámara IP Hikvision / EZVIZ ($ip)",
                    openPorts = openPorts,
                    rtspPort = 554,
                    httpPort = 80,
                    onvifPort = 8000,
                    onvifServiceUrl = onvifServiceUrl,
                    suggestedMainPath = "/Streaming/Channels/101",
                    suggestedSubPath = "/Streaming/Channels/102",
                    macOrHardwareInfo = "Puertos: ${openPorts.joinToString(", ")}"
                )
            }

            openPorts.contains(8899) -> {
                DiscoveredCameraDevice(
                    ipAddress = ip,
                    detectedBrand = CameraBrand.ONVIF_GENERIC,
                    deviceName = "Cámara ONVIF ($ip)",
                    openPorts = openPorts,
                    rtspPort = if (openPorts.contains(554)) 554 else 8554,
                    httpPort = 80,
                    onvifPort = 8899,
                    onvifServiceUrl = onvifServiceUrl,
                    suggestedMainPath = "/live/ch00_1",
                    suggestedSubPath = "/live/ch00_0",
                    macOrHardwareInfo = "ONVIF Puerto 8899"
                )
            }

            openPorts.contains(81) && !openPorts.contains(554) -> {
                DiscoveredCameraDevice(
                    ipAddress = ip,
                    detectedBrand = CameraBrand.ESP32_CAM,
                    deviceName = "Cámara MJPEG / Eye4 HTTP ($ip)",
                    openPorts = openPorts,
                    rtspPort = 554,
                    httpPort = 81,
                    onvifPort = 80,
                    onvifServiceUrl = onvifServiceUrl,
                    suggestedMainPath = "/stream",
                    suggestedSubPath = "/stream",
                    macOrHardwareInfo = "HTTP Stream Puerto 81"
                )
            }

            openPorts.contains(8080) && !openPorts.contains(554) -> {
                DiscoveredCameraDevice(
                    ipAddress = ip,
                    detectedBrand = CameraBrand.V720_NAXCLOW,
                    deviceName = "Cámara V720 / IP Webcam ($ip)",
                    openPorts = openPorts,
                    rtspPort = 554,
                    httpPort = 8080,
                    onvifPort = 8080,
                    onvifServiceUrl = onvifServiceUrl,
                    suggestedMainPath = "/video",
                    suggestedSubPath = "/video",
                    macOrHardwareInfo = "HTTP Video Puerto 8080"
                )
            }

            else -> {
                val rtsp = if (openPorts.contains(8554) && !openPorts.contains(554)) 8554 else 554
                DiscoveredCameraDevice(
                    ipAddress = ip,
                    detectedBrand = if (onvifServiceUrl != null) CameraBrand.ONVIF_GENERIC else CameraBrand.CUSTOM_RTSP,
                    deviceName = if (onvifServiceUrl != null) "Cámara ONVIF ($ip)" else "Cámara RTSP ($ip)",
                    openPorts = openPorts,
                    rtspPort = rtsp,
                    httpPort = 80,
                    onvifPort = 80,
                    onvifServiceUrl = onvifServiceUrl,
                    suggestedMainPath = if (onvifServiceUrl != null) "/live/ch00_1" else "/live/ch0",
                    suggestedSubPath = if (onvifServiceUrl != null) "/live/ch00_0" else "/live/ch0",
                    macOrHardwareInfo = "Puertos abiertos: ${openPorts.joinToString(", ")}"
                )
            }
        }

        val baseDevice = refineWithFingerprint(coarseDevice)
        // Automatic UPnP IGD Port Mapping for zero-config remote viewing outside the home
        if (!gatewayWanIp.isNullOrBlank() && baseDevice.rtspPort > 0) {
            val octet = ip.substringAfterLast(".").toIntOrNull() ?: 10
            val preferredExtPort = 10000 + (octet * 10) + (if (baseDevice.rtspPort == 554) 4 else 0)
            val mapping = UpnpIgdManager.requestPortMapping(
                internalIp = ip,
                internalPort = baseDevice.rtspPort,
                preferredExternalPort = preferredExtPort
            )
            if (mapping != null && mapping.success) {
                val httpMap = if (baseDevice.httpPort > 0 && baseDevice.httpPort != baseDevice.rtspPort) {
                    UpnpIgdManager.requestPortMapping(
                        internalIp = ip,
                        internalPort = baseDevice.httpPort,
                        preferredExternalPort = 20000 + (octet * 10) + (baseDevice.httpPort % 10)
                    )
                } else null
                return baseDevice.copy(
                    remoteHost = mapping.externalWanIp,
                    remoteRtspPort = mapping.externalPort,
                    remoteHttpPort = if (httpMap != null && httpMap.success) httpMap.externalPort else baseDevice.httpPort,
                    upnpMapped = true
                )
            }
            return baseDevice.copy(remoteHost = gatewayWanIp)
        }
        return baseDevice
    }

    private data class SsdpInfo(val friendlyName: String, val summary: String)

    private val cameraTokens = listOf(
        "camera", "ipcam", "netcam", "onvif", "hikvision", "dahua", "foscam", "axis", "reolink",
        "amcrest", "uniview", "hanwha", "vivotek", "ezviz", "tapo", "nvr", "dvr", "webcam", "imou", "wyze", "xmeye"
    )

    /** SSDP M-SEARCH: detecta dispositivos UPnP que se identifican como cámara/NVR. */
    private fun discoverSsdpCameras(): Map<String, SsdpInfo> {
        val locations = mutableMapOf<String, String>()
        val raw = mutableMapOf<String, String>()
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 400
                val msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\nST: ssdp:all\r\n\r\n")
                    .toByteArray()
                val addr = InetAddress.getByName("239.255.255.250")
                repeat(2) { socket.send(DatagramPacket(msg, msg.size, addr, 1900)) }
                val buf = ByteArray(4096)
                val deadline = System.currentTimeMillis() + 1800L
                while (System.currentTimeMillis() < deadline) {
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        socket.receive(p)
                        val ip = p.address?.hostAddress ?: continue
                        val text = String(p.data, 0, p.length)
                        raw[ip] = (raw[ip].orEmpty() + "\n" + text)
                        Regex("(?im)^location:\\s*(\\S+)").find(text)?.groupValues?.get(1)?.let { locations[ip] = it }
                    } catch (_: java.net.SocketTimeoutException) {
                    }
                }
            }
        } catch (_: Exception) {
        }
        val result = mutableMapOf<String, SsdpInfo>()
        for ((ip, text) in raw) {
            var name = ""
            var summary = text.lowercase()
            locations[ip]?.let { loc ->
                try {
                    val c = (URL(loc).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 600
                        readTimeout = 800
                    }
                    val xml = c.inputStream.bufferedReader().readText().take(6000)
                    c.disconnect()
                    name = Regex("<friendlyName>(.*?)</friendlyName>").find(xml)?.groupValues?.get(1).orEmpty()
                    val maker = Regex("<manufacturer>(.*?)</manufacturer>").find(xml)?.groupValues?.get(1).orEmpty()
                    val model = Regex("<modelName>(.*?)</modelName>").find(xml)?.groupValues?.get(1).orEmpty()
                    summary = "$name $maker $model ${text}".lowercase()
                    if (name.isNotBlank() && maker.isNotBlank() && !name.contains(maker, true)) name = "$name ($maker)"
                } catch (_: Exception) {
                }
            }
            if (cameraTokens.any { summary.contains(it) }) {
                result[ip] = SsdpInfo(name, summary.replace(Regex("\\s+"), " ").take(90))
            }
        }
        return result
    }

    /** Lee cabecera Server / WWW-Authenticate / título HTML y RTSP OPTIONS para identificar la marca. */
    private fun fingerprint(ip: String, openPorts: List<Int>): String {
        val sb = StringBuilder()
        for (p in listOf(80, 8080, 81, 88, 8081, 2020)) {
            if (p !in openPorts) continue
            try {
                val c = (URL("http://$ip:$p/").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 700
                    readTimeout = 900
                    instanceFollowRedirects = false
                }
                val code = c.responseCode
                sb.append(c.getHeaderField("Server").orEmpty()).append(' ')
                sb.append(c.getHeaderField("WWW-Authenticate").orEmpty()).append(' ')
                val stream = if (code < 400) c.inputStream else c.errorStream
                if (stream != null) {
                    val buf = ByteArray(2048)
                    val n = stream.read(buf)
                    if (n > 0) sb.append(String(buf, 0, n))
                }
                c.disconnect()
            } catch (_: Exception) {
            }
        }
        if (554 in openPorts) {
            try {
                Socket().use { s ->
                    s.soTimeout = 700
                    s.connect(InetSocketAddress(ip, 554), 500)
                    s.getOutputStream().write("OPTIONS rtsp://$ip:554/ RTSP/1.0\r\nCSeq: 1\r\n\r\n".toByteArray())
                    val buf = ByteArray(512)
                    val n = s.getInputStream().read(buf)
                    if (n > 0) sb.append(String(buf, 0, n))
                }
            } catch (_: Exception) {
            }
        }
        return sb.toString().lowercase()
    }

    private fun refineWithFingerprint(d: DiscoveredCameraDevice): DiscoveredCameraDevice {
        if (d.detectedBrand == CameraBrand.V380_PRO && d.deviceId.isNotBlank()) return d
        val ports = d.openPorts
        val fp = fingerprint(d.ipAddress, ports)
        return when {
            37777 in ports || fp.contains("dahua") || fp.contains("amcrest") || fp.contains("lorex") -> d.copy(
                detectedBrand = CameraBrand.DAHUA,
                deviceName = "Cámara Dahua / Amcrest (${d.ipAddress})",
                rtspPort = 554,
                suggestedMainPath = "/cam/realmonitor?channel=1&subtype=0",
                suggestedSubPath = "/cam/realmonitor?channel=1&subtype=1"
            )
            fp.contains("hikvision") || fp.contains("dnvrs-webs") || fp.contains("app-webs") || fp.contains("hikvision") -> d.copy(
                detectedBrand = CameraBrand.HIKVISION,
                deviceName = "Cámara Hikvision (${d.ipAddress})",
                rtspPort = 554,
                suggestedMainPath = "/Streaming/Channels/101",
                suggestedSubPath = "/Streaming/Channels/102"
            )
            fp.contains("reolink") -> d.copy(
                detectedBrand = CameraBrand.REOLINK,
                deviceName = "Cámara Reolink (${d.ipAddress})",
                rtspPort = 554,
                suggestedMainPath = "/h264Preview_01_main",
                suggestedSubPath = "/h264Preview_01_sub"
            )
            fp.contains("ezviz") -> d.copy(detectedBrand = CameraBrand.EZVIZ, deviceName = "Cámara EZVIZ (${d.ipAddress})")
            fp.contains("tapo") || fp.contains("tp-link") -> d.copy(
                detectedBrand = CameraBrand.TPLINK_TAPO,
                deviceName = "Cámara TP-Link Tapo (${d.ipAddress})",
                suggestedMainPath = "/stream1",
                suggestedSubPath = "/stream2"
            )
            fp.contains("ip webcam") || fp.contains("ipwebcam") -> d.copy(
                detectedBrand = CameraBrand.ANDROID_IP_WEBCAM,
                deviceName = "Android IP Webcam (${d.ipAddress})"
            )
            34567 in ports || fp.contains("xmeye") || fp.contains("netsurveillance") -> d.copy(
                detectedBrand = CameraBrand.ONVIF_GENERIC,
                deviceName = "Cámara XMEye / NetSurveillance (${d.ipAddress})",
                rtspPort = 554,
                suggestedMainPath = "/user=admin&password=&channel=1&stream=0.sdp?",
                suggestedSubPath = "/user=admin&password=&channel=1&stream=1.sdp?"
            )
            fp.contains("foscam") -> d.copy(detectedBrand = CameraBrand.ONVIF_GENERIC, deviceName = "Cámara Foscam (${d.ipAddress})", suggestedMainPath = "/videoMain", suggestedSubPath = "/videoSub")
            fp.contains("axis") -> d.copy(detectedBrand = CameraBrand.ONVIF_GENERIC, deviceName = "Cámara Axis (${d.ipAddress})", suggestedMainPath = "/axis-media/media.amp", suggestedSubPath = "/axis-media/media.amp?resolution=640x480")
            fp.contains("uniview") || fp.contains("hanwha") || fp.contains("vivotek") -> d.copy(detectedBrand = CameraBrand.ONVIF_GENERIC, deviceName = "Cámara IP ONVIF (${d.ipAddress})")
            else -> d
        }
    }
    private fun discoverOnvifViaUdpMulticast(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val messageId = UUID.randomUUID().toString()
        val probeXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope"
                        xmlns:w="http://schemas.xmlsoap.org/ws/2004/08/addressing"
                        xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery"
                        xmlns:dn="http://www.onvif.org/ver10/network/wsdl">
                <e:Header>
                    <w:MessageID>uuid:$messageId</w:MessageID>
                    <w:To e:mustUnderstand="true">urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To>
                    <w:Action a:mustUnderstand="true" xmlns:a="http://www.w3.org/2005/08/addressing">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action>
                </e:Header>
                <e:Body>
                    <d:Probe>
                        <d:Types>dn:NetworkVideoTransmitter</d:Types>
                    </d:Probe>
                </e:Body>
            </e:Envelope>
        """.trimIndent()

        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 450
                val data = probeXml.toByteArray(Charsets.UTF_8)
                val multicastAddr = InetAddress.getByName("239.255.255.250")
                val packet = DatagramPacket(data, data.size, multicastAddr, 3702)
                socket.send(packet)

                val buffer = ByteArray(8192)
                val deadline = System.currentTimeMillis() + 1100L
                while (System.currentTimeMillis() < deadline) {
                    try {
                        val recv = DatagramPacket(buffer, buffer.size)
                        socket.receive(recv)
                        val senderIp = recv.address?.hostAddress ?: continue
                        val xml = String(recv.data, 0, recv.length, Charsets.UTF_8)
                        val xAddr = Regex("<[^>]*XAddrs[^>]*>(.*?)</[^>]*XAddrs>", RegexOption.DOT_MATCHES_ALL)
                            .find(xml)?.groupValues?.getOrNull(1)?.trim()?.split(" ")?.firstOrNull()
                        result[senderIp] = xAddr ?: "http://$senderIp/onvif/device_service"
                    } catch (_: Exception) {
                        break
                    }
                }
            }
        } catch (_: Exception) {
        }
        return result
    }
}

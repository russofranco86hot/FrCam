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
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
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

        onProgress(0.15f, "Enviando sonda UDP ONVIF WS-Discovery en $subnetPrefix.0/24...")
        val onvifMap = discoverOnvifViaUdpMulticast()

        for ((ip, serviceUrl) in onvifMap) {
            if (ip == localIp || discovered.containsKey(ip)) continue
            val openPorts = probeCameraPorts(ip, timeoutMs = 260)
            val device = classifyDevice(ip, openPorts, serviceUrl, gatewayWanIp)
            discovered[ip] = device
            onDeviceFound(device)
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
                    semaphore.withPermit {
                        val openPorts = probeCameraPorts(ip, timeoutMs = 220)
                        if (openPorts.isNotEmpty()) {
                            val isCameraCandidate = openPorts.any {
                                it in listOf(10554, 10080, 8800, 8899, 6123, 554, 8554, 2020, 8000, 81)
                            } || openPorts.contains(8080) || openPorts.contains(80)

                            if (isCameraCandidate) {
                                val validDevice = if (openPorts == listOf(80)) {
                                    isLikelyCameraHttp(ip, 80)
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

    private fun probeCameraPorts(ip: String, timeoutMs: Int): List<Int> {
        val open = mutableListOf<Int>()
        val primaryPorts = listOf(10554, 8800, 6123, 554, 8899, 10080, 8554, 2020, 8000, 81, 8080)
        for (port in primaryPorts) {
            if (isTcpPortOpen(ip, port, timeoutMs)) {
                open.add(port)
            }
        }
        if (open.isNotEmpty() && isTcpPortOpen(ip, 80, timeoutMs)) {
            open.add(80)
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
        return try {
            Socket().use { socket ->
                socket.soTimeout = 400
                socket.connect(InetSocketAddress(ip, port), 350)
                val out = socket.getOutputStream()
                val req = "GET / HTTP/1.0\r\nHost: $ip\r\n\r\n"
                out.write(req.toByteArray())
                out.flush()
                val resp = socket.getInputStream().bufferedReader().readText().lowercase()
                resp.contains("ipcam") ||
                    resp.contains("onvif") ||
                    resp.contains("vstarcam") ||
                    resp.contains("eye4") ||
                    resp.contains("naxclow") ||
                    resp.contains("hikvision") ||
                    resp.contains("dahua") ||
                    resp.contains("mjpeg") ||
                    resp.contains("camera")
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
        val baseDevice = when {
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

            openPorts.contains(6123) -> {
                DiscoveredCameraDevice(
                    ipAddress = ip,
                    detectedBrand = CameraBrand.V720_NAXCLOW,
                    deviceName = "Cámara V720 Naxclow ($ip)",
                    openPorts = openPorts,
                    rtspPort = 554,
                    httpPort = 80,
                    onvifPort = 6123,
                    onvifServiceUrl = onvifServiceUrl,
                    suggestedMainPath = "/live/ch0",
                    suggestedSubPath = "/mjpeg/1",
                    macOrHardwareInfo = "V720 Naxclow Puerto 6123"
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
                    detectedBrand = if (onvifServiceUrl != null) CameraBrand.ONVIF_GENERIC else CameraBrand.V380_PRO,
                    deviceName = if (onvifServiceUrl != null) "Cámara ONVIF ($ip)" else "Cámara RTSP ($ip)",
                    openPorts = openPorts,
                    rtspPort = rtsp,
                    httpPort = 80,
                    onvifPort = 80,
                    onvifServiceUrl = onvifServiceUrl,
                    suggestedMainPath = "/live/ch00_1",
                    suggestedSubPath = "/live/ch00_0",
                    macOrHardwareInfo = "Puertos abiertos: ${openPorts.joinToString(", ")}"
                )
            }
        }

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
                return baseDevice.copy(
                    remoteHost = mapping.externalWanIp,
                    remoteRtspPort = mapping.externalPort,
                    remoteHttpPort = baseDevice.httpPort,
                    upnpMapped = true
                )
            }
            return baseDevice.copy(remoteHost = gatewayWanIp)
        }
        return baseDevice
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

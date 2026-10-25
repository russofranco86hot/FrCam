package com.frcam.app.protocol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.UUID

data class UpnpGatewayInfo(
    val rootDescUrl: String,
    val controlUrl: String,
    val serviceType: String,
    val externalWanIp: String?
)

data class UpnpMappingResult(
    val success: Boolean,
    val externalWanIp: String,
    val externalPort: Int,
    val internalIp: String,
    val internalPort: Int
)

/**
 * UPnP IGD (Internet Gateway Device) Client.
 * Automatically discovers home router gateway, resolves public WAN IP,
 * and requests automatic port mappings so cameras can be accessed from outside
 * the local network without requiring any manual router configuration.
 */
object UpnpIgdManager {

    private var cachedGateway: UpnpGatewayInfo? = null

    suspend fun discoverGateway(): UpnpGatewayInfo? = withContext(Dispatchers.IO) {
        if (cachedGateway != null) return@withContext cachedGateway

        val ssdpMessage = """
            M-SEARCH * HTTP/1.1
            HOST: 239.255.255.250:1900
            ST: urn:schemas-upnp-org:device:InternetGatewayDevice:1
            MAN: "ssdp:discover"
            MX: 2

        """.trimIndent().replace("\n", "\r\n")

        var locationUrl: String? = null

        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 800
                val data = ssdpMessage.toByteArray(Charsets.US_ASCII)
                val target = InetAddress.getByName("239.255.255.250")
                socket.send(DatagramPacket(data, data.size, target, 1900))

                val buf = ByteArray(2048)
                val deadline = System.currentTimeMillis() + 1200L
                while (System.currentTimeMillis() < deadline) {
                    try {
                        val packet = DatagramPacket(buf, buf.size)
                        socket.receive(packet)
                        val resp = String(packet.data, 0, packet.length, Charsets.US_ASCII)
                        val match = Regex("(?i)LOCATION:\\s*(https?://[^\r\n]+)").find(resp)
                        if (match != null) {
                            locationUrl = match.groupValues[1].trim()
                            break
                        }
                    } catch (_: Exception) {
                        break
                    }
                }
            }
        } catch (_: Exception) {
        }

        if (locationUrl.isNullOrBlank()) {
            // Fallback SSDP search for WANIPConnection service directly
            try {
                val wanSearch = """
                    M-SEARCH * HTTP/1.1
                    HOST: 239.255.255.250:1900
                    ST: urn:schemas-upnp-org:service:WANIPConnection:1
                    MAN: "ssdp:discover"
                    MX: 2

                """.trimIndent().replace("\n", "\r\n")

                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    socket.soTimeout = 600
                    val data = wanSearch.toByteArray(Charsets.US_ASCII)
                    val target = InetAddress.getByName("239.255.255.250")
                    socket.send(DatagramPacket(data, data.size, target, 1900))

                    val buf = ByteArray(2048)
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    val resp = String(packet.data, 0, packet.length, Charsets.US_ASCII)
                    val match = Regex("(?i)LOCATION:\\s*(https?://[^\r\n]+)").find(resp)
                    if (match != null) {
                        locationUrl = match.groupValues[1].trim()
                    }
                }
            } catch (_: Exception) {
            }
        }

        val location = locationUrl ?: return@withContext null
        val gateway = parseGatewayXml(location) ?: return@withContext null
        cachedGateway = gateway
        gateway
    }

    private fun parseGatewayXml(locationUrl: String): UpnpGatewayInfo? {
        return try {
            val conn = (URL(locationUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 3000
                readTimeout = 3000
                requestMethod = "GET"
            }
            if (conn.responseCode !in 200..299) return null
            val xml = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            var serviceType = "urn:schemas-upnp-org:service:WANIPConnection:1"
            var controlUrlMatch = Regex(
                """<serviceType>[^<]*(WANIPConnection|WANPPPConnection)[^<]*</serviceType>\s*<serviceId>[^<]*</serviceId>\s*<controlURL>([^<]+)</controlURL>""",
                RegexOption.IGNORE_CASE
            ).find(xml)

            if (controlUrlMatch == null) {
                controlUrlMatch = Regex("""<controlURL>([^<]+)</controlURL>""", RegexOption.IGNORE_CASE).find(xml)
            }

            val relControlUrl = controlUrlMatch?.groupValues?.lastOrNull()?.trim() ?: "/ctl/IPConn"
            val baseUrl = URL(locationUrl)
            val fullControlUrl = if (relControlUrl.startsWith("http://") || relControlUrl.startsWith("https://")) {
                relControlUrl
            } else if (relControlUrl.startsWith("/")) {
                "${baseUrl.protocol}://${baseUrl.host}:${baseUrl.port}$relControlUrl"
            } else {
                "${baseUrl.protocol}://${baseUrl.host}:${baseUrl.port}/$relControlUrl"
            }

            val wanIp = queryExternalIp(fullControlUrl, serviceType)

            UpnpGatewayInfo(
                rootDescUrl = locationUrl,
                controlUrl = fullControlUrl,
                serviceType = serviceType,
                externalWanIp = wanIp
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun queryExternalIp(controlUrl: String, serviceType: String): String? {
        val soapBody = """
            <?xml version="1.0"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                <s:Body>
                    <u:GetExternalIPAddress xmlns:u="$serviceType" />
                </s:Body>
            </s:Envelope>
        """.trimIndent()

        return try {
            val conn = (URL(controlUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 3000
                readTimeout = 3000
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                setRequestProperty("SOAPAction", "\"$serviceType#GetExternalIPAddress\"")
            }
            conn.outputStream.use { it.write(soapBody.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode in 200..299) {
                val resp = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val match = Regex("""<NewExternalIPAddress>([^<]+)</NewExternalIPAddress>""").find(resp)
                val ip = match?.groupValues?.getOrNull(1)?.trim()
                if (!ip.isNullOrBlank() && ip != "0.0.0.0") ip else null
            } else {
                conn.disconnect()
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Automatically requests the router to open an external port and forward it
     * to the internal camera IP and port.
     */
    suspend fun requestPortMapping(
        internalIp: String,
        internalPort: Int,
        preferredExternalPort: Int = internalPort + 10000,
        protocol: String = "TCP"
    ): UpnpMappingResult? = withContext(Dispatchers.IO) {
        val gateway = discoverGateway() ?: return@withContext null
        val wanIp = gateway.externalWanIp ?: queryExternalIp(gateway.controlUrl, gateway.serviceType)
        if (wanIp.isNullOrBlank()) return@withContext null

        val externalPort = if (preferredExternalPort in 1024..65535) preferredExternalPort else internalPort + 10000

        val soapBody = """
            <?xml version="1.0"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                <s:Body>
                    <u:AddPortMapping xmlns:u="${gateway.serviceType}">
                        <NewRemoteHost></NewRemoteHost>
                        <NewExternalPort>$externalPort</NewExternalPort>
                        <NewProtocol>$protocol</NewProtocol>
                        <NewInternalPort>$internalPort</NewInternalPort>
                        <NewInternalClient>$internalIp</NewInternalClient>
                        <NewEnabled>1</NewEnabled>
                        <NewPortMappingDescription>FrCam Auto $internalPort</NewPortMappingDescription>
                        <NewLeaseDuration>0</NewLeaseDuration>
                    </u:AddPortMapping>
                </s:Body>
            </s:Envelope>
        """.trimIndent()

        try {
            val conn = (URL(gateway.controlUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 3500
                readTimeout = 3500
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                setRequestProperty("SOAPAction", "\"${gateway.serviceType}#AddPortMapping\"")
            }
            conn.outputStream.use { it.write(soapBody.toByteArray(Charsets.UTF_8)) }
            val ok = conn.responseCode in 200..299
            conn.disconnect()

            if (ok) {
                UpnpMappingResult(
                    success = true,
                    externalWanIp = wanIp,
                    externalPort = externalPort,
                    internalIp = internalIp,
                    internalPort = internalPort
                )
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}

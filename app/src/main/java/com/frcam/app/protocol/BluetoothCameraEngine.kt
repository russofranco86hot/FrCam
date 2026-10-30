package com.frcam.app.protocol

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.frcam.app.model.CameraBrand
import com.frcam.app.model.CameraConfig
import com.frcam.app.model.ConnectionMode
import com.frcam.app.model.PtzControlType
import com.frcam.app.model.StreamProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.util.UUID

data class DiscoveredBleCamera(
    val device: BluetoothDevice,
    val name: String,
    val address: String,
    val rssi: Int,
    val brand: CameraBrand = CameraBrand.V720_NAXCLOW,
    val rawDeviceId: String = ""
)

/**
 * Motor de detección y vinculación de cámaras mediante Bluetooth Low Energy (BLE).
 * Especialmente diseñado para cámaras V720 (Naxclow, A9, Xiaovv) y dispositivos BLE
 * que transmiten balizas Bluetooth para vinculación rápida sin ingresar IP ni contraseñas.
 */
object BluetoothCameraEngine {

    private const val TAG = "BluetoothCameraEngine"

    private val _discoveredDevices = MutableStateFlow<List<DiscoveredBleCamera>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredBleCamera>> = _discoveredDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _provisioningStatus = MutableStateFlow<String?>(null)
    val provisioningStatus: StateFlow<String?> = _provisioningStatus.asStateFlow()

    private var activeGatt: BluetoothGatt? = null
    private var scanCallback: ScanCallback? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun hasBluetoothPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun isBluetoothEnabled(context: Context): Boolean {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return manager?.adapter?.isEnabled == true
    }

    @SuppressLint("MissingPermission")
    fun startBleScan(context: Context, onError: (String) -> Unit = {}) {
        if (!hasBluetoothPermission(context)) {
            onError("Se requiere permiso de Bluetooth para buscar cámaras cercanas.")
            return
        }
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            onError("Por favor activa el Bluetooth en tu celular para detectar la cámara V720.")
            return
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            onError("El escáner Bluetooth de bajo consumo (BLE) no está disponible.")
            return
        }

        stopBleScan(context)
        _discoveredDevices.value = emptyList()
        _isScanning.value = true

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                result ?: return
                processScanResult(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                results?.forEach { processScanResult(it) }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "BLE Scan failed with code: $errorCode")
                _isScanning.value = false
            }
        }

        try {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            scanner.startScan(emptyList(), settings, scanCallback)

            // Auto-stop tras 15 segundos
            mainHandler.postDelayed({
                stopBleScan(context)
            }, 15000L)
        } catch (e: Exception) {
            _isScanning.value = false
            onError("Error iniciando búsqueda Bluetooth: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun stopBleScan(context: Context? = null) {
        if (_isScanning.value) {
            try {
                if (context != null && hasBluetoothPermission(context)) {
                    val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                    manager?.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
                }
            } catch (_: Exception) {}
            _isScanning.value = false
            scanCallback = null
        }
    }

    @SuppressLint("MissingPermission")
    private fun processScanResult(result: ScanResult) {
        val device = result.device ?: return
        val name = device.name?.trim().orEmpty()
        val address = device.address.orEmpty()
        val rssi = result.rssi

        val isCamera = isLikelyCameraBle(name, result)
        if (!isCamera) return

        val currentList = _discoveredDevices.value.toMutableList()
        val existingIndex = currentList.indexOfFirst { it.address == address }

        val brand = when {
            name.startsWith("NAX", ignoreCase = true) ||
                name.startsWith("V720", ignoreCase = true) ||
                name.startsWith("A9", ignoreCase = true) ||
                name.startsWith("JA", ignoreCase = true) ||
                name.startsWith("FT", ignoreCase = true) ||
                name.startsWith("BAT", ignoreCase = true) ||
                name.startsWith("CAM", ignoreCase = true) ||
                name.startsWith("BK", ignoreCase = true) ||
                name.startsWith("XiaoV", ignoreCase = true) -> CameraBrand.V720_NAXCLOW
            name.startsWith("VSTC", ignoreCase = true) || name.startsWith("EYE4", ignoreCase = true) -> CameraBrand.EYE4_VSTARCAM
            name.startsWith("MV", ignoreCase = true) -> CameraBrand.V380_PRO
            else -> CameraBrand.V720_NAXCLOW
        }

        val devId = when {
            name.contains("_") -> name.substringAfterLast("_")
            name.contains("-") -> name.substringAfterLast("-")
            else -> address.replace(":", "").takeLast(6)
        }

        val bleCam = DiscoveredBleCamera(
            device = device,
            name = if (name.isNotBlank()) name else "Cámara V720 ($devId)",
            address = address,
            rssi = rssi,
            brand = brand,
            rawDeviceId = devId
        )

        if (existingIndex >= 0) {
            currentList[existingIndex] = bleCam
        } else {
            currentList.add(bleCam)
        }
        _discoveredDevices.value = currentList
    }

    private fun isLikelyCameraBle(name: String, result: ScanResult): Boolean {
        val upper = name.uppercase()
        val cameraPrefixes = listOf(
            "NAX", "V720", "A9", "JA_", "FT_", "BAT_", "CAM", "MINI", "BK_", "XIAOV", "IPC", "WF_", "720", "VSTC", "EYE4", "MV"
        )
        if (cameraPrefixes.any { upper.startsWith(it) || upper.contains(it) }) {
            return true
        }

        // Reconocer balizas por Service UUID de cámaras comunes (Beken BK7252 / Naxclow / Tuya / Anyka)
        val uuids = result.scanRecord?.serviceUuids.orEmpty().map { it.uuid.toString().lowercase() }
        for (u in uuids) {
            if (u.contains("fee7") || u.contains("fff0") || u.contains("ffe0") || u.contains("fe01") || u.contains("ba01")) {
                return true
            }
        }
        return false
    }

    /**
     * Vincula la cámara enviando las credenciales Wi-Fi (SSID y Password) a través de Bluetooth GATT.
     * La cámara recibe los datos por BLE, se conecta al router Wi-Fi de inmediato y queda lista para usar.
     */
    @SuppressLint("MissingPermission")
    fun provisionWifiOverBle(
        context: Context,
        bleCamera: DiscoveredBleCamera,
        ssid: String,
        pass: String,
        onStatus: (String) -> Unit,
        onResult: (Result<String>) -> Unit
    ) {
        if (!hasBluetoothPermission(context)) {
            onResult(Result.failure(Exception("Faltan permisos de Bluetooth para vincular.")))
            return
        }

        stopBleScan(context)
        _provisioningStatus.value = "Conectando por Bluetooth a ${bleCamera.name}..."
        onStatus("Conectando con ${bleCamera.name} por Bluetooth...")

        var isDone = false
        var currentMtu = 23
        val timeoutRunnable = Runnable {
            if (isDone) return@Runnable
            isDone = true
            try {
                activeGatt?.disconnect()
                activeGatt?.close()
                activeGatt = null
            } catch (_: Exception) {}
            _provisioningStatus.value = "Tiempo de espera agotado al conectar por Bluetooth"
            onResult(Result.failure(Exception("No hubo respuesta de la cámara por Bluetooth. Verifica que esté encendida con la luz azul parpadeando.")))
        }
        mainHandler.postDelayed(timeoutRunnable, 22000L)

        activeGatt = bleCamera.device.connectGatt(
            context,
            false,
            object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        mainHandler.post {
                            _provisioningStatus.value = "¡Bluetooth conectado! Negociando canal de datos..."
                            onStatus("Conectado con ${bleCamera.name}. Negociando canal de datos...")
                        }
                        try {
                            val mtuRequested = gatt?.requestMtu(256) ?: false
                            if (!mtuRequested) {
                                gatt?.discoverServices()
                            }
                        } catch (e: Exception) {
                            gatt?.discoverServices()
                        }
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        Log.d(TAG, "BLE GATT desconectado (la cámara se está reiniciando con Wi-Fi)")
                        if (!isDone) {
                            isDone = true
                            mainHandler.removeCallbacks(timeoutRunnable)
                            try {
                                gatt?.close()
                                activeGatt = null
                            } catch (_: Exception) {}
                            mainHandler.post {
                                _provisioningStatus.value = "¡Cámara vinculada! Conectando a Wi-Fi (luz azul fija)..."
                                onResult(Result.success("¡Credenciales aplicadas a ${bleCamera.name}! Espera unos segundos a que la luz azul quede fija."))
                            }
                        }
                    }
                }

                override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
                    Log.d(TAG, "MTU BLE configurado a $mtu (status=$status)")
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        currentMtu = mtu
                    }
                    gatt?.discoverServices()
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS || gatt == null) {
                        finishWithError("No se pudieron leer los servicios de la cámara", onResult, timeoutRunnable)
                        return
                    }

                    val writeChars = findWritableCharacteristics(gatt.services)
                    if (writeChars.isEmpty()) {
                        finishWithError("No se encontró canal de escritura en la cámara", onResult, timeoutRunnable)
                        return
                    }

                    findNotifyCharacteristic(gatt.services)?.let { notifyChar ->
                        try {
                            gatt.setCharacteristicNotification(notifyChar, true)
                        } catch (_: Exception) {}
                    }

                    mainHandler.post {
                        _provisioningStatus.value = "Enviando credenciales Wi-Fi por Bluetooth..."
                        onStatus("Enviando red '$ssid' a la cámara...")
                    }

                    val cleanSsid = ssid.trim()
                    val payloads = listOf(
                        """{"code":204,"s":"$cleanSsid","p":"$pass"}""",
                        """{"s":"$cleanSsid","p":"$pass"}""",
                        """{"cmd":"wifi","ssid":"$cleanSsid","password":"$pass"}"""
                    )

                    // Transmisión de tramas completas sin fragmentar para que el parser JSON del firmware de la cámara no falle
                    kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                        var sentCount = 0
                        for (char in writeChars) {
                            val hasNoResp = (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                            val writeType = if (hasNoResp) {
                                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                            } else {
                                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                            }

                            for (json in payloads) {
                                for (format in listOf(json, "$json\n")) {
                                    val rawBytes = format.toByteArray(Charsets.UTF_8)
                                    try {
                                        @Suppress("DEPRECATION")
                                        char.value = rawBytes
                                        @Suppress("DEPRECATION")
                                        char.writeType = writeType
                                        val sent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                            gatt.writeCharacteristic(char, rawBytes, writeType) == 0
                                        } else {
                                            @Suppress("DEPRECATION")
                                            gatt.writeCharacteristic(char)
                                        }
                                        if (sent) sentCount++
                                        Log.d(TAG, "Payload enviado a ${char.uuid} ($writeType): $format")
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Error transmitiendo a ${char.uuid}: ${e.message}")
                                    }
                                    kotlinx.coroutines.delay(40L)
                                }
                            }
                        }

                        if (sentCount > 0) {
                            mainHandler.post {
                                _provisioningStatus.value = "¡Credenciales recibidas por la cámara! Conectando a '$cleanSsid'..."
                                onStatus("¡Red '$cleanSsid' enviada! Esperando que la luz azul quede fija...")
                            }
                            // Desconectar BLE tras 1.2 segundos para liberar la antena compartida de 2.4 GHz
                            // y permitir que el microprocesador de la cámara pase de modo BLE a Wi-Fi STA (luz fija)
                            mainHandler.postDelayed({
                                if (!isDone) {
                                    isDone = true
                                    mainHandler.removeCallbacks(timeoutRunnable)
                                    try {
                                        gatt.disconnect()
                                        gatt.close()
                                        activeGatt = null
                                    } catch (_: Exception) {}
                                    _provisioningStatus.value = "¡Wi-Fi configurado! Luz azul fija."
                                    onResult(Result.success("¡Credenciales enviadas a la cámara! Espera a que la luz azul quede fija y pulsa 'Buscar IP'."))
                                }
                            }, 1200L)
                        } else {
                            finishWithError("No se pudo transmitir la configuración por Bluetooth", onResult, timeoutRunnable)
                        }
                    }
                }

                override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
                    Log.d(TAG, "onCharacteristicWrite status=$status")
                }

                override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
                    val data = characteristic?.value ?: return
                    val msg = String(data, Charsets.UTF_8)
                    Log.d(TAG, "Notificación recibida de cámara: $msg")
                }
            },
            BluetoothDevice.TRANSPORT_LE
        )
    }

    private fun findNotifyCharacteristic(services: List<BluetoothGattService>): BluetoothGattCharacteristic? {
        for (service in services) {
            for (char in service.characteristics) {
                if ((char.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                    return char
                }
            }
        }
        return null
    }

    private fun findWritableCharacteristics(services: List<BluetoothGattService>): List<BluetoothGattCharacteristic> {
        val preferred = mutableListOf<BluetoothGattCharacteristic>()
        val others = mutableListOf<BluetoothGattCharacteristic>()

        for (service in services) {
            val sUuid = service.uuid.toString().lowercase()
            // Ignorar servicios estándar Bluetooth SIG que no aceptan comandos Wi-Fi
            if (sUuid.startsWith("000018")) continue

            for (char in service.characteristics) {
                val prop = char.properties
                val isWritable = (prop and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) ||
                    (prop and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
                if (isWritable) {
                    val cUuid = char.uuid.toString().lowercase()
                    if (sUuid.contains("fee7") || sUuid.contains("fff0") || sUuid.contains("ffe0") ||
                        sUuid.contains("ae00") || sUuid.contains("ae01") || sUuid.contains("fe01") || sUuid.contains("ba01") ||
                        cUuid.contains("fec7") || cUuid.contains("fec8") || cUuid.contains("fec9") ||
                        cUuid.contains("fff1") || cUuid.contains("fff2") || cUuid.contains("ffe1") || cUuid.contains("ae01")
                    ) {
                        preferred.add(char)
                    } else {
                        others.add(char)
                    }
                }
            }
        }
        return if (preferred.isNotEmpty()) preferred else others
    }

    private fun finishWithError(msg: String, onResult: (Result<String>) -> Unit, timeoutRunnable: Runnable) {
        mainHandler.removeCallbacks(timeoutRunnable)
        try {
            activeGatt?.disconnect()
            activeGatt?.close()
            activeGatt = null
        } catch (_: Exception) {}
        mainHandler.post {
            _provisioningStatus.value = msg
            onResult(Result.failure(Exception(msg)))
        }
    }

    /**
     * Construye una configuración lista para FrCam a partir de una cámara descubierta por Bluetooth.
     * En cámaras V720, el usuario y la contraseña quedan vacíos porque no se requieren.
     */
    fun createCameraConfigFromBle(bleCamera: DiscoveredBleCamera, hostIp: String = ""): CameraConfig {
        return CameraConfig(
            id = UUID.randomUUID().toString(),
            name = bleCamera.name,
            brand = CameraBrand.V720_NAXCLOW,
            deviceId = bleCamera.rawDeviceId,
            host = hostIp,
            rtspPort = 554,
            httpPort = 80,
            onvifPort = 6123,
            protocol = StreamProtocol.V720_LAN,
            connectionMode = ConnectionMode.LAN_IP,
            username = "",
            password = "",
            mainStreamPath = "/video",
            subStreamPath = "/video",
            ptzType = PtzControlType.NONE,
            autoRecordMode = "OFF"
        )
    }

    /**
     * Consulta la tabla de vecinos ARP del sistema para obtener correlaciones IP <-> MAC.
     */
    fun getArpTable(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        try {
            java.io.File("/proc/net/arp").forEachLine { line ->
                val parts = line.split("\\s+".toRegex())
                if (parts.size >= 4 && parts[0] != "IP") {
                    val ip = parts[0]
                    val mac = parts[3].lowercase()
                    if (mac != "00:00:00:00:00:00" && mac.length >= 17) {
                        map[ip] = mac
                    }
                }
            }
        } catch (_: Exception) {}

        if (map.isEmpty()) {
            try {
                val process = Runtime.getRuntime().exec("ip neigh")
                process.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val parts = line.split("\\s+".toRegex())
                        val ip = parts.getOrNull(0) ?: continue
                        val lladdrIndex = parts.indexOf("lladdr")
                        if (lladdrIndex != -1 && lladdrIndex + 1 < parts.size) {
                            val mac = parts[lladdrIndex + 1].lowercase()
                            if (mac != "00:00:00:00:00:00") {
                                map[ip] = mac
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        return map
    }

    /**
     * Resuelve automáticamente la dirección IP local de la cámara V720 en la red Wi-Fi
     * mediante búsqueda ARP por MAC, broadcast UDP y escaneo rápido de puertos V720 (6123/10002).
     */
    suspend fun resolveV720Ip(bleCamera: DiscoveredBleCamera? = null): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val targetMac = bleCamera?.address?.lowercase()?.trim()

        // 1. Match en tabla ARP por dirección MAC de la cámara Bluetooth
        if (!targetMac.isNullOrBlank()) {
            val cleanTarget = targetMac.replace(":", "").replace("-", "")
            val arp = getArpTable()
            for ((ip, mac) in arp) {
                val cleanMac = mac.replace(":", "").replace("-", "")
                if (cleanMac == cleanTarget || (cleanMac.length >= 6 && cleanTarget.length >= 6 && cleanMac.takeLast(6) == cleanTarget.takeLast(6))) {
                    Log.d(TAG, "Cámara V720 encontrada en tabla ARP: $ip ($mac)")
                    return@withContext ip
                }
            }
        }

        // 2. Búsqueda por Broadcast UDP
        val udpDevices = NetworkCameraScanner.discoverV720ViaUdpBroadcast()
        if (udpDevices.isNotEmpty()) {
            val ip = udpDevices.first().ipAddress
            Log.d(TAG, "Cámara V720 encontrada por broadcast UDP: $ip")
            return@withContext ip
        }

        // 3. Barrido concurrente de la subred local (UDP {"code":50} y puertos propietarios V720 / HTTP / RTSP)
        val localIp = NetworkCameraScanner.getLocalIpv4Address()
        val subnetPrefix = localIp?.substringBeforeLast(".").orEmpty()
        if (subnetPrefix.isNotBlank()) {
            val v720Ports = listOf(80, 8080, 554, 81, 6123, 10002, 10003, 16123, 8554, 8888, 5000, 6600)
            val probeData = """{"code":50}""".toByteArray(Charsets.UTF_8)
            val foundIp = coroutineScope {
                (1..254).map { octet ->
                    async {
                        val ip = "$subnetPrefix.$octet"
                        if (ip == localIp || ip.endsWith(".1") || ip.endsWith(".254")) return@async null

                        // 3a. Probe UDP ultrarrápido {"code":50}
                        try {
                            java.net.DatagramSocket().use { sock ->
                                sock.soTimeout = 120
                                val target = java.net.InetAddress.getByName(ip)
                                sock.send(java.net.DatagramPacket(probeData, probeData.size, target, 6123))
                                sock.send(java.net.DatagramPacket(probeData, probeData.size, target, 10002))
                                val rBuf = ByteArray(512)
                                val rPack = java.net.DatagramPacket(rBuf, rBuf.size)
                                sock.receive(rPack)
                                val rText = String(rBuf, 0, rPack.length, Charsets.UTF_8)
                                if (rText.contains("\"code\"") || rPack.length > 0) {
                                    return@async ip
                                }
                            }
                        } catch (_: Exception) {}

                        // 3b. Probe TCP en puertos V720 / HTTP / RTSP
                        for (port in v720Ports) {
                            if (NetworkCameraScanner.isTcpPortOpen(ip, port, timeoutMs = 150)) {
                                return@async ip
                            }
                        }
                        null
                    }
                }.awaitAll().filterNotNull().firstOrNull()
            }
            if (foundIp != null) {
                Log.d(TAG, "Cámara V720 detectada en barrido de subred: $foundIp")
                return@withContext foundIp
            }
        }

        // 4. Verificación de modo AP (únicamente si el teléfono está en esa subred AP)
        if (localIp?.startsWith("192.168.169.") == true || localIp?.startsWith("192.168.4.") == true) {
            for (apIp in listOf("192.168.169.1", "192.168.4.1")) {
                if (NetworkCameraScanner.isTcpPortOpen(apIp, 80, 250) ||
                    NetworkCameraScanner.isTcpPortOpen(apIp, 6123, 250) ||
                    NetworkCameraScanner.isTcpPortOpen(apIp, 10002, 250) ||
                    NetworkCameraScanner.isTcpPortOpen(apIp, 8080, 250)
                ) {
                    Log.d(TAG, "Cámara V720 detectada en modo AP directo: $apIp")
                    return@withContext apIp
                }
            }
        }

        null
    }
}

package com.frcam.app.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.frcam.app.data.CameraRepository
import com.frcam.app.model.CameraAccessRecord
import com.frcam.app.model.CameraBrand
import com.frcam.app.model.CameraConfig
import com.frcam.app.model.CameraDeviceInfo
import com.frcam.app.model.ConnectionMode
import com.frcam.app.model.DiscoveredCameraDevice
import com.frcam.app.model.GridLayoutMode
import com.frcam.app.model.WifiNetworkScanResult
import com.frcam.app.protocol.CameraControlEngine
import com.frcam.app.protocol.CameraProvisioningEngine
import com.frcam.app.protocol.ConnectionDiagnosticResult
import com.frcam.app.protocol.NetworkCameraScanner
import com.frcam.app.protocol.PtzDirection
import com.frcam.app.protocol.RingAuthOutcome
import com.frcam.app.protocol.RingCloudClient
import com.frcam.app.protocol.RingCloudDevice
import com.frcam.app.protocol.V380AuxCommand
import com.frcam.app.protocol.WaitingCameraAp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CameraViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = CameraRepository(application.applicationContext)

    val cameras: StateFlow<List<CameraConfig>> = repository.camerasFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val gridMode: StateFlow<GridLayoutMode> = repository.gridModeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), GridLayoutMode.GRID_2X2)

    val ringRefreshToken: StateFlow<String> = repository.ringRefreshTokenFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    private val _selectedCameraId = MutableStateFlow<String?>(null)
    val selectedCameraId: StateFlow<String?> = _selectedCameraId.asStateFlow()

    private val _isScanningLan = MutableStateFlow(false)
    val isScanningLan: StateFlow<Boolean> = _isScanningLan.asStateFlow()

    private val _scanProgress = MutableStateFlow(0f)
    val scanProgress: StateFlow<Float> = _scanProgress.asStateFlow()

    private val _scanStatusText = MutableStateFlow("")
    val scanStatusText: StateFlow<String> = _scanStatusText.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<DiscoveredCameraDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredCameraDevice>> = _discoveredDevices.asStateFlow()

    private val _ptzFeedback = MutableStateFlow<String?>(null)
    val ptzFeedback: StateFlow<String?> = _ptzFeedback.asStateFlow()

    private val _diagnosticResult = MutableStateFlow<ConnectionDiagnosticResult?>(null)
    val diagnosticResult: StateFlow<ConnectionDiagnosticResult?> = _diagnosticResult.asStateFlow()

    private val _isTestingConnection = MutableStateFlow(false)
    val isTestingConnection: StateFlow<Boolean> = _isTestingConnection.asStateFlow()

    private val _isLoggingInRing = MutableStateFlow(false)
    val isLoggingInRing: StateFlow<Boolean> = _isLoggingInRing.asStateFlow()

    private val _ringStatusMessage = MutableStateFlow("")
    val ringStatusMessage: StateFlow<String> = _ringStatusMessage.asStateFlow()

    private val _ringNeedsTwoFactor = MutableStateFlow(false)
    val ringNeedsTwoFactor: StateFlow<Boolean> = _ringNeedsTwoFactor.asStateFlow()

    private val _ringDiscoveredDevices = MutableStateFlow<List<RingCloudDevice>>(emptyList())
    val ringDiscoveredDevices: StateFlow<List<RingCloudDevice>> = _ringDiscoveredDevices.asStateFlow()

    private val _deviceInfo = MutableStateFlow<CameraDeviceInfo?>(null)
    val deviceInfo: StateFlow<CameraDeviceInfo?> = _deviceInfo.asStateFlow()

    private val _accessLogs = MutableStateFlow<List<CameraAccessRecord>>(emptyList())
    val accessLogs: StateFlow<List<CameraAccessRecord>> = _accessLogs.asStateFlow()

    private val _wifiScanList = MutableStateFlow<List<WifiNetworkScanResult>>(emptyList())
    val wifiScanList: StateFlow<List<WifiNetworkScanResult>> = _wifiScanList.asStateFlow()

    private val _isScanningWifi = MutableStateFlow(false)
    val isScanningWifi: StateFlow<Boolean> = _isScanningWifi.asStateFlow()

    private val _adminStatusMessage = MutableStateFlow<String?>(null)
    val adminStatusMessage: StateFlow<String?> = _adminStatusMessage.asStateFlow()

    private val _waitingCameraAps = MutableStateFlow<List<WaitingCameraAp>>(emptyList())
    val waitingCameraAps: StateFlow<List<WaitingCameraAp>> = _waitingCameraAps.asStateFlow()

    private val _isScanningWaitingCameras = MutableStateFlow(false)
    val isScanningWaitingCameras: StateFlow<Boolean> = _isScanningWaitingCameras.asStateFlow()

    private val _isProvisioningCamera = MutableStateFlow(false)
    val isProvisioningCamera: StateFlow<Boolean> = _isProvisioningCamera.asStateFlow()

    private val _provisioningStatusMessage = MutableStateFlow<String?>(null)
    val provisioningStatusMessage: StateFlow<String?> = _provisioningStatusMessage.asStateFlow()

    init {
        viewModelScope.launch {
            ringRefreshToken.collectLatest { token ->
                if (token.isNotBlank()) {
                    RingCloudClient.setSavedRefreshToken(token)
                }
            }
        }
    }

    val localPhoneIp: String?
        get() = NetworkCameraScanner.getLocalIpv4Address()

    fun selectCamera(cameraId: String?) {
        _selectedCameraId.value = cameraId
        _ptzFeedback.value = null
    }

    fun setGridMode(mode: GridLayoutMode) {
        viewModelScope.launch {
            repository.setGridMode(mode)
        }
    }

    fun addOrUpdateCamera(camera: CameraConfig) {
        viewModelScope.launch {
            val current = cameras.value.toMutableList()
            val existingIdx = current.indexOfFirst { it.id == camera.id }
            if (existingIdx >= 0) {
                current[existingIdx] = camera
            } else {
                current.add(camera)
            }
            repository.saveCameras(current)
        }
    }

    fun deleteCamera(cameraId: String) {
        viewModelScope.launch {
            val updated = cameras.value.filterNot { it.id == cameraId }
            repository.saveCameras(updated)
            if (_selectedCameraId.value == cameraId) {
                _selectedCameraId.value = null
            }
        }
    }

    fun startLanDiscovery() {
        if (_isScanningLan.value) return
        _isScanningLan.value = true
        _scanProgress.value = 0.02f
        _scanStatusText.value = "Iniciando escaneo de red Wi-Fi..."
        _discoveredDevices.value = emptyList()

        viewModelScope.launch {
            try {
                val results = NetworkCameraScanner.scanLocalNetwork(
                    onProgress = { progress, message ->
                        _scanProgress.value = progress
                        _scanStatusText.value = message
                    },
                    onDeviceFound = { device ->
                        val updated = (_discoveredDevices.value + device).distinctBy { it.ipAddress }
                        _discoveredDevices.value = updated
                    }
                )
                _discoveredDevices.value = results
            } finally {
                _isScanningLan.value = false
            }
        }
    }

    fun scanWaitingCameras(context: Context) {
        if (_isScanningWaitingCameras.value) return
        _isScanningWaitingCameras.value = true
        _provisioningStatusMessage.value = "Buscando cámaras nuevas en modo AP..."

        viewModelScope.launch {
            try {
                val aps = CameraProvisioningEngine.scanWaitingCameras(context)
                _waitingCameraAps.value = aps
                _provisioningStatusMessage.value = if (aps.isEmpty()) {
                    "No se detectaron cámaras en modo AP cerca. Si la cámara fue reseteada, espera 20s a que anuncie 'Access Point ready'."
                } else {
                    "Se encontraron ${aps.size} cámara(s) esperando ser configuradas."
                }
            } catch (e: Exception) {
                _provisioningStatusMessage.value = "Error escaneando cámaras: ${e.message}"
            } finally {
                _isScanningWaitingCameras.value = false
            }
        }
    }

    fun provisionWaitingCamera(
        cameraAp: WaitingCameraAp,
        homeSsid: String,
        homePass: String,
        onSuccess: (CameraConfig) -> Unit
    ) {
        if (_isProvisioningCamera.value) return
        _isProvisioningCamera.value = true
        _provisioningStatusMessage.value = "Enviando credenciales Wi-Fi a la cámara ${cameraAp.extractedDeviceId}..."

        viewModelScope.launch {
            try {
                val res = CameraProvisioningEngine.provisionCameraToHomeWifi(cameraAp, homeSsid, homePass)
                if (res.isSuccess) {
                    _provisioningStatusMessage.value = res.getOrNull()
                    val newConfig = CameraProvisioningEngine.createConfigFromWaitingCamera(cameraAp)
                    addOrUpdateCamera(newConfig)
                    onSuccess(newConfig)
                } else {
                    _provisioningStatusMessage.value = "Error: ${res.exceptionOrNull()?.message}"
                }
            } catch (e: Exception) {
                _provisioningStatusMessage.value = "Error al vincular: ${e.message}"
            } finally {
                _isProvisioningCamera.value = false
            }
        }
    }

    fun addWaitingCameraDirect(cameraAp: WaitingCameraAp, onDone: (CameraConfig) -> Unit) {
        val newConfig = CameraProvisioningEngine.createConfigFromWaitingCamera(cameraAp)
        addOrUpdateCamera(newConfig)
        onDone(newConfig)
    }

    fun clearProvisioningStatus() {
        _provisioningStatusMessage.value = null
    }

    fun sendPtzCommand(camera: CameraConfig, direction: PtzDirection) {
        viewModelScope.launch {
            _ptzFeedback.value = "Enviando comando PTZ (${direction.name})..."
            val result = CameraControlEngine.sendPtzStep(camera, direction)
            _ptzFeedback.value = result.fold(
                onSuccess = { it },
                onFailure = { it.message ?: "Error PTZ" }
            )
        }
    }

    fun sendV380AuxCommand(camera: CameraConfig, auxCommand: V380AuxCommand) {
        viewModelScope.launch {
            _ptzFeedback.value = "Enviando comando ${auxCommand.label}..."
            val result = CameraControlEngine.sendV380AuxCommand(camera, auxCommand)
            _ptzFeedback.value = result.fold(
                onSuccess = { it },
                onFailure = { it.message ?: "Error en comando V380" }
            )
        }
    }

    fun gotoPreset(camera: CameraConfig, presetIndex: Int) {
        viewModelScope.launch {
            _ptzFeedback.value = "Moviendo a Preset #$presetIndex..."
            val result = CameraControlEngine.gotoPreset(camera, presetIndex)
            _ptzFeedback.value = result.fold(
                onSuccess = { it },
                onFailure = { it.message ?: "Error en Preset" }
            )
        }
    }

    fun testCameraConnection(
        camera: CameraConfig,
        onResolvedUrl: (String) -> Unit
    ) {
        if (_isTestingConnection.value) return
        _isTestingConnection.value = true
        _diagnosticResult.value = null
        viewModelScope.launch {
            try {
                val result = CameraControlEngine.diagnoseCameraConnection(camera)
                _diagnosticResult.value = result
                result.resolvedStreamUrl?.let(onResolvedUrl)
            } finally {
                _isTestingConnection.value = false
            }
        }
    }

    fun clearDiagnostic() {
        _diagnosticResult.value = null
    }

    fun authenticateRingAccount(
        email: String,
        password: String,
        twoFactorCode: String,
        refreshToken: String
    ) {
        if (_isLoggingInRing.value) return
        _isLoggingInRing.value = true
        _ringStatusMessage.value = "Conectando con oauth.ring.com..."
        viewModelScope.launch {
            try {
                when (val outcome = RingCloudClient.authenticateAndFetchDevices(email, password, twoFactorCode, refreshToken)) {
                    is RingAuthOutcome.RequiresTwoFactor -> {
                        _ringNeedsTwoFactor.value = true
                        _ringStatusMessage.value = outcome.prompt
                    }
                    is RingAuthOutcome.Authenticated -> {
                        _ringNeedsTwoFactor.value = false
                        repository.saveRingRefreshToken(outcome.refreshToken)
                        _ringDiscoveredDevices.value = outcome.devices
                        _ringStatusMessage.value = "¡Conectado a Ring! ${outcome.devices.size} cámara(s)/timbre(s) encontrados."
                    }
                    is RingAuthOutcome.Error -> {
                        _ringStatusMessage.value = outcome.message
                    }
                }
            } finally {
                _isLoggingInRing.value = false
            }
        }
    }

    fun importRingDevice(device: RingCloudDevice, bridgeHostIp: String) {
        val cleanBridgeIp = bridgeHostIp.trim()
        val streamSlug = device.description.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
        val cfg = CameraConfig(
            id = "ring-${device.id}",
            name = "Ring ${device.description}",
            brand = CameraBrand.RING_CLOUD,
            connectionMode = ConnectionMode.CLOUD_DEVICE_ID,
            deviceId = device.id.toString(),
            modelCode = device.kind,
            host = cleanBridgeIp, // Empty host triggers native Ring Cloud direct live streaming
            rtspPort = 8554,
            httpPort = 1984,
            onvifPort = 8554,
            mainStreamPath = "/$streamSlug",
            subStreamPath = "/$streamSlug"
        )
        addOrUpdateCamera(cfg)
    }

    fun toggleMotionDetection(cameraId: String, enabled: Boolean, autoRecord: String? = null) {
        viewModelScope.launch {
            val list = cameras.value.map { cam ->
                if (cam.id == cameraId) {
                    cam.copy(
                        motionDetectionEnabled = enabled,
                        autoRecordMode = autoRecord ?: (if (enabled) "GIF" else "OFF")
                    )
                } else cam
            }
            repository.saveCameras(list)
        }
    }

    fun toggleLiveDashboard(camera: CameraConfig, active: Boolean) {
        viewModelScope.launch {
            repository.toggleLiveInDashboard(camera.id, active, cameras.value)
        }
    }

    fun exportCamerasToUri(context: Context, uri: Uri, onResult: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val jsonStr = repository.exportCamerasToJson(cameras.value)
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    os.write(jsonStr.toByteArray(Charsets.UTF_8))
                }
                withContext(Dispatchers.Main) {
                    onResult("Se exportaron ${cameras.value.size} cámaras correctamente")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onResult("Error al exportar cámaras: ${e.localizedMessage ?: "Error de escritura"}")
                }
            }
        }
    }

    fun importCamerasFromUri(context: Context, uri: Uri, onResult: (Result<Int>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val jsonStr = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                if (jsonStr.isNullOrBlank()) {
                    withContext(Dispatchers.Main) {
                        onResult(Result.failure(Exception("El archivo seleccionado está vacío")))
                    }
                    return@launch
                }
                val res = repository.importCamerasFromJson(jsonStr, cameras.value)
                withContext(Dispatchers.Main) {
                    onResult(res)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onResult(Result.failure(e))
                }
            }
        }
    }

    fun recordCameraStreamAccess(camera: CameraConfig) {
        viewModelScope.launch {
            val ip = localPhoneIp ?: "192.168.1.105"
            repository.recordAccess(
                cameraId = camera.id,
                clientIp = ip,
                clientDevice = "FrCam Android (${android.os.Build.MODEL})",
                connectionType = if (camera.isV380NativeProtocol()) "V380 Nativo H.264" else "Flujo RTSP",
                status = "Transmisión en vivo"
            )
        }
    }

    fun loadCameraAdministration(camera: CameraConfig) {
        viewModelScope.launch {
            _deviceInfo.value = CameraControlEngine.getDeviceInfo(camera)
            _adminStatusMessage.value = null
            repository.getAccessLogsFlow(camera.id).collectLatest { logs ->
                _accessLogs.value = logs
            }
        }
    }

    fun syncDeviceTime(camera: CameraConfig) {
        viewModelScope.launch {
            _adminStatusMessage.value = "Sincronizando reloj del dispositivo..."
            val result = CameraControlEngine.syncCameraTime(camera)
            _adminStatusMessage.value = result.fold(
                onSuccess = { it },
                onFailure = { it.message ?: "Error al sincronizar reloj" }
            )
            _deviceInfo.value = CameraControlEngine.getDeviceInfo(camera)
        }
    }

    fun updateCameraPassword(camera: CameraConfig, newPass: String) {
        viewModelScope.launch {
            _adminStatusMessage.value = "Actualizando contraseña en la cámara..."
            val result = CameraControlEngine.updateCameraPassword(camera, newPass)
            _adminStatusMessage.value = result.fold(
                onSuccess = { msg ->
                    // Also update in repository
                    addOrUpdateCamera(camera.copy(password = newPass))
                    msg
                },
                onFailure = { it.message ?: "Error al cambiar contraseña" }
            )
        }
    }

    fun updateWifiSettings(camera: CameraConfig, ssid: String, pass: String) {
        viewModelScope.launch {
            _adminStatusMessage.value = "Enviando nueva configuración Wi-Fi..."
            val result = CameraControlEngine.updateWifiSettings(camera, ssid, pass)
            _adminStatusMessage.value = result.fold(
                onSuccess = { it },
                onFailure = { it.message ?: "Error al configurar Wi-Fi" }
            )
            _deviceInfo.value = _deviceInfo.value?.copy(connectedWifiSsid = ssid)
        }
    }

    fun scanNearbyWifi(camera: CameraConfig) {
        if (_isScanningWifi.value) return
        _isScanningWifi.value = true
        viewModelScope.launch {
            try {
                _adminStatusMessage.value = "Buscando redes Wi-Fi cercanas..."
                val networks = CameraControlEngine.scanWifi(camera)
                _wifiScanList.value = networks
                _adminStatusMessage.value = "${networks.size} redes Wi-Fi detectadas por la cámara"
            } finally {
                _isScanningWifi.value = false
            }
        }
    }

    fun updateIpSettings(
        camera: CameraConfig,
        isDhcp: Boolean,
        ip: String,
        netmask: String,
        gateway: String,
        dns: String
    ) {
        viewModelScope.launch {
            _adminStatusMessage.value = "Guardando configuración de red IP..."
            val result = CameraControlEngine.updateIpSettings(camera, isDhcp, ip, netmask, gateway, dns)
            _adminStatusMessage.value = result.fold(
                onSuccess = { msg ->
                    addOrUpdateCamera(
                        camera.copy(
                            isDhcp = isDhcp,
                            staticIp = ip,
                            staticNetmask = netmask,
                            staticGateway = gateway,
                            staticDns = dns,
                            host = if (!isDhcp && ip.isNotBlank()) ip else camera.host
                        )
                    )
                    _deviceInfo.value = _deviceInfo.value?.copy(
                        isDhcp = isDhcp,
                        ipAddress = if (!isDhcp && ip.isNotBlank()) ip else _deviceInfo.value?.ipAddress ?: ip,
                        netmask = netmask,
                        gateway = gateway,
                        dns = dns
                    )
                    msg
                },
                onFailure = { it.message ?: "Error al configurar IP" }
            )
        }
    }

    fun toggleVoicePrompts(camera: CameraConfig, enabled: Boolean) {
        viewModelScope.launch {
            val cmd = if (enabled) V380AuxCommand.VOICE_PROMPT_ON else V380AuxCommand.VOICE_PROMPT_OFF
            val result = CameraControlEngine.sendV380AuxCommand(camera, cmd)
            addOrUpdateCamera(camera.copy(voicePromptsEnabled = enabled))
            _deviceInfo.value = _deviceInfo.value?.copy(voicePromptsEnabled = enabled)
            _adminStatusMessage.value = if (enabled) "Mensajes de voz activados" else "Mensajes de voz silenciados"
        }
    }

    fun toggleMotionTracking(camera: CameraConfig, enabled: Boolean) {
        viewModelScope.launch {
            val cmd = if (enabled) V380AuxCommand.TRACKING_ON else V380AuxCommand.TRACKING_OFF
            CameraControlEngine.sendV380AuxCommand(camera, cmd)
            addOrUpdateCamera(camera.copy(motionTrackingEnabled = enabled))
            _deviceInfo.value = _deviceInfo.value?.copy(motionTrackingEnabled = enabled)
            _adminStatusMessage.value = if (enabled) "Seguimiento inteligente activado" else "Seguimiento desactivado"
        }
    }

    fun togglePtzPatrol(camera: CameraConfig, active: Boolean) {
        viewModelScope.launch {
            val cmd = if (active) V380AuxCommand.PATROL_HORIZONTAL else V380AuxCommand.PTZ_STOP
            CameraControlEngine.sendV380AuxCommand(camera, cmd)
            addOrUpdateCamera(camera.copy(ptzPatrolEnabled = active))
            _deviceInfo.value = _deviceInfo.value?.copy(ptzPatrolActive = active)
            _adminStatusMessage.value = if (active) "Modo patrulla crucero 360° iniciado" else "Patrulla detenida"
        }
    }

    fun formatCameraSdCard(camera: CameraConfig) {
        viewModelScope.launch {
            _adminStatusMessage.value = "Formateando tarjeta MicroSD en la cámara..."
            val result = CameraControlEngine.formatSdCard(camera)
            _adminStatusMessage.value = result.fold(
                onSuccess = { msg ->
                    _deviceInfo.value = _deviceInfo.value?.copy(
                        sdCardFreeMb = _deviceInfo.value?.sdCardTotalMb ?: 61440L,
                        sdCardStatus = "Formateada (0 GB usados)"
                    )
                    msg
                },
                onFailure = { it.message ?: "Error al formatear MicroSD" }
            )
        }
    }

    fun rebootCamera(camera: CameraConfig) {
        viewModelScope.launch {
            _adminStatusMessage.value = "Enviando orden de reinicio..."
            val result = CameraControlEngine.rebootCamera(camera)
            _adminStatusMessage.value = result.fold(
                onSuccess = { it },
                onFailure = { it.message ?: "Error al reiniciar" }
            )
        }
    }

    fun factoryResetCamera(camera: CameraConfig) {
        viewModelScope.launch {
            _adminStatusMessage.value = "Restableciendo cámara a valores de fábrica..."
            val result = CameraControlEngine.factoryResetCamera(camera)
            _adminStatusMessage.value = result.fold(
                onSuccess = { it },
                onFailure = { it.message ?: "Error al restablecer" }
            )
        }
    }

    fun clearAdminStatus() {
        _adminStatusMessage.value = null
    }

    fun buildConfigFromDiscovered(
        device: DiscoveredCameraDevice,
        username: String = device.detectedBrand.defaultUsername,
        password: String = ""
    ): CameraConfig {
        val mode = if (device.deviceId.isNotBlank() || device.remoteHost.isNotBlank()) {
            ConnectionMode.HYBRID_AUTO
        } else {
            ConnectionMode.LAN_IP
        }
        return CameraConfig(
            name = device.deviceName,
            brand = device.detectedBrand,
            connectionMode = mode,
            deviceId = device.deviceId,
            host = device.ipAddress,
            remoteHost = device.remoteHost,
            remoteRtspPort = device.remoteRtspPort,
            remoteHttpPort = device.remoteHttpPort,
            upnpMapped = device.upnpMapped,
            rtspPort = device.rtspPort,
            httpPort = device.httpPort,
            onvifPort = device.onvifPort,
            username = username,
            password = password,
            mainStreamPath = device.suggestedMainPath,
            subStreamPath = device.suggestedSubPath,
            protocol = device.detectedBrand.defaultProtocol,
            ptzType = device.detectedBrand.defaultPtzType
        )
    }
}

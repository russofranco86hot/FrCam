package com.frcam.app

import android.Manifest
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.frcam.app.data.AppSettings
import com.frcam.app.media.CameraMediaEngine
import com.frcam.app.model.CameraBrand
import com.frcam.app.model.CameraConfig
import com.frcam.app.model.GridLayoutMode
import com.frcam.app.ui.screens.AddEditCameraSheet
import com.frcam.app.ui.screens.AppSettingsDialog
import com.frcam.app.ui.screens.CameraDashboardScreen
import com.frcam.app.ui.screens.LanDiscoveryDialog
import com.frcam.app.ui.screens.MediaGalleryDialog
import com.frcam.app.ui.screens.MotionEventsHistoryDialog
import com.frcam.app.ui.screens.RingCloudLoginDialog
import com.frcam.app.ui.screens.SingleCameraDetailScreen
import com.frcam.app.ui.theme.FrCamTheme
import com.frcam.app.viewmodel.CameraViewModel
import com.frcam.app.wear.PhoneWearSyncEngine
import com.google.android.gms.wearable.MessageClient

class MainActivity : ComponentActivity() {

    private val viewModel: CameraViewModel by viewModels()
    private var multicastLock: WifiManager.MulticastLock? = null
    private val isInPipModeState = mutableStateOf(false)
    private var wearMessageListener: MessageClient.OnMessageReceivedListener? = null

    override fun onStart() {
        super.onStart()
        wearMessageListener = PhoneWearSyncEngine.registerListener(this) {
            viewModel.cameras.value
        }
    }

    override fun onStop() {
        wearMessageListener?.let { PhoneWearSyncEngine.unregisterListener(this, it) }
        wearMessageListener = null
        super.onStop()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipModeState.value = isInPictureInPictureMode
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        AppSettings.init(this)
        acquireWifiMulticastLock()

        handleNotificationIntent(intent)

        setContent {
            FrCamTheme {
                // Runtime Permissions (Microphone for Intercom and Notifications for Motion Alerts)
                val permissionsToRequest = remember {
                    val list = mutableListOf(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        list.add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    list.toTypedArray()
                }
                val multiplePermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) {}
                LaunchedEffect(Unit) {
                    val missing = permissionsToRequest.filter {
                        ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED
                    }
                    if (missing.isNotEmpty()) {
                        multiplePermissionLauncher.launch(missing.toTypedArray())
                    }
                }

                val cameras by viewModel.cameras.collectAsState()
                val gridMode by viewModel.gridMode.collectAsState()
                val selectedCameraId by viewModel.selectedCameraId.collectAsState()
                val isScanningLan by viewModel.isScanningLan.collectAsState()
                val scanProgress by viewModel.scanProgress.collectAsState()
                val scanStatusText by viewModel.scanStatusText.collectAsState()
                val discoveredDevices by viewModel.discoveredDevices.collectAsState()
                val ptzFeedback by viewModel.ptzFeedback.collectAsState()
                val diagnosticResult by viewModel.diagnosticResult.collectAsState()
                val isTestingConnection by viewModel.isTestingConnection.collectAsState()

                val ringRefreshToken by viewModel.ringRefreshToken.collectAsState()
                val isLoggingInRing by viewModel.isLoggingInRing.collectAsState()
                val ringStatusMessage by viewModel.ringStatusMessage.collectAsState()
                val ringNeedsTwoFactor by viewModel.ringNeedsTwoFactor.collectAsState()
                val ringDiscoveredDevices by viewModel.ringDiscoveredDevices.collectAsState()

                val deviceInfo by viewModel.deviceInfo.collectAsState()
                val accessLogs by viewModel.accessLogs.collectAsState()
                val wifiScanList by viewModel.wifiScanList.collectAsState()
                val isScanningWifi by viewModel.isScanningWifi.collectAsState()
                val adminStatusMessage by viewModel.adminStatusMessage.collectAsState()
                val motionEvents by CameraMediaEngine.motionEvents.collectAsState()

                var showAddEditSheet by remember { mutableStateOf(false) }
                var editingCamera by remember { mutableStateOf<CameraConfig?>(null) }
                var showScannerDialog by remember { mutableStateOf(false) }
                var showRingDialog by remember { mutableStateOf(false) }
                var showMotionHistoryDialog by remember { mutableStateOf(false) }
                var showSettingsDialog by remember { mutableStateOf(false) }
                var showGalleryDialog by remember { mutableStateOf(false) }

                val activeDetailCamera = cameras.find { it.id == selectedCameraId }

                LaunchedEffect(activeDetailCamera?.id) {
                    if (activeDetailCamera != null) {
                        viewModel.recordCameraStreamAccess(activeDetailCamera)
                    }
                }

                // Sincronizar automáticamente con el reloj Wear OS ante cambios en la lista de cámaras
                LaunchedEffect(cameras) {
                    if (cameras.isNotEmpty()) {
                        PhoneWearSyncEngine.syncCamerasToWatch(this@MainActivity, cameras)
                    }
                }

                if (activeDetailCamera != null) {
                    BackHandler {
                        viewModel.selectCamera(null)
                    }
                    SingleCameraDetailScreen(
                        camera = activeDetailCamera,
                        isInPipMode = isInPipModeState.value,
                        ptzFeedback = ptzFeedback,
                        deviceInfo = deviceInfo,
                        accessLogs = accessLogs,
                        wifiScanList = wifiScanList,
                        isScanningWifi = isScanningWifi,
                        adminStatusMessage = adminStatusMessage,
                        onBack = { viewModel.selectCamera(null) },
                        onEdit = { cam ->
                            viewModel.clearDiagnostic()
                            editingCamera = cam
                            showAddEditSheet = true
                        },
                        onDelete = { id ->
                            viewModel.deleteCamera(id)
                        },
                        onToggleMotionAlert = { enabled ->
                            viewModel.toggleMotionDetection(activeDetailCamera.id, enabled)
                        },
                        onPtzCommand = { dir ->
                            viewModel.sendPtzCommand(activeDetailCamera, dir)
                        },
                        onV380AuxCommand = { aux ->
                            viewModel.sendV380AuxCommand(activeDetailCamera, aux)
                        },
                        onGotoPreset = { preset ->
                            viewModel.gotoPreset(activeDetailCamera, preset)
                        },
                        onEnterPipMode = {
                            enterCameraPipMode()
                        },
                        onOpenAdmin = {
                            viewModel.loadCameraAdministration(activeDetailCamera)
                        },
                        onSyncTime = {
                            viewModel.syncDeviceTime(activeDetailCamera)
                        },
                        onUpdatePassword = { newPass ->
                            viewModel.updateCameraPassword(activeDetailCamera, newPass)
                        },
                        onUpdateWifi = { ssid, pass ->
                            viewModel.updateWifiSettings(activeDetailCamera, ssid, pass)
                        },
                        onScanWifi = {
                            viewModel.scanNearbyWifi(activeDetailCamera)
                        },
                        onUpdateIp = { isDhcp, ip, mask, gw, dns ->
                            viewModel.updateIpSettings(activeDetailCamera, isDhcp, ip, mask, gw, dns)
                        },
                        onToggleVoicePrompts = { enabled ->
                            viewModel.toggleVoicePrompts(activeDetailCamera, enabled)
                        },
                        onToggleMotionTracking = { enabled ->
                            viewModel.toggleMotionTracking(activeDetailCamera, enabled)
                        },
                        onTogglePtzPatrol = { active ->
                            viewModel.togglePtzPatrol(activeDetailCamera, active)
                        },
                        onFormatSdCard = {
                            viewModel.formatCameraSdCard(activeDetailCamera)
                        },
                        onReboot = {
                            viewModel.rebootCamera(activeDetailCamera)
                        },
                        onFactoryReset = {
                            viewModel.factoryResetCamera(activeDetailCamera)
                        },
                        onUpdateCamera = { updatedCam ->
                            viewModel.addOrUpdateCamera(updatedCam)
                        }
                    )
                } else {
                    CameraDashboardScreen(
                        cameras = cameras,
                        gridMode = gridMode,
                        localPhoneIp = viewModel.localPhoneIp,
                        onToggleGridMode = {
                            val next = when (gridMode) {
                                GridLayoutMode.SINGLE -> GridLayoutMode.GRID_2X2
                                GridLayoutMode.GRID_2X2 -> GridLayoutMode.GRID_3X3
                                GridLayoutMode.GRID_3X3 -> GridLayoutMode.SINGLE
                            }
                            viewModel.setGridMode(next)
                        },
                        onOpenScanner = {
                            showScannerDialog = true
                            viewModel.startLanDiscovery()
                        },
                        onOpenRingLogin = {
                            showRingDialog = true
                        },
                        onOpenMotionHistory = {
                            showMotionHistoryDialog = true
                        },
                        onOpenSettings = {
                            showSettingsDialog = true
                        },
                        onOpenGallery = {
                            showGalleryDialog = true
                        },
                        onAddCamera = { preselectedBrand: CameraBrand? ->
                            viewModel.clearDiagnostic()
                            val defaultBrand = preselectedBrand ?: CameraBrand.V380_PRO
                            val subnetPrefix = viewModel.localPhoneIp?.substringBeforeLast(".") ?: "192.168.1"
                            editingCamera = CameraConfig(
                                name = "Cámara ${defaultBrand.displayName}",
                                brand = defaultBrand,
                                connectionMode = defaultBrand.defaultConnectionMode,
                                host = if (defaultBrand == CameraBrand.V380_PRO) "" else "$subnetPrefix.",
                                rtspPort = defaultBrand.defaultRtspPort,
                                httpPort = defaultBrand.defaultHttpPort,
                                onvifPort = defaultBrand.defaultOnvifPort,
                                username = defaultBrand.defaultUsername,
                                mainStreamPath = defaultBrand.defaultMainPath,
                                subStreamPath = defaultBrand.defaultSubPath,
                                protocol = defaultBrand.defaultProtocol,
                                ptzType = defaultBrand.defaultPtzType
                            )
                            showAddEditSheet = true
                        },
                        onSelectCamera = { cam ->
                            viewModel.selectCamera(cam.id)
                        },
                        onEditCamera = { cam ->
                            viewModel.clearDiagnostic()
                            editingCamera = cam
                            showAddEditSheet = true
                        },
                        onDeleteCamera = { cam ->
                            viewModel.deleteCamera(cam.id)
                        },
                        onToggleLiveDashboard = { cam, active ->
                            viewModel.toggleLiveDashboard(cam, active)
                        },
                        onToggleMotion = { cam, enabled ->
                            viewModel.toggleMotionDetection(cam.id, enabled)
                        },
                        onExportCameras = { ctx, uri, callback ->
                            viewModel.exportCamerasToUri(ctx, uri, callback)
                        },
                        onImportCameras = { ctx, uri, callback ->
                            viewModel.importCamerasFromUri(ctx, uri, callback)
                        }
                    )
                }

                if (showAddEditSheet) {
                    AddEditCameraSheet(
                        initialCamera = editingCamera,
                        isTestingConnection = isTestingConnection,
                        diagnosticResult = diagnosticResult,
                        onTestConnection = { cfg, onResolved ->
                            viewModel.testCameraConnection(cfg, onResolved)
                        },
                        onSave = { updated ->
                            viewModel.addOrUpdateCamera(updated)
                        },
                        onDelete = { camId ->
                            viewModel.deleteCamera(camId)
                            showAddEditSheet = false
                            editingCamera = null
                        },
                        onDismiss = {
                            showAddEditSheet = false
                            editingCamera = null
                        }
                    )
                }

                if (showScannerDialog) {
                    LanDiscoveryDialog(
                        localPhoneIp = viewModel.localPhoneIp,
                        isScanning = isScanningLan,
                        scanProgress = scanProgress,
                        scanStatusText = scanStatusText,
                        discoveredDevices = discoveredDevices,
                        onStartScan = { viewModel.startLanDiscovery() },
                        onSelectDevice = { discovered ->
                            showScannerDialog = false
                            viewModel.clearDiagnostic()
                            editingCamera = viewModel.buildConfigFromDiscovered(discovered)
                            showAddEditSheet = true
                        },
                        onDismiss = {
                            showScannerDialog = false
                        }
                    )
                }

                if (showRingDialog) {
                    RingCloudLoginDialog(
                        savedRefreshToken = ringRefreshToken,
                        isLoggingIn = isLoggingInRing,
                        statusMessage = ringStatusMessage,
                        needsTwoFactor = ringNeedsTwoFactor,
                        discoveredDevices = ringDiscoveredDevices,
                        onAuthenticate = { email, pass, code, token ->
                            viewModel.authenticateRingAccount(email, pass, code, token)
                        },
                        onImportDevice = { dev, bridgeHost ->
                            viewModel.importRingDevice(dev, bridgeHost)
                            showRingDialog = false
                        },
                        onDismiss = {
                            showRingDialog = false
                        }
                    )
                }

                if (showMotionHistoryDialog) {
                    MotionEventsHistoryDialog(
                        events = motionEvents,
                        onDismiss = { showMotionHistoryDialog = false },
                        onSelectCamera = { camId: String ->
                            viewModel.selectCamera(camId)
                        }
                    )
                }

                if (showSettingsDialog) {
                    AppSettingsDialog(
                        cameras = cameras,
                        onDismiss = { showSettingsDialog = false }
                    )
                }

                if (showGalleryDialog) {
                    MediaGalleryDialog(
                        onDismiss = { showGalleryDialog = false }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNotificationIntent(intent)
    }

    private fun handleNotificationIntent(intent: Intent?) {
        val targetCamId = intent?.getStringExtra("open_camera_id")
        if (!targetCamId.isNullOrBlank()) {
            viewModel.selectCamera(targetCamId)
        }
    }

    private fun enterCameraPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)
        }
    }

    private fun acquireWifiMulticastLock() {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifiManager?.createMulticastLock("FrCamOnvifDiscoveryLock")?.apply {
                setReferenceCounted(true)
                acquire()
            }
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}

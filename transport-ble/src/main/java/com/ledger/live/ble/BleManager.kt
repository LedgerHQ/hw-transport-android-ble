package com.ledger.live.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelUuid
import com.ledger.live.ble.callback.BleManagerConnectionCallback
import com.ledger.live.ble.callback.BleManagerDisconnectionCallback
import com.ledger.live.ble.callback.BleManagerSendCallback
import com.ledger.live.ble.extension.fromHexStringToBytes
import com.ledger.live.ble.model.BleDeviceModel
import com.ledger.live.ble.model.BleEvent
import com.ledger.live.ble.model.BleState
import com.ledger.live.ble.service.BleService
import com.ledger.live.ble.service.model.BleServiceEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import timber.log.Timber
import java.util.*

@SuppressLint("MissingPermission")
class BleManager internal constructor(
    private val context: Context
) {
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    private var isScanning: Boolean = false
    private val _bleState = MutableSharedFlow<BleState>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_LATEST,
        extraBufferCapacity = 10
    )

    val bleState: Flow<BleState>
        get() = _bleState

    //TODO improve events flow
    private val _bleEvents = MutableSharedFlow<BleEvent>()
    val bleEvents: Flow<BleEvent>
        get() = _bleEvents

    private val bluetoothAdapter by lazy {
        context.getSystemService(BluetoothManager::class.java).adapter
    }

    private val bluetoothScanner by lazy {
        bluetoothAdapter.bluetoothLeScanner
    }

    private var scannedDevices: MutableList<BleDeviceModel> = mutableListOf()
    private val scanCallback: ScanCallback = object : ScanCallback() {
        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            Timber.d("Batch result")
            var added = false
            results?.let {
                results.forEach { res ->
                    val device = parseScanResult(res)
                    if (device != null && scannedDevices.find {scannedDevice -> scannedDevice.id == device.id } == null) {
                        scannedDevices.add(device)
                        onScanDevicesCallback?.invoke(scannedDevices)
                        added = true
                    }
                }
            }

            if (added) {
                onScanDevicesCallback?.invoke(scannedDevices)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Timber.d("Bluetooth scan failed $errorCode")
            //TODO HANDLE ERROR IN BLESCANCALLBACK
        }

        override fun onScanResult(callbackType: Int, result: ScanResult) {
            when (callbackType) {
                ScanSettings.CALLBACK_TYPE_ALL_MATCHES,
                ScanSettings.CALLBACK_TYPE_FIRST_MATCH -> {
                    Timber.d("Scan result => FIRST_MATCH")
                    val device = parseScanResult(result)
                    if (device != null && scannedDevices.find { it.id == device.id } == null) {
                        scannedDevices.add(device)
                        onScanDevicesCallback?.invoke(scannedDevices)
                    }
                }
                ScanSettings.CALLBACK_TYPE_MATCH_LOST -> {
                    Timber.d("Scan result => Lost")
                    if (scannedDevices.removeIf { it.id == result.device.address }) {
                        onScanDevicesCallback?.invoke(scannedDevices)
                    }
                }
            }

            Timber.d("Scan Devices $scannedDevices")
        }
    }

    var pollingJob: Job? = null
    var onScanDevicesCallback: ((List<BleDeviceModel>) -> Unit)? = null

    private fun parseScanResult(result: ScanResult): BleDeviceModel? {
        val device = result.device
        val rssi = result.rssi
        val uuids = getServiceUUIDsList(result)
        val name = device.name

        return if (name != null && uuids.isNotEmpty()) {
            Timber.d("Scan result device => \n id: ${device.address} \n name: $name \n serviceId : ${uuids.first()}")
            BleDeviceModel(
                id = device.address,
                name = name,
                serviceId = uuids.first().toString(),
                rssi = rssi
            )
        } else null
    }

    private fun getServiceUUIDsList(scanResult: ScanResult): List<UUID> {
        val parcelUuids = scanResult.scanRecord!!.serviceUuids
        val serviceList: MutableList<UUID> = ArrayList()
        for (i in parcelUuids.indices) {
            val serviceUUID = parcelUuids[i].uuid
            if (!serviceList.contains(serviceUUID)) serviceList.add(serviceUUID)
        }
        return serviceList
    }

    /**
     * Use bleState for getting informations about running scan
     */
    fun startScanning(): Boolean {
        return internalStartScanning()
    }

    fun startScanning(
        onScanDevices: (List<BleDeviceModel>) -> Unit
    ): Boolean {
        Timber.d("Start Scanning")
        onScanDevicesCallback = onScanDevices
        return internalStartScanning()
    }

    /**
     * Start a new scanning session
     *
     * Stop current device connection if exists
     * Stop
     */
    private fun internalStartScanning(): Boolean {
        //Assure to stop every runnning scan or active connection
        disconnect()
        stopScanning()

        isScanning = true

        val filters = mutableListOf<ScanFilter>()
        //Filter NanoX service
        filters.add(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(UUID.fromString(NANO_X_SERVICE_UUID)))
                .build()
        )

        //Filter Faststack service
        filters.add(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(UUID.fromString(NANO_FTS_SERVICE_UUID)))
                .build()
        )

        scannedDevices = mutableListOf()

        val builder = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)

        val scanSettings = builder.build()
        bluetoothScanner.startScan(filters, scanSettings, scanCallback)

        //Expose scanned device list every second
        if (pollingJob == null) {
            pollingJob = scope.launch() {
                while (true) {
                    _bleState.tryEmit(BleState.Scanning(scannedDevices = scannedDevices))
                    delay(SCAN_THROTTLE_MS)
                }
            }
        }

        return true
    }

    fun stopScanning() {
        Timber.d("Stop Scanning")
        pollingJob?.cancel()
        pollingJob = null
        bluetoothScanner.stopScan(scanCallback)
        isScanning = false
    }

    private var connectionCallback: BleManagerConnectionCallback? = null
    @Volatile
    private var connectingJob: Job? = null

    //Ensure only one connection is tried at a time
    @Synchronized
    fun connect(
        address: String,
        onConnectSuccess: (BleDeviceModel) -> Unit,
        onConnectError: (String) -> Unit
    ) {
        val callback = object : BleManagerConnectionCallback {
            override fun onConnectionSuccess(device: BleDeviceModel) {
                isConnected = true
                onConnectSuccess(device)
            }

            override fun onConnectionError(message: String) {
                onConnectError(message)
            }
        }

        if (connectingJob == null
            || connectingJob?.isCancelled == true
            || connectingJob?.isCompleted == true
        ) {
            connectingJob = scope.launch {
                internalConnect(address, callback)
            }
        }
    }

    /**
     * Use Event Flow for connection callback
     */
    @Synchronized
    fun connect(address: String) {
        if (connectingJob == null
            || connectingJob?.isCancelled == true
            || connectingJob?.isCompleted == true
        ) {
            connectingJob = scope.launch {
                internalConnect(address)
            }
        }
    }

    @Synchronized
    private suspend fun internalConnect(
        address: String,
        callback: BleManagerConnectionCallback? = null
    ) {
        Timber.d("($this) - Try Connecting to device with address $address")
        stopScanning()
        internalDisconnect()

        connectionCallback = callback

        val device = scannedDevices.firstOrNull { it.id == address }
            ?: bluetoothAdapter.bondedDevices.firstOrNull {
                it.address == address
            }?.let {
                BleDeviceModel(
                    id = it.address,
                    name = it.name,
                    serviceId = it.uuids?.first()?.uuid.toString(),
                )
            }

        device?.let {
            connectedDevice = it
            val gattServiceIntent = Intent(context, BleService::class.java)
            context.bindService(gattServiceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        } ?: run {
            connectionCallback?.onConnectionError("Device not found")
            _bleEvents.tryEmit(BleEvent.BleError.ConnectionError("Device not found"))
        }
    }

    //- Disconnect
    private var disconnectionCallback: BleManagerDisconnectionCallback? = null
    @Volatile
    private var disconnectingJob: Job? = null

    @Synchronized
    fun disconnect(
        onDisconnectSuccess: () -> Unit,
    ) {
        Timber.d("Called disconnect")
        disconnectionCallback = object : BleManagerDisconnectionCallback {
            override fun onDisconnectionSuccess() {
                onDisconnectSuccess()
            }
        }

        if (disconnectingJob == null
            || disconnectingJob?.isCancelled == true
            || disconnectingJob?.isCompleted == true
        ) {
            disconnectingJob = scope.launch {
                internalDisconnect()
            }
        }
    }

    @Synchronized
    fun disconnect() {
        Timber.d("Called disconnect")

        if (disconnectingJob == null
            || disconnectingJob?.isCancelled == true
            || disconnectingJob?.isCompleted == true
        ) {
            disconnectingJob = scope.launch {
                internalDisconnect()
            }
        }
    }

    private var disconnectingDeferred: CompletableDeferred<Boolean>? = null

    @Synchronized
    private suspend fun internalDisconnect() {
        Timber.d("internal Disconnect")
        if (disconnectingDeferred == null
            || disconnectingDeferred?.isCompleted == true
            || disconnectingDeferred?.isCancelled == true
        ) {
            if (bluetoothService != null && bluetoothService!!.isBound) {
                disconnectingDeferred = CompletableDeferred()
                context.unbindService(serviceConnection)
                disconnectingDeferred!!.await()
            }
        }
    }

    fun send(
        apdu: ByteArray,
    ) {
        bluetoothService?.sendApdu(apdu) ?: run {
            throw IllegalStateException("Bluetooth service not connected, please use connect before")
        }
    }

    private val pendingSendRequest = mutableListOf<BleManagerSendCallback>()
    fun send(
        apduHex: String,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        val id = bluetoothService?.sendApdu(apduHex.fromHexStringToBytes()) ?: run {
            throw IllegalStateException("Bluetooth service not connected, please use connect before")
        }

        pendingSendRequest.add(
            BleManagerSendCallback(
                id = id,
                onSuccess = onSuccess,
                onError = onError
            )
        )
    }

    // Bluetooth Service lifecycle.
    private var bluetoothService: BleService? = null
    private lateinit var connectedDevice: BleDeviceModel
    var isConnected: Boolean = false
        private set

    private val serviceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(componentName: ComponentName, service: IBinder) {
            Timber.d("Connected to BleService !")
            bluetoothService = (service as BleService.LocalBinder).service
            bluetoothService?.let { bleService ->
                if (!bleService.initialize()) {
                    Timber.e("Unable to initialize Bluetooth")
                    connectionCallback?.onConnectionError("Couldn't initialize the BLE service")
                    bleService.disconnectService()
                } else {
                    bleService.connect(connectedDevice.id)
                    scope.launch {
                        bleService.listenEvents().collect { event ->
                            when (event) {
                                is BleServiceEvent.BleDeviceConnected -> {
                                    connectionCallback?.onConnectionSuccess(connectedDevice)
                                    _bleState.tryEmit(BleState.Connected(connectedDevice))
                                }
                                is BleServiceEvent.BleDeviceDisconnected -> {
                                    disconnected()
                                    _bleState.tryEmit(BleState.Disconnected)
                                }
                                is BleServiceEvent.SuccessSend -> {
                                    _bleEvents.tryEmit(BleEvent.SendingEvent.SendSuccess(event.sendId))
                                }
                                is BleServiceEvent.SendAnswer -> {
                                    pendingSendRequest.firstOrNull { it.id == event.sendId }
                                        ?.let { callback ->
                                            callback.onSuccess(event.answer)
                                        }
                                }
                                is BleServiceEvent.ErrorSend -> {
                                    _bleEvents.tryEmit(BleEvent.BleError.SendError(event.error))
                                    pendingSendRequest.firstOrNull { it.id == event.sendId }
                                        ?.let { callback ->
                                            callback.onError(event.error)
                                        }
                                }
                                else -> Timber.d("Event not handle $event")
                            }
                        }
                    }
                }
            }
        }

        override fun onServiceDisconnected(componentName: ComponentName) {
            Timber.d("BleService disconnected unexpectedly")
        }
    }

    private fun disconnected() {
        Timber.d("BleService disconnected")
        if (bluetoothService?.isBound == true) {
            context.unbindService(serviceConnection)
        } else {
            //Only Call disconnection or error
            disconnectionCallback?.onDisconnectionSuccess()
                ?: connectionCallback?.onConnectionError("Device connection lost")
            disconnectionCallback = null
            connectionCallback = null
            bluetoothService = null
            isConnected = false
            disconnectingDeferred?.complete(true)
        }
    }

    companion object {

        private const val SCAN_THROTTLE_MS = 1000L
        const val NANO_X_SERVICE_UUID = "13D63400-2C97-0004-0000-4C6564676572"
        const val NANO_FTS_SERVICE_UUID = "13d63400-2c97-6004-0000-4c6564676572"

        const val nanoXNotifyCharacteristicUUID = "13d63400-2c97-0004-0001-4c6564676572"
        const val nanoXWriteWithResponseCharacteristicUUID = "13d63400-2c97-0004-0002-4c6564676572"
        const val nanoXWriteWithoutResponseCharacteristicUUID =
            "13d63400-2c97-0004-0003-4c6564676572"

        const val nanoFTSNotifyCharacteristicUUID = "13d63400-2c97-6004-0001-4c6564676572"
        const val nanoFTSWriteWithResponseCharacteristicUUID =
            "13d63400-2c97-6004-0002-4c6564676572"
        const val nanoFTSWriteWithoutResponseCharacteristicUUID =
            "13d63400-2c97-6004-0003-4c6564676572"
    }
}
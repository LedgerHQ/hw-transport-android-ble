package com.ledger.live.ble.service

import android.annotation.SuppressLint
import android.app.Service
import android.bluetooth.*
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.ledger.live.ble.*
import com.ledger.live.ble.extension.fromHexStringToBytes
import com.ledger.live.ble.extension.toHexString
import com.ledger.live.ble.extension.toUUID
import com.ledger.live.ble.model.BleCommand
import com.ledger.live.ble.model.BleDeviceService
import com.ledger.live.ble.model.BleState
import com.ledger.live.ble.model.FrameCommand
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import timber.log.Timber
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

@SuppressLint("MissingPermission")
class BleService : Service() {

    //Service related
    inner class LocalBinder : Binder() {
        val service: BleService
            get() = this@BleService
    }

    private var timeoutJob: Job? = null
    private val binder: IBinder = LocalBinder()
    var isBound = false
    override fun onBind(intent: Intent): IBinder {
        isBound = true
        initialize()
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Timber.d("Unbind service")
        disconnectService()
        isBound = false
        return super.onUnbind(intent)
    }

    //Bluetooth related
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bluetoothGATT: BluetoothGatt? = null
    private var bluetoothDeviceAddress: String? = null
    private var connectionState: Int = BLE_STATE_DISCONNECTED

    //TODO improve
    private var deviceServices: MutableList<BleDeviceService> = mutableListOf()
    private var mtuSize: Int = 0

    private val events: MutableSharedFlow<BleServiceEvent> = MutableSharedFlow(0, 1)

    fun initialize(): Boolean {
        return try {
            val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
            bluetoothAdapter = bluetoothManager.adapter
            connectionState = BLE_STATE_CONNECTING
            true
        } catch (exception: Exception) {
            false
        }
    }

    fun disconnectService() {
        bluetoothGATT?.close()
        bluetoothGATT?.disconnect()

        bluetoothGATT = null
        mtuSize = 0
        deviceServices.clear()
        commandQueue.clear()
        pendingCommand = null
        pendingAnswers.clear()
        pendingApdu.clear()

        stopSelf()
        notify(BleServiceEvent.BleDeviceDisconnected)
    }

    private val gattCallback: BluetoothGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Timber.d("GATT connection state change. state: $newState, status: $status")

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Timber.d("CONNECTED")
                    connectionState = BLE_STATE_CONNECTED
                    gatt.discoverServices() //Needed for considering really connected (services discovery)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Timber.i("Disconnected from GATT server.")
                    connectionState = BLE_STATE_DISCONNECTED
                    disconnectService()
                }
            }
        }

        override fun onServicesDiscovered(
            gatt: BluetoothGatt,
            status: Int
        ) {
            Timber.d("------------- onServicesDiscovered status: $status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                gatt.services.forEach { service ->
                    Timber.d("Service UUID ${service.uuid}")

                    if (service.uuid == BleManager.NANO_X_SERVICE_UUID.toUUID()
                        || service.uuid == BleManager.NANO_FTS_SERVICE_UUID.toUUID()
                    ) {
                        Timber.d("Service UUID ${service.uuid}")

                        val bleServiceBuilder: BleDeviceService.Builder =
                            BleDeviceService.Builder(service.uuid)
                        service.characteristics.forEach { characteristic ->
                            when (characteristic.uuid) {
                                BleManager.nanoXWriteWithResponseCharacteristicUUID.toUUID(),
                                BleManager.nanoFTSWriteWithResponseCharacteristicUUID.toUUID() -> {
                                    bleServiceBuilder.setWriteCharacteristic(characteristic)
                                }
                                BleManager.nanoXWriteWithoutResponseCharacteristicUUID.toUUID(),
                                BleManager.nanoFTSWriteWithoutResponseCharacteristicUUID.toUUID() -> {
                                    bleServiceBuilder.setWriteNoAnswerCharacteristic(characteristic)
                                }
                                BleManager.nanoXNotifyCharacteristicUUID.toUUID(),
                                BleManager.nanoFTSNotifyCharacteristicUUID.toUUID() -> {
                                    bleServiceBuilder.setNotifyCharacteristic(characteristic)
                                }
                            }
                        }
                        val bleDeviceService = bleServiceBuilder.build().apply {
                            this.writeCharacteristic.writeType =
                                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                            this.writeNoAnswerCharacteristic?.writeType =
                                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE

                            initNotification(this.notifyCharacteristic)
                        }
                        deviceServices.add(bleDeviceService)
                    }
                }
                //When considered connected once the services have been scanned
                timeoutJob?.cancel()
                notify(BleServiceEvent.BleDeviceConnected)
                timeoutJob = null
            } else {
                Timber.w("onServicesDiscovered received: $status")
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor?,
            status: Int
        ) {
            super.onDescriptorWrite(gatt, descriptor, status)
            Timber.d("------------- onDescriptorWrite status: $status")
            isNotificationEnabled = true
            if (mtuSize == 0) {
                //We should go there after service discovery as we activate notifications
                //Then we need to get a default Mtu Size
                sendMtuMessage()
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            Timber.d("------------- onCharacteristicWrite status: $status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (commandQueue.isNotEmpty()) {
                    val nextCommand = commandQueue.removeFirst()
                    sendCommand(nextCommand)
                }
            } else {
                pendingCommand?.let {
                    notify(BleServiceEvent.ErrorSend(it.id, "Problem occured while sending APDU"))
                    pendingCommand = null
                }
            }

        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            Timber.d("------------- onCharacteristicChanged status: ${characteristic.value.toHexString()}")

            //Mtu Handshake
            if (mtuSize == 0) {
                mtuSize = characteristic.value.toHexString().substring(MTU_HANDSHAKE_COMMAND.length)
                    .toInt(16)
                Timber.d("-------------  MTU size : $mtuSize")
                connectionState = BLE_STATE_CONNECTED_READY
            } else { //APDU answer
                pendingCommand?.id?.let { id ->
                    handleAnswer(id, characteristic.value.toHexString())?.let { answer ->
                        notify(BleServiceEvent.SendAnswer(sendId = id, answer = answer))
                        pendingCommand = null
                    }
                }
            }
            //Dequeu apdu that could have been stack during the initialisation process (enable Notifications + MTU)
            dequeuApdu()
        }

    }

    private var isNotificationEnabled = false
    private fun initNotification(notifyCharacteristic: BluetoothGattCharacteristic) {
        bluetoothGATT?.let{ gatt ->
            gatt.setCharacteristicNotification(notifyCharacteristic, true)
            notifyCharacteristic.descriptors.forEach {
                it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(it)
            }
        }
    }

    private var pendingAnswers: MutableList<FrameCommand> = mutableListOf()
    private fun handleAnswer(id: String, hexAnswer: String): String? {
        val command: FrameCommand = FrameCommand.fromHex(id, hexAnswer)
        pendingAnswers.add(command)

        val isAnswerComplete = if (command.index == 0) {
            command.size == command.apdu.size
        } else {
            val totalReceivedSize = pendingAnswers.sumOf { it.apdu.size }
            pendingAnswers.first().size == totalReceivedSize
        }

        return if (isAnswerComplete) {
            val completeApdu = pendingAnswers.joinToString("") { it.apdu.toHexString() }
            pendingAnswers.clear()
            completeApdu
        } else {
            null
        }
    }

    fun connect(address: String): Boolean {
        // Previously connected to the given device.
        // Try to reconnect.
        Timber.d("Connect to device address => $address.")

        if (bluetoothDeviceAddress != null && address == bluetoothDeviceAddress && bluetoothGATT != null) {
            Timber.d("Trying to use an existing mBluetoothGatt for connection.")
            return if (bluetoothGATT?.connect() == true) {
                connectionState = BLE_STATE_CONNECTING
                true
            } else {
                false
            }
        }

        val device: BluetoothDevice = bluetoothAdapter.getRemoteDevice(address)

        // We want to directly connect to the device, so we are setting the autoConnect
        // parameter to false.
        bluetoothGATT = device.connectGatt(this, false, gattCallback)
        bluetoothDeviceAddress = address
        timeoutJob = MainScope().launch(Dispatchers.IO) {
            delay(CONNECT_TIMEOUT)
            disconnectService()
        }

        connectionState = BLE_STATE_CONNECTING
        return true
    }

    private fun notify(event: BleServiceEvent) {
        events.tryEmit(event)
    }


    fun sendMtuMessage() {
        Timber.d("Send MTU Handshake")
        mtuSize = 0
        deviceServices.forEach {
            it.writeCharacteristic.value = MTU_HANDSHAKE_COMMAND.fromHexStringToBytes()
            bluetoothGATT?.writeCharacteristic(it.writeCharacteristic)
        }
    }

    fun listenEvents(): Flow<BleServiceEvent> {
        return events
    }

    var pendingApdu: Queue<BlePendingRequest> = ConcurrentLinkedQueue()

    @Synchronized
    fun sendApdu(apdu: ByteArray): String {
        Timber.d("Send APDU")
        if (bluetoothDeviceAddress == null) {
            disconnectService()
        }

        val id = generateId(bluetoothDeviceAddress!!)
        pendingApdu.add(BlePendingRequest(id, apdu))

        if (connectionState == BLE_STATE_CONNECTED_READY) {
            dequeuApdu()
        } else {
            initNotification(deviceServices.first().notifyCharacteristic)
        }

        return id
    }

    private fun dequeuApdu() {
        Timber.d("Try to dequeu pending request")
        Timber.d("pending request => ${pendingApdu.size}")
        if (pendingApdu.isNotEmpty() && pendingCommand == null) {
            Timber.d("Dequeu is possible")
            val pendingRequest = pendingApdu.remove()
            val command = BleCommand(pendingRequest.id, pendingRequest.apdu, mtuSize)
            sendCommands(command)
        } else {
            Timber.d("Dequeu is NOT possible")
        }
    }

    private val commandQueue: ArrayDeque<FrameCommand> = ArrayDeque()
    private fun sendCommands(command: BleCommand) {
        Timber.d("Need to send ${command.commands.size} frame")
        commandQueue.addAll(command.commands)
        val command = commandQueue.removeFirst()
        sendCommand(command)
    }

    private var pendingCommand: FrameCommand? = null
    private fun sendCommand(command: FrameCommand) {
        val commandInByte: ByteArray = command.bytes
        pendingCommand = command
        deviceServices.forEach {
            if (it.writeNoAnswerCharacteristic != null) {
                it.writeNoAnswerCharacteristic.value = commandInByte
                bluetoothGATT?.writeCharacteristic(it.writeNoAnswerCharacteristic)
            } else {
                it.writeCharacteristic.value = commandInByte
                bluetoothGATT?.writeCharacteristic(it.writeCharacteristic)
            }
        }
    }

    companion object {
        private const val MTU_HANDSHAKE_COMMAND = "0800000000"
        private const val APP_VERSION_COMMAND = "b001000000"

        private const val CONNECT_TIMEOUT = 5_000L

        private const val BLE_STATE_DISCONNECTED = 0
        private const val BLE_STATE_CONNECTING = 1
        private const val BLE_STATE_CONNECTED = 2
        private const val BLE_STATE_CONNECTED_READY = 3

        private fun generateId(deviceName: String): String {
            return "${deviceName}_send_${Date().time}"
        }
    }
}
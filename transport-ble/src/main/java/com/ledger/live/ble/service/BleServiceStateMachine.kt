package com.ledger.live.ble.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattService
import android.content.Context
import com.ledger.live.ble.BleManager
import com.ledger.live.ble.extension.toHexString
import com.ledger.live.ble.extension.toUUID
import com.ledger.live.ble.model.BleDeviceService
import com.ledger.live.ble.service.BleService.Companion.MTU_HANDSHAKE_COMMAND
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import timber.log.Timber

@SuppressLint("MissingPermission")
class BleServiceStateMachine(
    private val gattCallbackFlow: BleGattCallbackFlow,
    private val deviceAddress: String,
    private val device: BluetoothDevice,
) {
    var currentState: BleServiceState = BleServiceState.Created
    lateinit var deviceService: BleDeviceService
    var mtuSize = -1
    private val bleReceiver = BleReceiver()

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var timeoutJob: Job

    private val _stateMachineFlow = MutableSharedFlow<BleServiceState>(replay = 1, extraBufferCapacity = 0, onBufferOverflow = BufferOverflow.SUSPEND)
    val stateFlow: Flow<BleServiceState>
        get() = _stateMachineFlow

    private lateinit var gattInteractor: GattInteractor

    init {
        gattCallbackFlow.gattFlow
            .onEach { Timber.d("Event Received $it") }
            .onEach { handleGattCallbackEvent(it) }
            .flowOn(Dispatchers.IO)
            .launchIn(scope)

        timeoutJob = scope.launch {
            delay(CONNECT_TIMEOUT)
            _stateMachineFlow.tryEmit(BleServiceState.Error("Connection timeout error"))
        }
    }

    fun build(context: Context) {
        val bluetoothGATT = device.connectGatt(context, false, gattCallbackFlow)
        this.gattInteractor = GattInteractor(bluetoothGATT!!)
    }

    fun clear() {
        this.gattInteractor.gatt.close()
        this.gattInteractor.gatt.disconnect()
    }

    private val bleSender: BleSender by lazy {
        BleSender(gattInteractor, deviceAddress) { sendId ->
            pushState(BleServiceState.WaitingResponse(sendId))
        }
    }

    fun sendApdu(apdu: ByteArray): String {
        val id = bleSender.queuApdu(apdu)
        if (currentState is BleServiceState.Ready
            || currentState is BleServiceState.WaitingResponse) {
            bleSender.dequeuApdu()
        } else { //Trigger Gatt initialization
            gattInteractor.discoverService()
        }

        return id
    }

    private fun handleGattCallbackEvent(event: GattCallbackEvent) {
        when (event) {
            is GattCallbackEvent.ConnectionState.Connected -> {
                when(currentState) {
                    BleServiceState.Created -> {
                        timeoutJob.cancel()
                        pushState(BleServiceState.WaitingServices)
                        gattInteractor.discoverService()
                    }
                    else -> {
                        pushState(BleServiceState.Error("Connected event should only be received when current state is Created"))
                    }
                }
            }
            is GattCallbackEvent.ServicesDiscovered -> {
                val deviceService = parseServices(event.services)
                if (deviceService != null) {
                    Timber.d("Devices Services parsed for given UUID ${deviceService?.uuid}")
                    Timber.d("Current State $currentState")

                    this@BleServiceStateMachine.deviceService = deviceService
                    when(currentState) {
                        BleServiceState.WaitingServices -> {
                            pushState(BleServiceState.WaitingNotificationEnable)
                            gattInteractor.enableNotification(deviceService)
                        }
                        else -> {
                            pushState(BleServiceState.Error("ServicesDiscovered event should only be received when current state is WaitingServices"))
                        }
                    }
                }
            }
            is GattCallbackEvent.WriteDescriptorAck -> {
                when(currentState) {
                    BleServiceState.WaitingNotificationEnable -> {
                        pushState(BleServiceState.WaitingMtu)
                        gattInteractor.askMtu(deviceService)
                    }
                    else -> {
                        pushState(BleServiceState.Error("WriteDescriptorAck event should only be received when current state is WaitingNotificationEnable"))
                    }
                }
            }
            is GattCallbackEvent.WriteCharacteristicAck -> {
                when(currentState) {
                    BleServiceState.WaitingMtu -> {
                        Timber.d("Mtu request Sent")
                    }
                    is BleServiceState.Ready -> {
                        //NOTHING TO do but not an error
                        //CharacteristicChanged can be called before write characteristic ack
                        bleSender.nextCommand()
                    }
                    is BleServiceState.WaitingResponse -> {
                        bleSender.nextCommand()
                    }
                    else -> {
                        pushState(BleServiceState.Error("WriteCharacteristicAck event should only be received when current state is WaitingMtu or WaitingResponse"))
                    }
                }
            }
            is GattCallbackEvent.CharacteristicChanged -> {
                when(currentState) {
                    BleServiceState.WaitingMtu -> {
                        mtuSize = event.value.toHexString().substring(MTU_HANDSHAKE_COMMAND.length).toInt(16)
                        pushState(BleServiceState.Ready(deviceService, mtuSize, null))
                        Timber.d("Mtu Value received : $mtuSize")
                    }
                    is BleServiceState.WaitingResponse -> {
                        val answer = bleReceiver.handleAnswer(bleSender.pendingCommand!!.id, event.value.toHexString())
                        if (answer != null) {
                            bleSender.clearCommand()
                            pushState(BleServiceState.Ready(deviceService, mtuSize, answer))
                        } else {
                            Timber.d("Still waiting for a part of the answer")
                        }
                    }
                    else -> {
                        pushState(BleServiceState.Error("CharacteristicChanged event should only be received when current state is WaitingMtu or WaitingResponse"))
                    }
                }
            }
            is GattCallbackEvent.ConnectionState.Disconnected -> {
                pushState(BleServiceState.Error("Unexpected disconnection happened"))
            }
        }
    }

    private fun pushState(state: BleServiceState) {
        currentState = state
        //ensure state is pushed
        runBlocking {
            Timber.d("push state => $state")
            _stateMachineFlow.emit(state)
        }

        if (currentState is BleServiceState.Ready) {
            if (!bleSender.isInitialized) {
                bleSender.initialized(mtuSize, deviceService)
            }
            bleSender.dequeuApdu()
        }
    }

    private fun parseServices(services: List<BluetoothGattService>): BleDeviceService? {
        var deviceService : BleDeviceService? = null
        services.forEach { service ->
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
                deviceService = bleServiceBuilder.build()
            }
        }

        return deviceService ?: run {
            _stateMachineFlow.tryEmit(BleServiceState.Error("No usable service found for device"))
            null
        }
    }

    sealed class BleServiceState {
        object Created : BleServiceState()
        object WaitingServices: BleServiceState()
        object WaitingNotificationEnable : BleServiceState()
        object WaitingMtu : BleServiceState()
        data class Ready(val deviceService: BleDeviceService, val mtu: Int, val answer: BleAnswer? = null) : BleServiceState()
        data class WaitingResponse(val sendId: String) : BleServiceState()
        data class Error(val error: String): BleServiceState()
    }

    companion object {
        private const val CONNECT_TIMEOUT = 5_000L

    }
}
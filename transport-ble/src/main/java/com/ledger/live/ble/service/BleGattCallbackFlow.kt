package com.ledger.live.ble.service

import android.bluetooth.*
import com.ledger.live.ble.BleManager
import com.ledger.live.ble.extension.toHexString
import com.ledger.live.ble.extension.toUUID
import com.ledger.live.ble.model.BleDeviceService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.stateIn
import timber.log.Timber

class BleGattCallbackFlow: BluetoothGattCallback() {

    private val _gattFlow = MutableSharedFlow<GattCallbackEvent>(replay = 0, extraBufferCapacity = 4)
    val gattFlow: Flow<GattCallbackEvent>
        get() = _gattFlow


    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        Timber.d("GATT connection state change. state: $newState, status: $status")
        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> {
                _gattFlow.tryEmit(GattCallbackEvent.ConnectionState.Connected)
            }
            BluetoothProfile.STATE_DISCONNECTED -> {
                _gattFlow.tryEmit(GattCallbackEvent.ConnectionState.Disconnected)
            }
        }
    }

    override fun onServicesDiscovered(
        gatt: BluetoothGatt,
        status: Int
    ) {
        Timber.d("------------- onServicesDiscovered status: $status")
        if (status == BluetoothGatt.GATT_SUCCESS) {
            _gattFlow.tryEmit(
                GattCallbackEvent.ServicesDiscovered(gatt.services)
            )
        } else {
            Timber.w("onServicesDiscovered received: $status")
            _gattFlow.tryEmit(GattCallbackEvent.ConnectionState.Disconnected)
        }
    }

    override fun onDescriptorWrite(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor?,
        status: Int
    ) {
        super.onDescriptorWrite(gatt, descriptor, status)
        Timber.d("------------- onDescriptorWrite status: $status")
        _gattFlow.tryEmit(GattCallbackEvent.WriteDescriptorAck(status == BluetoothGatt.GATT_SUCCESS))
    }

    override fun onCharacteristicWrite(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int
    ) {
        Timber.d("------------- onCharacteristicWrite status: $status")
        _gattFlow.tryEmit(GattCallbackEvent.WriteCharacteristicAck(status == BluetoothGatt.GATT_SUCCESS))

    }

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ) {
        Timber.d("------------- onCharacteristicChanged status: ${characteristic.value.toHexString()}")
        _gattFlow.tryEmit(GattCallbackEvent.CharacteristicChanged(characteristic.value))
    }
}

sealed class GattCallbackEvent {
    sealed class ConnectionState: GattCallbackEvent() {
        object Connected: GattCallbackEvent()
        object Disconnected: GattCallbackEvent()
    }
    data class ServicesDiscovered(val services: List<BluetoothGattService>): GattCallbackEvent()
    data class CharacteristicChanged(val value: ByteArray): GattCallbackEvent()
    data class WriteDescriptorAck(val isSuccess: Boolean): GattCallbackEvent()
    data class WriteCharacteristicAck(val isSuccess: Boolean): GattCallbackEvent()
}
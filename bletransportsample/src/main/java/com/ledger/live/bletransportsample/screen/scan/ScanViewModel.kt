package com.ledger.live.bletransportsample.screen.scan

import android.annotation.SuppressLint
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ledger.live.ble.BleManager
import com.ledger.live.ble.model.BleState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import timber.log.Timber

@SuppressLint("MissingPermission")
class ScanViewModel(
    context: Context,
) : ViewModel() {

    private var isScanning: Boolean = false
    private val _uiState: MutableSharedFlow<BleUiState> = MutableSharedFlow(extraBufferCapacity = 1)
    val uiState: Flow<BleUiState>
        get() = _uiState

    private val bleManager: BleManager = BleManager.getInstance(context)

    init {
        bleManager.bleState
            .onEach {
                Timber.d("new state => ${it.toString()}")
            }
            .onEach(this::handleState)
            .flowOn(Dispatchers.IO)
            .launchIn(viewModelScope)
    }

    private fun handleState(state: BleState) {
        when (state) {
            is BleState.Idle,
            is BleState.Disconnected -> {
                _uiState.tryEmit(BleUiState.Idle)
            }
            is BleState.Scanning -> {
                val scanningState = BleUiState.Scanning(
                    devices = state.scannedDevices.map { UiDevice(it.id, it.name) }
                )
                _uiState.tryEmit(scanningState)
            }
   /*         is BleState.Connected -> {
                Timber.d("connected")
                val device = state.connectedDevice
                _uiState.tryEmit(BleUiState.Connected(UiDevice(device.id, device.name)))
            }*/
            else -> {}
        }
    }

    fun toggleScan() {
        if (isScanning) {
            isScanning = false
            bleManager.stopScanning()
            _uiState.tryEmit(BleUiState.Idle)
        } else {
            isScanning = true
            bleManager.startScanning {
                val scanningState = BleUiState.Scanning(
                    devices = it.map { device -> UiDevice(device.id, device.name) }
                )
                _uiState.tryEmit(scanningState)
            }

            _uiState.tryEmit(
                BleUiState.Scanning(
                    devices = emptyList()
                )
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(address: String) {
        bleManager.connect(
            address = address,
            onConnectError = {
                Timber.e("Disconnect Callback => cause: $it")
                isScanning = false
                _uiState.tryEmit(BleUiState.Idle)
            },
            onConnectSuccess = {
                Timber.d("Connected Callback")
                isScanning = false
                val device = it
                _uiState.tryEmit(BleUiState.Connected(UiDevice(device.id, device.name)))
            }
        )
    }

    fun connectNanoX() {
        connectToDevice("DE:F1:55:51:C2:0C")
    }

    fun sendSmallApdu() {
        Timber.d("Send Small APDU")
        val hexStringApdu = "b001000000"
        bleManager.send(
            onSuccess = {
                Timber.d("Send Apdu Success Callback : $it")

            },
            onError = { message ->
                Timber.e(message)
            },
            apduHex = hexStringApdu,
        )
    }

    fun sendBigApdu() {
        Timber.d("Send Big APDU")
        val hexStringApdu =
            "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"
        bleManager.send(
            apduHex = hexStringApdu,
            onError = {
                Timber.e(it)
            },
            onSuccess = {
                Timber.d("Send Apdu Success Callback")
            }
        )
    }

    fun disconnect() {
        bleManager.disconnect()
        bleManager.disconnect {
            Timber.d("Disconnection has been done")
            _uiState.tryEmit(BleUiState.Idle)
        }
    }

    class ScanViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ScanViewModel(context) as T
        }
    }
}

sealed class BleUiState {
    object Idle : BleUiState()
    data class Scanning(
        val devices: List<UiDevice> = emptyList(),
    ) : BleUiState()

    data class Connected(
        val device: UiDevice
    ) : BleUiState()
}

data class UiDevice(
    val id: String,
    val name: String
)

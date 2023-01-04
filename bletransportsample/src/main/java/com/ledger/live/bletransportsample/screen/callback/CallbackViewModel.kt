package com.ledger.live.bletransportsample.screen.callback

import android.annotation.SuppressLint
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.ledger.live.ble.BleManager
import com.ledger.live.ble.BleManagerFactory
import com.ledger.live.bletransportsample.screen.model.BleUiState
import com.ledger.live.bletransportsample.screen.model.UiDevice
import kotlinx.coroutines.flow.*
import timber.log.Timber

@SuppressLint("MissingPermission")
class CallbackViewModel(
    context: Context,
) : ViewModel() {

    private var isScanning: Boolean = false
    private val _uiState: MutableSharedFlow<BleUiState> = MutableSharedFlow(extraBufferCapacity = 1)
    val uiState: Flow<BleUiState>
        get() = _uiState

    private val bleManager: BleManager = BleManagerFactory.newInstance(context)

    fun toggleScan() {
        if (isScanning) {
            isScanning = false
            bleManager.stopScanning()
            _uiState.tryEmit(BleUiState.Idle)
        } else {
            isScanning = true
            bleManager.startScanning {
                Timber.d("Scanned device callback $it")
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
        bleManager.disconnect {
            Timber.d("Disconnection has been done")
            _uiState.tryEmit(BleUiState.Idle)
        }
    }

    class CallbackViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return CallbackViewModel(context) as T
        }
    }
}
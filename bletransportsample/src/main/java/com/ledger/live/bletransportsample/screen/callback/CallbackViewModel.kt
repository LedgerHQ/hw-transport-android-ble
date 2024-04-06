package com.ledger.live.bletransportsample.screen.callback

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import android.annotation.SuppressLint
import android.content.Context
import com.ledger.live.ble.BleManager
import com.ledger.live.ble.BleManagerFactory
import com.ledger.live.ble.model.BleState
import timber.log.Timber
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

@SuppressLint("MissingPermission")
class CallbackViewModel(
    context: Context,
) : ViewModel() {

    private var isScanning: Boolean = false
    private val _uiState: MutableSharedFlow<BleState> = MutableSharedFlow(extraBufferCapacity = 1)
    val uiState: Flow<BleState>
        get() = _uiState

    private val bleManager: BleManager = BleManagerFactory.newInstance(context)

    fun toggleScan() {
        if (isScanning) {
            isScanning = false
            bleManager.stopScanning()
            _uiState.tryEmit(BleState.Idle)
        } else {
            isScanning = true
            bleManager.startScanning {
                Timber.d("Scanned device callback $it")
                val scanningState = BleState.Scanning(scannedDevices = it)
                _uiState.tryEmit(scanningState)
            }

            _uiState.tryEmit(
                BleState.Scanning(
                    scannedDevices = emptyList()
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
                _uiState.tryEmit(BleState.Idle)
            },
            onConnectSuccess = {
                Timber.d("Connected Callback")
                isScanning = false
                _uiState.tryEmit(BleState.Connected(it))
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
            _uiState.tryEmit(BleState.Idle)
        }
    }

    class CallbackViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return CallbackViewModel(context) as T
        }
    }
}

package com.ledger.live.bletransportsample.screen.callback

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.ledger.live.ble.model.BleState
import com.ledger.live.bletransportsample.screen.BleScreen

@SuppressLint("MissingPermission")
@Composable
fun CallbackScreen(
    callbackViewModel: CallbackViewModel
) {
    val uiState: BleState by callbackViewModel.uiState.collectAsState(initial = BleState.Idle)
    BleScreen(
        uiState = uiState,
        toggleScan = { callbackViewModel.toggleScan() },
        onDeviceClick = { deviceId: String -> callbackViewModel.connectToDevice(deviceId) },
        sendSmallApdu = { callbackViewModel.sendSmallApdu() },
        sendBigApdu = { callbackViewModel.sendBigApdu() }
    ) { callbackViewModel.disconnect() }
}


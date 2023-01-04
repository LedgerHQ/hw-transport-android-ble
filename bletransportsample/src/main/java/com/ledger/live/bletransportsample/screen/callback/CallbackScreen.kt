package com.ledger.live.bletransportsample.screen.callback

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.ledger.live.bletransportsample.screen.BleScreen
import com.ledger.live.bletransportsample.screen.model.BleUiState

@SuppressLint("MissingPermission")
@Composable
fun CallbackScreen(
    callbackViewModel: CallbackViewModel
) {
    val uiState: BleUiState by callbackViewModel.uiState.collectAsState(initial = BleUiState.Idle)
    BleScreen(
        uiState = uiState,
        toggleScan = { callbackViewModel.toggleScan() },
        onDeviceClick = { deviceId: String -> callbackViewModel.connectToDevice(deviceId) },
        sendSmallApdu = { callbackViewModel.sendSmallApdu() },
        sendBigApdu = { callbackViewModel.sendBigApdu() }
    ) { callbackViewModel.disconnect() }
}


@file:OptIn(ExperimentalMaterial3Api::class)

package com.ledger.live.bletransportsample.screen.scan

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.ledger.live.bletransportsample.R

@SuppressLint("MissingPermission")
@Composable
fun ScanScreen(
    scanViewModel: ScanViewModel
) {
    val uiState: BleUiState by scanViewModel.uiState.collectAsState(initial = BleUiState.Idle)
    Scan(
        uiState = uiState,
        toggleScan = { scanViewModel.toggleScan() },
        connectknownDevice = { scanViewModel.connectNanoX() },
        onDeviceClick = { deviceId: String -> scanViewModel.connectToDevice(deviceId) },
        sendSmallApdu = { scanViewModel.sendSmallApdu() },
        sendBigApdu = { scanViewModel.sendBigApdu() },
        disconnect = { scanViewModel.disconnect() }
    )

}

@SuppressLint("MissingPermission")
@Composable
fun Scan(
    uiState: BleUiState,
    toggleScan: () -> Unit,
    connectknownDevice: () -> Unit,
    onDeviceClick: (String) -> Unit,
    sendSmallApdu: () -> Unit,
    sendBigApdu: () -> Unit,
    disconnect: () -> Unit,
) {

    when (uiState) {
        is BleUiState.Idle,
        is BleUiState.Scanning -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Button(
                    onClick = { toggleScan() }
                ) {
                    if (uiState is BleUiState.Scanning) {
                        Text(text = "Stop Scanning (${uiState.devices.size})")
                    } else {
                        Text(text = "Start Scan")
                    }
                }

                if (uiState is BleUiState.Scanning) {
                    if (uiState.devices.isEmpty()) {
                        Text(text = "No devices found, please scan again")
                    } else {

                        LazyColumn {
                            items(items = uiState.devices) { device ->
                                ScannedDeviceItem(
                                    title = device.name,
                                    onClick = { onDeviceClick(device.id) })
                            }
                        }
                    }
                } else {

                    Button(
                        onClick = { connectknownDevice() }
                    ) {
                        Text(text = "Connect to Nano X 7B95")
                    }

                    Text(text = "Clic on scan for finding devices")
                }
            }
        }
        is BleUiState.Connected -> {
            Column() {
                Text(text = "Connected to device : ${uiState.device.name}")

                Button(onClick = sendSmallApdu) {
                    Text(text = "Send small APDU")
                }

                Button(onClick = sendBigApdu) {
                    Text(text = "Send BIG APDU")
                }

                Button(onClick = disconnect) {
                    Text(text = "Disconnect")
                }
            }
        }
        else -> {
            //TODO
        }
    }


}

@Composable
fun ScannedDeviceItem(
    title: String,
    onClick: () -> Unit
) {
    Card(
        onClick = { onClick() },
        modifier = Modifier
            .padding(8.dp)
            .height(40.dp)
    ) {
        Row() {
            Icon(
                painter = painterResource(id = R.drawable.ic_nano_x),
                contentDescription = null
            )
            Spacer(Modifier.width(12.dp))
            Text(text = title)
        }
    }

}
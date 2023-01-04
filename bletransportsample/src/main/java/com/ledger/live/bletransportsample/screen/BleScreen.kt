package com.ledger.live.bletransportsample.screen

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.ledger.live.bletransportsample.R
import com.ledger.live.bletransportsample.screen.model.BleUiState

@SuppressLint("MissingPermission")
@Composable
fun BleScreen(
    uiState: BleUiState,
    toggleScan: () -> Unit,
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

@OptIn(ExperimentalMaterial3Api::class)
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

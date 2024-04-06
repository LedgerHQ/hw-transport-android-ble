package com.ledger.live.bletransportsample.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import android.annotation.SuppressLint
import com.ledger.live.ble.model.BleState
import com.ledger.live.bletransportsample.R

@SuppressLint("MissingPermission")
@Composable
fun BleScreen(
    uiState: BleState,
    toggleScan: () -> Unit,
    onDeviceClick: (String) -> Unit,
    sendSmallApdu: () -> Unit,
    sendBigApdu: () -> Unit,
    disconnect: () -> Unit,
) {

    when (uiState) {
        is BleState.Idle,
        is BleState.Scanning -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Button(
                    onClick = { toggleScan() }
                ) {
                    if (uiState is BleState.Scanning) {
                        Text(text = "Stop Scanning (${uiState.scannedDevices.size})")
                    } else {
                        Text(text = "Start Scan")
                    }
                }

                if (uiState is BleState.Scanning) {
                    if (uiState.scannedDevices.isEmpty()) {
                        Text(text = "No devices found, please scan again")
                    } else {
                        LazyColumn {
                            items(items = uiState.scannedDevices) { device ->
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
        is BleState.Connected -> {
            Column() {
                Text(text = "Connected to device : ${uiState.connectedDevice.name}")

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

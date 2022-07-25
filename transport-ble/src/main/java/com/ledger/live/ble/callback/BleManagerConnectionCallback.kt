package com.ledger.live.ble.callback

import com.ledger.live.ble.model.BleDeviceModel

interface BleManagerConnectionCallback {
    fun onConnectionSuccess(device: BleDeviceModel)
    fun onConnectionError(message: String)
}

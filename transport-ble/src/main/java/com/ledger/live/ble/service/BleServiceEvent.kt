package com.ledger.live.ble.service

sealed class BleServiceEvent {
    object BleDeviceConnected: BleServiceEvent()
    object BleDeviceDisconnected: BleServiceEvent()
    data class SuccessSend(val sendId: String): BleServiceEvent()
    data class SendAnswer(val sendId: String, val answer: String): BleServiceEvent()
    data class ErrorSend(val sendId: String, val error: String): BleServiceEvent()
}

package com.ledger.live.ble.model

sealed class BleEvent {
    sealed class BleError : BleEvent() {
        data class SendError(val error: String) : BleEvent()
        data class ConnectionError(val error: String) : BleEvent()
    }

    sealed class BleStateChange : BleEvent() {
        object BluetoothActivated : BleStateChange()
        object BluetoothDeactivated : BleStateChange()
        object ConnectionLost : BleStateChange()
    }

    sealed class SendingEvent : BleEvent() {
        data class SendSuccess(
            val id: String,
        ) : SendingEvent()

        data class SendAnswer(val id: String, val answer: String) : SendingEvent()
    }
}

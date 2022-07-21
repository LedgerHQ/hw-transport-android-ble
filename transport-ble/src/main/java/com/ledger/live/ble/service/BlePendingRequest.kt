package com.ledger.live.ble.service

data class BlePendingRequest(
    val id: String,
    val apdu: ByteArray
)

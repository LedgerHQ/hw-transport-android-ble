package com.ledger.live.ble.service

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.content.Context
import app.cash.turbine.test
import com.ledger.devicesdk.sdk.internal.transportble.BleManager
import com.ledger.devicesdk.sdk.internal.transportble.extension.fromHexStringToBytes
import com.ledger.devicesdk.sdk.internal.transportble.extension.toHexString
import com.ledger.devicesdk.sdk.internal.transportble.extension.toUUID
import com.ledger.devicesdk.sdk.internal.transportble.model.BleError
import com.ledger.devicesdk.sdk.internal.transportble.service.model.GattCallbackEvent
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("Ble State Machine Test")
class BleServiceStateMachineTest {

    private lateinit var stateMachine: com.ledger.devicesdk.sdk.internal.transportble.service.BleServiceStateMachine
    private lateinit var mockedFlow: MutableSharedFlow<com.ledger.devicesdk.sdk.internal.transportble.service.model.GattCallbackEvent>
    private val gatt: BluetoothGatt = mockk()
    private val callbackFlow: com.ledger.devicesdk.sdk.internal.transportble.service.BleGattCallbackFlow = mockk()
    private val mtuSize = 153

    @BeforeEach
    fun setup() {
        val device: BluetoothDevice = mockk()
        every { device.connectGatt(any(), any(), any()) } returns gatt
        every { gatt.writeCharacteristic(any()) } returns true
        every { gatt.requestConnectionPriority(any()) } returns true

        val mockContext: Context = mockk()
        every { mockContext.registerReceiver(any(), any()) } returns mockk()
        every { mockContext.unregisterReceiver(any()) } returns Unit

        mockedFlow = MutableSharedFlow()
        every { callbackFlow.gattFlow } returns mockedFlow

        stateMachine =
            com.ledger.devicesdk.sdk.internal.transportble.service.BleServiceStateMachine(
                callbackFlow,
                "address",
                device
            )
        stateMachine.build(mockContext)

        val mockCharacteristic = mockk<BluetoothGattCharacteristic>() {
            every { setValue(any<ByteArray>()) } returns true
        }
        stateMachine.deviceService = mockk {
            every { writeNoAnswerCharacteristic } returns mockCharacteristic
            every { writeCharacteristic } returns mockCharacteristic
            every { notifyCharacteristic } returns mockCharacteristic
        }

        stateMachine.mtuSize = mtuSize
    }

    @Nested
    @DisplayName("State::Created")
    inner class CreatedState {

        @Test
        fun `Given state is created when waiting more than 5 seconds we should have a timeout error`() =
            runTest {
                //Given
                stateMachine.currentState = com.ledger.devicesdk.sdk.internal.transportble.service.BleServiceStateMachine.BleServiceState.Created

                //When
                delay(5000)

                //Then
                stateMachine.stateFlow.test {
                    assertEquals(
                        com.ledger.devicesdk.sdk.internal.transportble.service.BleServiceStateMachine.BleServiceState.Error(
                            com.ledger.devicesdk.sdk.internal.transportble.model.BleError.CONNECTION_TIMEOUT),
                        awaitItem()
                    )
                }
            }

        @Test
        fun `Given state is not Connected when receiving Connected Event try to discover services`() =
            runTest {
                //Given
                every { gatt.discoverServices() } returns true

                //When
                mockedFlow.emit(com.ledger.devicesdk.sdk.internal.transportble.service.model.GattCallbackEvent.ConnectionState.Connected)

                //Then
                stateMachine.stateFlow.test {
                    assertEquals(
                        com.ledger.devicesdk.sdk.internal.transportble.service.BleServiceStateMachine.BleServiceState.WaitingServices,
                        awaitItem()
                    )
                    verify { gatt.discoverServices() }
                }
            }
    }

    @Nested
    @DisplayName("State::WaitingServices")
    inner class WaitingServices {
        @Test
        fun `Given state is NOT Waiting Services when receiving ServicesDiscovered Event then send Error State`() =
            runTest {
                //Given
                stateMachine.currentState = com.ledger.devicesdk.sdk.internal.transportble.service.BleServiceStateMachine.BleServiceState.Created
                //When
                mockedFlow.emit(com.ledger.devicesdk.sdk.internal.transportble.service.model.GattCallbackEvent.ServicesDiscovered(emptyList()))

                //Then
                stateMachine.stateFlow.test {
                    assertEquals(
                        com.ledger.devicesdk.sdk.internal.transportble.service.BleServiceStateMachine.BleServiceState.Error(
                            com.ledger.devicesdk.sdk.internal.transportble.model.BleError.INTERNAL_STATE),
                        awaitItem()
                    )
                }
            }

        @Test
        fun `Given state is Waiting Services when receiving ServicesDiscovered Event try to Negotiate MTU`() =
            runTest {
                //Given
                stateMachine.currentState = com.ledger.devicesdk.sdk.internal.transportble.service.BleServiceStateMachine.BleServiceState.WaitingServices

                every { gatt.requestMtu(any()) } returns true
                val mockedService = mockk<BluetoothGattService>()
                val writeCharacteristic: BluetoothGattCharacteristic = mockk()
                every { writeCharacteristic.uuid } returns com.ledger.devicesdk.sdk.internal.transportble.BleManager.nanoXWriteWithResponseCharacteristicUUID.toUUID()

                val writeNoAnswerCharacteristic: BluetoothGattCharacteristic = mockk()
                every { writeNoAnswerCharacteristic.uuid } returns com.ledger.devicesdk.sdk.internal.transportble.BleManager.nanoXWriteWithoutResponseCharacteristicUUID.toUUID()

                val notifyCharacteristic: BluetoothGattCharacteristic = mockk()
                every { notifyCharacteristic.uuid } returns com.ledger.devicesdk.sdk.internal.transportble.BleManager.nanoXNotifyCharacteristicUUID.toUUID()

                every { mockedService.uuid } returns com.ledger.devicesdk.sdk.internal.transportble.BleManager.NANO_X_SERVICE_UUID.toUUID()
                every { mockedService.characteristics } returns listOf(
                    writeNoAnswerCharacteristic,
                    writeCharacteristic,
                    notifyCharacteristic
                )

                //When
                mockedFlow.emit(com.ledger.devicesdk.sdk.internal.transportble.service.model.GattCallbackEvent.ServicesDiscovered(listOf(mockedService)))

                //Then
                stateMachine.stateFlow.test {
                    assertEquals(com.ledger.devicesdk.sdk.internal.transportble.service.BleServiceStateMachine.BleServiceState.NegotiatingMtu, awaitItem())
                    verify { gatt.requestMtu(any()) }
                }
            }

        @Test
        fun `Given state is Waiting Services when receiving ServicesDiscovered Event with empty services Then send Error State`() =
            runTest {
                //Given
                stateMachine.currentState = com.ledger.devicesdk.sdk.internal.transportble.service.BleServiceStateMachine.BleServiceState.WaitingServices
                every { gatt.requestMtu(any()) } returns true

                //When
                mockedFlow.emit(com.ledger.devicesdk.sdk.internal.transportble.service.model.GattCallbackEvent.ServicesDiscovered(emptyList()))

                //Then
                stateMachine.stateFlow.test {
                    assertEquals(
                        com.ledger.devicesdk.sdk.internal.transportble.service.BleServiceStateMachine.BleServiceState.Error(
                            com.ledger.devicesdk.sdk.internal.transportble.model.BleError.SERVICE_NOT_FOUND),
                        awaitItem()
                    )
                }
            }
    }

    @Nested
    @DisplayName("State::NegotiatingMtu")
    inner class NegotiatingMtu {
        @Test
        fun `Given state is NOT Negotiating Mtu when receiving Mtu Negotiated Event then send Error State`() =
            runTest {
                //Given
                stateMachine.currentState = com.ledger.devicesdk.sdk.internal.transportble.service.BleServiceStateMachine.BleServiceState.WaitingServices

                //When
                mockedFlow.emit(com.ledger.devicesdk.sdk.internal.transportble.service.model.GattCallbackEvent.MtuNegociated(153))

                //Then
                stateMachine.stateFlow.test {
                    assertEquals(
                        com.ledger.devicesdk.sdk.internal.transportble.service.BleServiceStateMachine.BleServiceState.Error(
                            com.ledger.devicesdk.sdk.internal.transportble.model.BleError.INTERNAL_STATE),
                        awaitItem()
                    )
                }
            }

        @Test
        fun `Given state is Negotiating Mtu when receiving Mtu Negotiated Event then send try to enable notification`() =
            runTest {
                //Given
                stateMachine.currentState = com.ledger.devicesdk.sdk.internal.transportble.service.BleServiceStateMachine.BleServiceState.NegotiatingMtu
                stateMachine.deviceService = mockk()
                every { stateMachine.deviceService.notifyCharacteristic } returns mockk {
                    every { descriptors } returns listOf(
                        mockk {
                            every { setValue(any()) } returns true
                        }
                    )
                }
                every { gatt.setCharacteristicNotification(any(), true) } returns true
                every { gatt.writeDescriptor(any()) } returns true

                //When
                mockedFlow.emit(com.ledger.devicesdk.sdk.internal.transportble.service.model.GattCallbackEvent.MtuNegociated(153))

                //Then
                stateMachine.stateFlow.test {
                    assertEquals(
                        com.ledger.devicesdk.sdk.internal.transportble.service.BleServiceStateMachine.BleServiceState.WaitingNotificationEnable,
                        awaitItem()
                    )
                    assertEquals(153, stateMachine.negotiatedMtu)
                    verify { gatt.setCharacteristicNotification(any(), true) }
                }
            }
    }

    @Nested
    @DisplayName("State::WaitingNotificationEnable")
    inner class WaitingNotificationEnable {
        @Test
        fun `Given state is NOT Waiting Notification Enable when receiving Mtu Negotiated Event then send Error State`() =
            runTest {
                //Given
                stateMachine.currentState = com.ledger.devicesdk.sdk.internal.transportble.service.BleServiceStateMachine.BleServiceState.WaitingServices

                //When
                mockedFlow.emit(com.ledger.devicesdk.sdk.internal.transportble.service.model.GattCallbackEvent.WriteDescriptorAck(true))

                //Then
                stateMachine.stateFlow.test {
                    assertEquals(
                        com.ledger.devicesdk.sdk.internal.transportble.service.BleServiceStateMachine.BleServiceState.Error(
                            com.ledger.devicesdk.sdk.internal.transportble.model.BleError.INTERNAL_STATE),
                        awaitItem()
                    )
                }
            }

        @Test
        fun `Given state is Waiting Notification Enable when receiving Write Descriptor Ack Event then send CheckingMtu State`() =
            runTest {
                //Given
                stateMachine.currentState =
                    com.ledger.devicesdk.sdk.internal.transportble.service.BleServiceStateMachine.BleServiceState.WaitingNotificationEnable

                //When
                mockedFlow.emit(com.ledger.devicesdk.sdk.internal.transportble.service.model.GattCallbackEvent.WriteDescriptorAck(true))

                //Then
                stateMachine.stateFlow.test {
                    assertEquals(com.ledger.devicesdk.sdk.internal.transportble.service.BleServiceStateMachine.BleServiceState.CheckingMtu, awaitItem())
                }
            }
    }

    @Nested
    @DisplayName("State::CheckingMtu")
    inner class CheckingMtu {

        @Test
        fun `Given state is NOT Checking MTU when receiving Characteristic Changed Event then send Ready State`() =
            runTest {
                //Given
                stateMachine.currentState = com.ledger.devicesdk.sdk.internal.transportble.service.BleServiceStateMachine.BleServiceState.NegotiatingMtu

                //When
                mockedFlow.emit(com.ledger.devicesdk.sdk.internal.transportble.service.model.GattCallbackEvent.CharacteristicChanged("${com.ledger.devicesdk.sdk.internal.transportble.service.BleService.MTU_HANDSHAKE_COMMAND}${mtuSize.toByte().toHexString()}".fromHexStringToBytes()))

                //Then
                stateMachine.stateFlow.test {
                    assertEquals(
                        com.ledger.devicesdk.sdk.internal.transportble.service.BleServiceStateMachine.BleServiceState.Error(
                            com.ledger.devicesdk.sdk.internal.transportble.model.BleError.INTERNAL_STATE),
                        awaitItem()
                    )
                }
            }

        @Test
        fun `Given state is Checking MTU when receiving Characteristic Changed Event then send Ready State`() =
            runTest {
                //Given
                stateMachine.currentState = com.ledger.devicesdk.sdk.internal.transportble.service.BleServiceStateMachine.BleServiceState.CheckingMtu
                stateMachine.negotiatedMtu = mtuSize

                //When
                mockedFlow.emit(com.ledger.devicesdk.sdk.internal.transportble.service.model.GattCallbackEvent.CharacteristicChanged("${com.ledger.devicesdk.sdk.internal.transportble.service.BleService.MTU_HANDSHAKE_COMMAND}${mtuSize.toByte().toHexString()}".fromHexStringToBytes()))

                //Then
                stateMachine.stateFlow.test {
                    assertEquals(
                        com.ledger.devicesdk.sdk.internal.transportble.service.BleServiceStateMachine.BleServiceState.Ready(
                            stateMachine.deviceService,
                            mtuSize,
                            null
                        ), awaitItem()
                    )
                }
            }
    }

    @Nested
    @DisplayName("State::Ready")
    inner class Ready {
        @Test
        fun `Given state is Ready when sending APDU then send Waiting Response`() =
            runTest {
                //Given
                stateMachine.bleSender.initialized(mtuSize, stateMachine.deviceService)
                stateMachine.negotiatedMtu = mtuSize
                stateMachine.currentState = com.ledger.devicesdk.sdk.internal.transportble.service.BleServiceStateMachine.BleServiceState.Ready(
                    stateMachine.deviceService,
                    mtuSize,
                    null
                )

                //When
                val id = stateMachine.sendApdu("mysuperApdu".toByteArray())

                //Then
                stateMachine.stateFlow.test {
                    assertEquals(
                        com.ledger.devicesdk.sdk.internal.transportble.service.BleServiceStateMachine.BleServiceState.WaitingResponse(id), awaitItem()
                    )
                }
            }

        @Test
        fun `Given state is NOT Ready when sending APDU then try initialize state machine by discovering services and queue APDU`() =
            runTest {
                //Given
                stateMachine.currentState = com.ledger.devicesdk.sdk.internal.transportble.service.BleServiceStateMachine.BleServiceState.CheckingMtu
                every { gatt.discoverServices() } returns true

                //When
                val id = stateMachine.sendApdu("mysuperApdu".toByteArray())

                //Then
                assertEquals(1, stateMachine.bleSender.pendingApdu.size)
                stateMachine.stateFlow.test {
                    assertEquals(
                        com.ledger.devicesdk.sdk.internal.transportble.service.BleServiceStateMachine.BleServiceState.WaitingServices, awaitItem()
                    )
                }
            }
    }

    @Nested
    @DisplayName("State::WaitingResponse")
    inner class WaitingResponse {
        @Test
        fun `Given state is WaitingResponse when calling send APDU then send queue apdu`() =
            runTest {
                //Given
                stateMachine.bleSender.initialized(mtuSize, stateMachine.deviceService)
                stateMachine.negotiatedMtu = mtuSize
                stateMachine.currentState = com.ledger.devicesdk.sdk.internal.transportble.service.BleServiceStateMachine.BleServiceState.WaitingResponse("id")

                assertEquals(0, stateMachine.bleSender.pendingApdu.size)
                assertNull(stateMachine.bleSender.pendingCommand)

                //When
                val id1 = stateMachine.sendApdu("mysuperApdu".toByteArray())

                //Try to send first one APDU directly so no queue increasing
                assertEquals(0, stateMachine.bleSender.pendingApdu.size)
                assertNotNull(stateMachine.bleSender.pendingCommand)
                assertEquals(id1, stateMachine.bleSender.pendingCommand?.id)

                val id2 = stateMachine.sendApdu("mysuperApdu2".toByteArray())

                //Still Waiting response so queue second APDU
                assertEquals(1, stateMachine.bleSender.pendingApdu.size)
                assertEquals(id2, stateMachine.bleSender.pendingApdu.element().id)

            }
    }
}

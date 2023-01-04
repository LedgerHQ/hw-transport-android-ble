<img src="https://user-images.githubusercontent.com/4631227/191834116-59cf590e-25cc-4956-ae5c-812ea464f324.png" height="100" />

[GitHub](https://github.com/LedgerHQ/ledger-live/),
[Ledger Devs Discord](https://developers.ledger.com/discord-pro),
[Developer Portal](https://developers.ledger.com/)

# Android BLE transport Library (beta)
Allows for communication with Ledger Hardware Wallets(Nano X, Stax) via BLE (Bluetooth Low Energy) on Android (>=13.0).
Please note that this is a beta release and still under active development.

Current VERSION is => 1.0.0-beta1

## Setup

### Manifest
When integrating the library you have to authorize some permissions. 

```xml
      <uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
      <uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
      <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" tools:node="replace" android:maxSdkVersion="28"/>
      <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" tools:node="replace" android:maxSdkVersion="30"/>

      <!-- Bluetooth permissions: Android API >= 31 (Android 12)-->
      <uses-permission android:name="android.permission.BLUETOOTH_CONNECT"/>
      <uses-permission android:name="android.permission.BLUETOOTH_SCAN" android:usesPermissionFlags="neverForLocation"/>
```

### Import 
```groovy
implementation 'com.github.LedgerHQ:hw-transport-android-ble:VERSION'
```

## Usage
The `bletransportsample` application is the best way to see `transport-ble` in action. 

### Init
The entry point for interacting with the bluetooth is the BleManager. You can instantiate it through the `BleManagerFactory`
by simply providing the context which is required for interacting with bluetooth service  

Sample:

```kotlin
private val bleManager: BleManager = BleManagerFactory.newInstance(context)
```

### Interface 
Which the BleManager instance you are able to do several actions 

* scan
* connect
* send
* disconnect

These actions are doable in 2 different ways. The first one is providing callback for each action 
and the second is to use Flow

### Using callback 
#### Scanning 
For scanning devices you have to call `startScanning()` by providing a callback that will be called
every 1 seconds, giving the list of scanned devices, it could only be Nano X and Stax devices other
devices will be filtered.

Code: 
```kotlin
private val bleManager: BleManager = BleManagerFactory.newInstance(context)
bleManager.startScanning { scannedDevices -> 
    //DO what you want here
    //You have device ID(address), service ID, name, rssi, 
}

```

#### Connecting to a device

For connecting to a device it's very simple you just have to provide the device address, which is the ID retrieve from scanned device
And you have to provide also the 2 callbacks, the success and error one. 

Code:
```kotlin
bleManager.connect(
    address = scannedDevice.address, //DEVICE ADDRESS, 
    onConnectSuccess = { device -> 
        //Called when device is successfully connected to a NANO X or Stax and pairing have been done if needed
    },
    onConnectError = { error ->
        //Called when connection was not possible (timeout, pairing rejecting, etc) 
        // or unexpected disconnection happened 
    }
)
```

#### Sending APDU 
Code:
```kotlin
bleManager. send(
    apduHex = "0001feab89dc0089098", // APDU string representation in hexadecimal you can use utils extension ByteArray.toHexString()
    onSuccess = { answer -> 
        // Send has been successfull and connected device may have send an answer in response of the sent message
        // Answer can be null 
    }, 
    onError = { errorMessage ->  
        //you can handle the send error here with the message indicating the cause of the error 
    } 
)
```
#### Disconnecting
Code:
```kotlin
bleManager.disconnect(
  onDisconnectSuccess = {
      //Call when asked disconnection is effective
  }
)
```

### Using Flow

For using flow you will have first to listen the followings flows 
`bleState` and `bleEvents`

The different states are : 

* Idle: meaning that nothing is happening (not connected to a device and not scanning devices)
* Scanning: scanning is in progress and it gives the list of scanned devices
* Connected: Indicating that the a device is connected, some information are available 
* Disconnected

The events are :
* Error State 
  * SendError: Mean that sending an APDU has failed 
  * ConnectionError( not able to connect or unexpected disconnection)
* BleStateChange 
  * BluetoothActivated: Bluetooth has been activated on device
  * BluetoothDeactivated: Bluetooth has been deactivated on device 
  * ConnectionLost: connection to device has been lost 
* SendingEvent
  * SendSuccess: APDU has been successfully sent 
  * SendAnswer: We received an answer from connected device after sending an APDU


INCOMPLETE TBD
#### Scanning
#### Connecting to a device
#### Sending APDU
#### Disconnecting

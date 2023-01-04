package com.ledger.live.bletransportsample

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import com.ledger.live.bletransportsample.screen.callback.CallbackScreen
import com.ledger.live.bletransportsample.screen.callback.CallbackViewModel
import com.ledger.live.bletransportsample.screen.flow.FlowScreen
import com.ledger.live.bletransportsample.screen.flow.FlowViewModel
import com.ledger.live.bletransportsample.ui.theme.LiveTransportBleTheme
import timber.log.Timber
import timber.log.Timber.DebugTree

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Timber.treeCount == 0) {
            Timber.plant(DebugTree())
        }

        val callbackViewModel = CallbackViewModel.CallbackViewModelFactory(this).create(CallbackViewModel::class.java)
        val flowViewModel = FlowViewModel.FlowViewModelFactory(this).create(FlowViewModel::class.java)

        setContent {
            LiveTransportBleTheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // For Callback
                    //CallbackScreen(callbackViewModel)

                    // For Flow usage
                    FlowScreen(flowViewModel)
                }
            }
        }
    }
}

class ErrorTree : Timber.Tree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (priority == Log.ERROR || priority == Log.WARN) {
            Log.println(priority, tag, message)
        }
    }
}

@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    LiveTransportBleTheme {
        CallbackScreen(CallbackViewModel.CallbackViewModelFactory(LocalContext.current).create(CallbackViewModel::class.java))
    }
}
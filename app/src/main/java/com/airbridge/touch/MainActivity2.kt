package com.airbridge.touch

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.airbridge.touch.data.HistoryRepository
import com.airbridge.touch.data.SettingsRepository
import com.airbridge.touch.transport.ConnectionMachine
import com.airbridge.touch.transport.WifiDirectController
import com.airbridge.touch.transfer.DestinationManager
import com.airbridge.touch.ui.AppViewModel
import com.airbridge.touch.ui.HomeScreen
import com.airbridge.touch.ui.theme.AirBridgeTheme
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {

    private lateinit var machine: ConnectionMachine
    private lateinit var settings: SettingsRepository
    private lateinit var viewModel: AppViewModel

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    private val pickFiles = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> viewModel.sendFiles(uris) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        settings = SettingsRepository(this)
        val history = HistoryRepository(this)
        val controller = WifiDirectController(this) { android.util.Log.d(TAG, it) }
        machine = ConnectionMachine(controller) { android.util.Log.d(TAG, it) }
        val destinations = DestinationManager(this)
        viewModel = AppViewModel(this, machine, history, settings, destinations)

        requestRuntimePermissions()

        val prefill = runBlocking { settings.current() }
        setContent {
            AirBridgeTheme(darkTheme = prefill.darkTheme || prefill.useSystemTheme) {
                HomeScreen(viewModel, onPickFiles = { pickFiles.launch(arrayOf("*/*")) })
            }
        }
    }

    private fun requestRuntimePermissions() {
        val needed = mutableListOf(
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE
        )
        if (Build.VERSION.SDK_INT >= 33) {
            needed.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    companion object { private const val TAG = "AirBridgeTouch" }
}

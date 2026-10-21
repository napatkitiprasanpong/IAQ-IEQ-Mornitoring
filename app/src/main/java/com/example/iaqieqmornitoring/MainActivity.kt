package com.example.iaqieqmornitoring

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.iaqieqmornitoring.navigation.AppNavigation
import com.example.iaqieqmornitoring.ui.theme.IAQIEQMornitoringTheme
import com.example.iaqieqmornitoring.utils.AppSettings
import com.example.iaqieqmornitoring.utils.SensorRepository
import com.example.iaqieqmornitoring.utils.TemiRepository

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Only on the first launch of the process (not on rotation / re-creation).
        val firstLaunch = !AppSettings.isInitialised
        if (firstLaunch) AppSettings.init(applicationContext)

        val activityInfo = try {
            packageManager.getActivityInfo(componentName, PackageManager.GET_META_DATA)
        } catch (e: Exception) {
            null
        }
        TemiRepository.init(activityInfo)

        if (firstLaunch) {
            TemiRepository.applyMode()          // temi robot (or simulated robot)
            SensorRepository.applySettings()    // Raspberry Pi over MQTT (or simulated sensors)
        }

        enableEdgeToEdge()

        setContent {
            IAQIEQMornitoringTheme {
                AppNavigation()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        TemiRepository.onActivityStart()
    }
}

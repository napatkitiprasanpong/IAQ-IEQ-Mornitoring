package com.example.iaqieqmornitoring

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.iaqieqmornitoring.bridge.IaqBridge
import com.example.iaqieqmornitoring.bridge.IaqBridgeHostImpl
import com.example.iaqieqmornitoring.navigation.AppNavigation
import com.example.iaqieqmornitoring.ui.theme.IAQIEQMornitoringTheme
import com.example.iaqieqmornitoring.utils.AppSettings
import com.example.iaqieqmornitoring.utils.RoutineManager
import com.example.iaqieqmornitoring.utils.SensorRepository
import com.example.iaqieqmornitoring.utils.TemiRepository
import com.robotemi.sdk.Robot
import com.robotemi.sdk.listeners.OnRobotReadyListener

class MainActivity : ComponentActivity(), OnRobotReadyListener {

    private val robot: Robot?
        get() = try { Robot.getInstance() } catch (e: Throwable) { null }

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

        RoutineManager.startScheduler()     // runs scheduled routines while the app is open
        IaqBridge.host = IaqBridgeHostImpl() // lets the AI/voice app (MatePet) control and read this app

        enableEdgeToEdge()

        setContent {
            IAQIEQMornitoringTheme {
                AppNavigation()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) IaqBridge.host = null
    }

    override fun onStart() {
        super.onStart()
        robot?.addOnRobotReadyListener(this)
        TemiRepository.onActivityStart()
    }

    override fun onStop() {
        super.onStop()
        robot?.removeOnRobotReadyListener(this)
    }

    override fun onRobotReady(isReady: Boolean) {
        if (isReady) {
            val activityInfo = try {
                packageManager.getActivityInfo(componentName, PackageManager.GET_META_DATA)
            } catch (e: Exception) {
                null
            }
            if (activityInfo != null) {
                try {
                    robot?.onStart(activityInfo)
                } catch (e: Exception) {
                    // ignore
                }
            }
        }
    }
}

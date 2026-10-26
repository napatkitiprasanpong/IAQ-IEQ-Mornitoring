package com.example.iaqieqmornitoring

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.iaqieqmornitoring.utils.AppSettings

/**
 * Opens the app when the tablet has finished booting, if "Start this app automatically when temi starts"
 * is on in Settings. The setting is read straight from the preferences file because the app has not
 * been started yet at this point.
 *
 * Android 10 and newer stop apps from opening screens out of the background, so on those versions
 * this may do nothing; registering as temi's start-up app (done by the same setting) is the other route.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != "android.intent.action.QUICKBOOT_POWERON") return

        val prefs = context.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(AppSettings.KEY_AUTO_START, false)) return

        val launch = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }
        try {
            context.startActivity(launch)
        } catch (e: Exception) {
            // Not allowed to open the screen from the background on this Android version: nothing more to do.
        }
    }
}

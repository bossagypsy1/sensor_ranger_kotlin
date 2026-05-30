package com.sensorranger.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Receives AlarmManager broadcasts and kicks the service to do a push.
 * Using AlarmManager (setAndAllowWhileIdle) instead of coroutine delay means
 * pushes still fire during Android Doze maintenance windows.
 */
class PushAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        LogManager.log("ALARM", "Push alarm received")
        if (PreferencesManager(context).running) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, SensorRangerService::class.java).apply {
                    action = SensorRangerService.ACTION_PUSH
                }
            )
        } else {
            LogManager.log("ALARM", "Not running — alarm ignored")
        }
    }
}

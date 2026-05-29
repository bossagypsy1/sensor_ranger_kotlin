package com.sensorranger.app

import android.app.Application
import android.content.Intent

class SensorRangerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        LogManager.init(this)
        LogManager.log("APP", "Application starting")

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                // Save crash to file FIRST before anything else
                LogManager.saveCrash(throwable)

                // Launch CrashActivity to show the error visually
                val intent = Intent(this, CrashActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    putExtra(CrashActivity.EXTRA_CRASH, throwable.stackTraceToString())
                    putExtra(CrashActivity.EXTRA_TITLE,
                        "${throwable.javaClass.simpleName}: ${throwable.message}")
                }
                startActivity(intent)
            } catch (_: Exception) {
                // If even our handler crashes, fall through to default
            }

            // Let the default handler run too (generates system crash dialog)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}

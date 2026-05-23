package com.sensorranger.app

import android.app.Application

class SensorRangerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        LogManager.init(this)

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val trace = throwable.stackTraceToString()
            LogManager.saveCrash(trace)
            LogManager.log("CRASH", throwable.message ?: throwable.javaClass.simpleName)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}

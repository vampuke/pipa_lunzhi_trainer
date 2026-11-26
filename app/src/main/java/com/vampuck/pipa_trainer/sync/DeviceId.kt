package com.vampuck.pipa_trainer.sync

import android.content.Context
import java.util.UUID

object DeviceId {
    @Synchronized
    fun get(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences("practice_sync", Context.MODE_PRIVATE)
        prefs.getString("device_id", null)?.let { return it }
        return UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }
    }
}

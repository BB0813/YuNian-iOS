package com.lianyu.ai.common

import android.content.Context
import java.util.UUID

object DeviceIdProvider {
    private const val PREFS_NAME = "lianyu_secure"
    private const val KEY_DEVICE_ID = "device_id"

    @Volatile
    private var cachedDeviceId: String? = null

    fun getDeviceId(context: Context): String {
        cachedDeviceId?.let { return it }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var id = prefs.getString(KEY_DEVICE_ID, null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        }
        cachedDeviceId = id
        return id
    }
}

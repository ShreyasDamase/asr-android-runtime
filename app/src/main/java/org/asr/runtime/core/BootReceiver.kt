package org.asr.runtime.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/** Starts the runtime after a reboot, only when START ON BOOT is on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val enabled = context.getSharedPreferences("asr_prefs", Context.MODE_PRIVATE)
            .getBoolean("start_on_boot", false)
        if (!enabled) return
        Log.i("AsrBoot", "Starting ASR runtime after boot")
        val service = Intent(context, AsrService::class.java).setAction(AsrService.ACTION_BOOT_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(service)
        } else {
            context.startService(service)
        }
    }
}

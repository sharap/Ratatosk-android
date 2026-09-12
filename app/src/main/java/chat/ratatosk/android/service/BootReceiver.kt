package chat.ratatosk.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import chat.ratatosk.android.core.RatatoskCore

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Не только BOOT_COMPLETED: MIUI шлёт своё QUICKBOOT_POWERON вместо
        // него, а после обновления приложения система процесс не поднимает
        // вовсе — MY_PACKAGE_REPLACED единственный повод узнать об этом.
        val known = intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON" ||
            intent.action == "com.htc.intent.action.QUICKBOOT_POWERON"
        if (known) {
            if (RatatoskCore.anyAccountExists(context)) {
                scheduleWatchdog(context)
                val serviceIntent = Intent(context, RatatoskService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }
}

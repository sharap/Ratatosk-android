package chat.ratatosk.android.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import chat.ratatosk.android.core.RatatoskCore

/**
 * Как часто сторож проверяет, жив ли сервис.
 *
 * Пятнадцать минут — не попытка обмануть систему, а компромисс: реже
 * означало бы полночи без связи после отстрела, чаще — будильник ради
 * будильника. Приложение в белом списке Doze, так что срабатывание
 * не откладывается до окна обслуживания.
 */
private const val WATCHDOG_INTERVAL_MS = 15L * 60 * 1000

private const val WATCHDOG_REQUEST = 4711

private fun watchdogIntent(context: Context): PendingIntent {
    val intent = Intent(context, ServiceWatchdogReceiver::class.java).apply {
        action = ServiceWatchdogReceiver.ACTION_CHECK
    }
    return PendingIntent.getBroadcast(
        context,
        WATCHDOG_REQUEST,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

/**
 * Взводит сторожа заново.
 *
 * Одноразовый будильник, который перевзводит сам себя, а не повторяющийся:
 * повторяющиеся система в Doze складывает в общее окно и растягивает,
 * а `setAndAllowWhileIdle` срабатывает и в простое.
 *
 * Неточный намеренно: точный на Android 12+ требует отдельного разрешения,
 * а сторожу секунды не важны.
 */
fun scheduleWatchdog(context: Context, delayMs: Long = WATCHDOG_INTERVAL_MS) {
    val alarms = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
    val at = System.currentTimeMillis() + delayMs
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, watchdogIntent(context))
        } else {
            alarms.set(AlarmManager.RTC_WAKEUP, at, watchdogIntent(context))
        }
    } catch (t: Throwable) {
        android.util.Log.w("ServiceWatchdog", "Failed to schedule watchdog: ${t.message}")
    }
}

/**
 * Поднимает сервис, если его не стало, и взводит следующую проверку.
 *
 * Нужен потому, что START_STICKY — обещание системы, а не оболочки:
 * MIUI и подобные отстреливают процесс и не поднимают его обратно.
 */
class ServiceWatchdogReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_CHECK = "chat.ratatosk.android.WATCHDOG_CHECK"
    }

    override fun onReceive(context: Context, intent: Intent) {
        // Следующую проверку взводим первым делом: если запуск сервиса
        // ниже откажет, цепочка всё равно не оборвётся.
        scheduleWatchdog(context)

        if (!RatatoskCore.anyAccountExists(context)) return

        val serviceIntent = Intent(context, RatatoskService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (t: Throwable) {
            // С Android 12 поднимать foreground-сервис из фона можно
            // не всегда. Приложение в белом списке Doze, так что обычно
            // можно, — но отказ не должен ронять приёмник.
            android.util.Log.w("ServiceWatchdog", "Failed to start service: ${t.message}")
        }
    }
}

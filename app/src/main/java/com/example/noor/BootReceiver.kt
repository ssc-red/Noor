package com.example.noor

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.*

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Reschedule all notifications after device boot
            val prefs = context.getSharedPreferences("NoorPrefs", Context.MODE_PRIVATE)
            val sehriRaw = prefs.getString("sehri_raw", "") ?: ""
            val iftarRaw = prefs.getString("iftar_raw", "") ?: ""

            if (sehriRaw.isNotEmpty()) {
                scheduleNotification(context, sehriRaw, "Sehri Time", "It's time for Sehri!", 1001)
            }

            if (iftarRaw.isNotEmpty()) {
                scheduleNotification(context, iftarRaw, "Iftar Time", "It's time for Iftar!", 1002)
            }
        }
    }

    private fun scheduleNotification(
        context: Context,
        time24h: String,
        title: String,
        message: String,
        id: Int
    ) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            val intent = Intent(context, NotificationReceiver::class.java).apply {
                putExtra("title", title)
                putExtra("message", message)
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val calendar = Calendar.getInstance()
            val parts = time24h.split(":")
            calendar.set(Calendar.HOUR_OF_DAY, parts[0].toInt())
            calendar.set(Calendar.MINUTE, parts[1].toInt())
            calendar.set(Calendar.SECOND, 0)

            if (calendar.before(Calendar.getInstance())) {
                calendar.add(Calendar.DAY_OF_YEAR, 1)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        calendar.timeInMillis,
                        pendingIntent
                    )
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    calendar.timeInMillis,
                    pendingIntent
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}


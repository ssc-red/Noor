package com.example.noor

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class WidgetUpdateWorker(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val prefs = context.getSharedPreferences("NoorPrefs", Context.MODE_PRIVATE)
        val saherTime = prefs.getString("saher_raw", "") ?: ""
        val iftarTime = prefs.getString("iftar_raw", "") ?: ""

        if (saherTime.isNotEmpty() && iftarTime.isNotEmpty()) {
            val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
            val now = sdf.format(Date())

            val nextMainEvent = when {
                saherTime > now -> PrayerTime("Sehri", saherTime)
                iftarTime > now -> PrayerTime("Iftar", iftarTime)
                else -> PrayerTime("Sehri", saherTime) // Simplified tomorrow
            }

            prefs.edit().apply {
                putString("nextEvent", nextMainEvent.name)
                putString("nextTime", getCountdownString(nextMainEvent.time))
                apply()
            }

            NoorWidget().updateAll(context)
        }

        return Result.success()
    }

    private fun getCountdownString(targetTime24h: String): String {
        try {
            val now = Calendar.getInstance()
            val target = Calendar.getInstance()
            val parts = targetTime24h.split(":")
            target.set(Calendar.HOUR_OF_DAY, parts[0].toInt())
            target.set(Calendar.MINUTE, parts[1].toInt())
            target.set(Calendar.SECOND, 0)

            if (target.before(now)) {
                target.add(Calendar.DAY_OF_YEAR, 1)
            }

            val diff = target.timeInMillis - now.timeInMillis
            val hours = TimeUnit.MILLISECONDS.toHours(diff)
            val minutes = TimeUnit.MILLISECONDS.toMinutes(diff) % 60

            return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
        } catch (e: Exception) {
            return ""
        }
    }

    companion object {
        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<WidgetUpdateWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.NOT_REQUIRED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "WidgetUpdateWorker",
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}

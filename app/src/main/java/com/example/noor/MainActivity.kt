package com.example.noor

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.NightlightRound
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.glance.appwidget.updateAll
import com.example.noor.ui.theme.NoorTheme
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val prefs = getSharedPreferences("NoorTheme", Context.MODE_PRIVATE)
            var isDarkMode by remember { mutableStateOf(prefs.getBoolean("darkMode", true)) }

            NoorTheme(darkTheme = isDarkMode) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    RamadanScreen(isDarkMode) { darkMode ->
                        isDarkMode = darkMode
                        prefs.edit().putBoolean("darkMode", darkMode).apply()
                    }
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun RamadanScreen(isDarkMode: Boolean, onThemeChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var prayerTimes by remember { mutableStateOf<List<PrayerTime>>(emptyList()) }
    var locationName by remember { mutableStateOf("Fetching location...") }
    var nextEvent by remember { mutableStateOf<PrayerTime?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    fun updateWidget(times: List<PrayerTime>) {
        val prefs = context.getSharedPreferences("NoorPrefs", Context.MODE_PRIVATE)
        val saherTime = times.find { it.name.contains("Saher") }?.time ?: ""
        val iftarTime = times.find { it.name.contains("Iftar") }?.time ?: ""
        
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        val now = sdf.format(Date())
        
        val nextMainEvent = when {
            saherTime > now -> PrayerTime("Sehri", saherTime)
            iftarTime > now -> PrayerTime("Iftar", iftarTime)
            else -> PrayerTime("Sehri", saherTime) // Tomorrow
        }
        
        prefs.edit().apply {
            putString("saher", formatToAmPm(saherTime))
            putString("iftar", formatToAmPm(iftarTime))
            putString("saher_raw", saherTime)
            putString("iftar_raw", iftarTime)
            putString("nextEvent", nextMainEvent.name)
            putString("nextTime", getCountdownString(nextMainEvent.time))
            apply()
        }

        scope.launch {
            NoorWidget().updateAll(context)
        }
        
        // Start background worker for periodic updates
        WidgetUpdateWorker.enqueue(context)
    }

    fun scheduleNotification(time24h: String, title: String, message: String, id: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!alarmManager.canScheduleExactAlarms()) {
                val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                context.startActivity(intent)
                return
            }
        }

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

        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                calendar.timeInMillis,
                pendingIntent
            )
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val locationGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        val notificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions[Manifest.permission.POST_NOTIFICATIONS] ?: false
        } else {
            true
        }

        if (locationGranted) {
            fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                if (location != null) {
                    locationName = "Location Found"
                    fetchPrayerTimes(location.latitude, location.longitude) { times ->
                        prayerTimes = times
                        nextEvent = calculateNextEvent(times)
                        
                        updateWidget(times)
                        
                        val saherTime = times.find { it.name.contains("Saher") }?.time ?: ""
                        val iftarTime = times.find { it.name.contains("Iftar") }?.time ?: ""
                        
                        if (saherTime.isNotEmpty() && notificationGranted) scheduleNotification(saherTime, "Sehri Time", "It's time for Sehri!", 1001)
                        if (iftarTime.isNotEmpty() && notificationGranted) scheduleNotification(iftarTime, "Iftar Time", "It's time for Iftar!", 1002)

                        isLoading = false
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        val permissions = mutableListOf(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Centered title
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.noor_logo),
                contentDescription = "Logo",
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Noor",
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }

        Text(
            text = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date()),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.secondary
        )

        Text(
            text = locationName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )

        Spacer(modifier = Modifier.height(24.dp))

        if (isLoading) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        } else {
            // Display only the next Sehri or Iftar event at the top with countdown
            val saherTime = prayerTimes.find { it.name.contains("Saher") }?.time ?: ""
            val iftarTime = prayerTimes.find { it.name.contains("Iftar") }?.time ?: ""
            val saherEvent = PrayerTime("Sehri", saherTime)
            val iftarEvent = PrayerTime("Iftar", iftarTime)

            // Determine which event is next
            val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
            val now = sdf.format(Date())
            val nextMainEvent = when {
                saherTime > now -> saherEvent
                iftarTime > now -> iftarEvent
                else -> iftarEvent // Tomorrow's iftar
            }

            // Calculate countdown hours for the next event
            val countdownHours = getCountdownString(nextMainEvent.time)

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Column(
                    modifier = Modifier
                        .padding(24.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Next: ${nextMainEvent.name}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = formatToAmPm(nextMainEvent.time),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (countdownHours.isNotEmpty()) {
                        Text(
                            text = "in $countdownHours",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Upcoming Prayer Times",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.align(Alignment.Start),
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Get next 5 prayer times (today's remaining + tomorrow's if needed)

            // Get remaining times from today
            val todayRemaining = prayerTimes.filter { it.time > now }

            // Get times for tomorrow if we need more to reach 5
            val nextDayTimes = if (todayRemaining.size < 5) {
                prayerTimes.take(5 - todayRemaining.size).map {
                    it.copy(isTomorrow = true)
                }
            } else {
                emptyList()
            }

            val next5Times = (todayRemaining + nextDayTimes).take(5)

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (next5Times.isEmpty()) {
                    item {
                        Text(
                            text = "No prayer times available.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                } else {
                    items(next5Times, key = { "${it.name}-${it.isTomorrow}" }) { prayer ->
                        PrayerRow(prayer)
                    }
                }
            }
        }

        // Spacer to push button to bottom
        Spacer(modifier = Modifier.weight(1f))

        // Theme toggle button at bottom right
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.End
        ) {
            IconButton(
                onClick = { onThemeChange(!isDarkMode) },
                modifier = Modifier.size(50.dp)
            ) {
                Icon(
                    imageVector = if (isDarkMode) Icons.Default.LightMode else Icons.Default.NightlightRound,
                    contentDescription = "Toggle Theme",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}

@Composable
fun PrayerRow(prayer: PrayerTime) {
    val isMain = prayer.name.contains("Iftar") || prayer.name.contains("Saher")
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isMain) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(prayer.name, fontWeight = if (isMain) FontWeight.Bold else FontWeight.Normal)
                    if (prayer.isTomorrow) {
                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.clip(RoundedCornerShape(4.dp))
                        ) {
                            Text(
                                text = "Tomorrow",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
            Text(formatToAmPm(prayer.time), fontWeight = FontWeight.Bold)
        }
    }
}

fun formatToAmPm(time24h: String): String {
    if (time24h.isEmpty()) return ""
    return try {
        val sdf24 = SimpleDateFormat("HH:mm", Locale.getDefault())
        val sdf12 = SimpleDateFormat("hh:mm a", Locale.getDefault())
        val date = sdf24.parse(time24h)
        date?.let { sdf12.format(it) } ?: time24h
    } catch (e: Exception) {
        time24h
    }
}

fun getCountdownString(targetTime24h: String): String {
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
        val hours = java.util.concurrent.TimeUnit.MILLISECONDS.toHours(diff)
        val minutes = java.util.concurrent.TimeUnit.MILLISECONDS.toMinutes(diff) % 60

        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    } catch (e: Exception) {
        return ""
    }
}

fun fetchPrayerTimes(lat: Double, lon: Double, onResult: (List<PrayerTime>) -> Unit) {
    MainScope().launch(Dispatchers.Default) {
        try {
            val url = URL("https://api.aladhan.com/v1/timings?latitude=$lat&longitude=$lon&method=2&school=1")
            val connection = url.openConnection()
            val text = connection.getInputStream().bufferedReader().readText()
            val timings = JSONObject(text).getJSONObject("data").getJSONObject("timings")

            val list = listOf(
                PrayerTime("Saher / Fajr", timings.getString("Fajr")),
                PrayerTime("Dhuhr", timings.getString("Dhuhr")),
                PrayerTime("Asr", timings.getString("Asr")),
                PrayerTime("Iftar / Maghrib", timings.getString("Maghrib")),
                PrayerTime("Isha", timings.getString("Isha"))
            )
            withContext(Dispatchers.Main) {
                onResult(list)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

fun calculateNextEvent(times: List<PrayerTime>): PrayerTime? {
    if (times.isEmpty()) return null
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    val now = sdf.format(Date())

    val next = times.filter { it.time > now }.minByOrNull { it.time }

    return next ?: times.firstOrNull()?.copy(isTomorrow = true)
}

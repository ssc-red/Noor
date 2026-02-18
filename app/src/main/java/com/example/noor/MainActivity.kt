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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.NightlightRound
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
    var showFutureDatesModal by remember { mutableStateOf(false) }

    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    fun updateWidget(times: List<PrayerTime>) {
        val prefs = context.getSharedPreferences("NoorPrefs", Context.MODE_PRIVATE)
        val sehriTime = times.find { it.name.contains("Sehri") }?.time ?: ""
        val iftarTime = times.find { it.name.contains("Iftar") }?.time ?: ""
        
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        val now = sdf.format(Date())
        
        val nextMainEvent = when {
            sehriTime > now -> PrayerTime("Sehri", sehriTime)
            iftarTime > now -> PrayerTime("Iftar", iftarTime)
            else -> PrayerTime("Sehri", sehriTime) 
        }
        
        prefs.edit().apply {
            putString("sehri", formatToAmPm(sehriTime))
            putString("iftar", formatToAmPm(iftarTime))
            putString("sehri_raw", sehriTime)
            putString("iftar_raw", iftarTime)
            putString("nextEvent", nextMainEvent.name)
            putString("nextTime", getCountdownString(nextMainEvent.time))
            apply()
        }

        scope.launch {
            NoorWidget().updateAll(context)
        }
        
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
                    val prefs = context.getSharedPreferences("NoorPrefs", Context.MODE_PRIVATE)
                    prefs.edit().apply {
                        putString("lastLat", location.latitude.toString())
                        putString("lastLon", location.longitude.toString())
                        apply()
                    }

                    fetchPrayerTimes(location.latitude, location.longitude) { times ->
                        prayerTimes = times
                        nextEvent = calculateNextEvent(times)
                        updateWidget(times)
                        
                        val sehriTime = times.find { it.name.contains("Sehri") }?.time ?: ""
                        val iftarTime = times.find { it.name.contains("Iftar") }?.time ?: ""
                        
                        if (sehriTime.isNotEmpty() && notificationGranted) scheduleNotification(sehriTime, "Sehri Time", "It's time for Sehri!", 1001)
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
            val sehriTime = prayerTimes.find { it.name.contains("Sehri") }?.time ?: ""
            val iftarTime = prayerTimes.find { it.name.contains("Iftar") }?.time ?: ""
            val sehriEvent = PrayerTime("Sehri", sehriTime)
            val iftarEvent = PrayerTime("Iftar", iftarTime)

            val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
            val now = sdf.format(Date())
            val nextMainEvent = when {
                sehriTime > now -> sehriEvent
                iftarTime > now -> iftarEvent
                else -> iftarEvent 
            }

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

            val todayRemaining = prayerTimes.filter { it.time > now }
            val nextDayTimes = if (todayRemaining.size < 5) {
                prayerTimes.take(5 - todayRemaining.size).map { it.copy(isTomorrow = true) }
            } else emptyList()

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

        Spacer(modifier = Modifier.weight(1f))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { showFutureDatesModal = true },
                modifier = Modifier.size(50.dp)
            ) {
                Text(text = "📅", style = MaterialTheme.typography.headlineSmall)
            }
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = {
                    onThemeChange(!isDarkMode)
                    MainScope().launch { NoorWidget().updateAll(context) }
                },
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

    if (showFutureDatesModal) {
        FutureDatesDialog(
            onDismiss = { showFutureDatesModal = false }
        )
    }
}

@Composable
fun PrayerRow(prayer: PrayerTime) {
    val isMain = prayer.name.contains("Iftar") || prayer.name.contains("Sehri")
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(prayer.name, fontWeight = if (isMain) FontWeight.Bold else FontWeight.Normal)
                if (prayer.isTomorrow) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(4.dp)
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
            Text(formatToAmPm(prayer.time), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun FutureDatesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("NoorPrefs", Context.MODE_PRIVATE)
    val lastLat = prefs.getString("lastLat", "0.0")?.toDoubleOrNull() ?: 0.0
    val lastLon = prefs.getString("lastLon", "0.0")?.toDoubleOrNull() ?: 0.0
    
    var futureTimes by remember { mutableStateOf<List<DayPrayerTimes>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        fetchRamadanPrayerTimes(lastLat, lastLon) { times ->
            futureTimes = times
            isLoading = false
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.85f).clip(RoundedCornerShape(24.dp)),
            color = MaterialTheme.colorScheme.background,
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("Ramadan Timetable", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text("Remaining days", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close", tint = MaterialTheme.colorScheme.primary) }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (isLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = MaterialTheme.colorScheme.primary) }
                } else if (futureTimes.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No data available. Check connection.", color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    ) {
                        Row(modifier = Modifier.padding(vertical = 10.dp, horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Day", modifier = Modifier.weight(0.7f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                            Text("Date", modifier = Modifier.weight(1.3f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                            Text("Sehri", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge)
                            Text("Iftar", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, textAlign = TextAlign.End, style = MaterialTheme.typography.labelLarge)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    LazyColumn(modifier = Modifier.weight(1f)) {
                        itemsIndexed(futureTimes) { index, day ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(if (index % 2 != 0) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f) else Color.Transparent)
                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(day.dayLabel, modifier = Modifier.weight(0.7f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                Text(day.dateLabel, modifier = Modifier.weight(1.3f), style = MaterialTheme.typography.bodySmall)
                                Text(formatToAmPm(day.sehri), modifier = Modifier.weight(1f), textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                                Text(formatToAmPm(day.iftar), modifier = Modifier.weight(1f), textAlign = TextAlign.End, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(12.dp)) { Text("Done", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

fun fetchRamadanPrayerTimes(lat: Double, lon: Double, onResult: (List<DayPrayerTimes>) -> Unit) {
    MainScope().launch(Dispatchers.IO) {
        val results = mutableListOf<DayPrayerTimes>()
        try {
            // 1. Get current Hijri date to find Ramadan day
            val todayUrl = URL("https://api.aladhan.com/v1/timings?latitude=$lat&longitude=$lon&method=2&school=1")
            val todayConnection = todayUrl.openConnection()
            val todayText = todayConnection.getInputStream().bufferedReader().readText()
            val hijriData = JSONObject(todayText).getJSONObject("data").getJSONObject("date").getJSONObject("hijri")
            val currentHijriDay = hijriData.getString("day").toInt()
            val currentHijriMonth = hijriData.getJSONObject("month").getInt("number")
            val currentHijriYear = hijriData.getString("year").toInt()

            // If we are past Ramadan (month 9), we target the next year's Ramadan
            val targetYear = if (currentHijriMonth > 9) currentHijriYear + 1 else currentHijriYear

            // Correct official endpoint for Hijri Calendar By Lat Long
            // Documentation: https://aladhan.com/prayer-times-api#hijriCalendarByLatLong
            val calendarUrl = URL("https://api.aladhan.com/v1/hijriCalendar?latitude=$lat&longitude=$lon&method=2&month=9&year=$targetYear")
            val calendarConnection = calendarUrl.openConnection()
            val responseText = calendarConnection.getInputStream().bufferedReader().readText()
            val dataArray = JSONObject(responseText).getJSONArray("data")

            for (i in 0 until dataArray.length()) {
                val dayData = dataArray.getJSONObject(i)
                val dayOfMonth = dayData.getJSONObject("date").getJSONObject("hijri").getString("day").toInt()
                if (currentHijriMonth != 9 || dayOfMonth >= currentHijriDay) {
                    val readableDate = dayData.getJSONObject("date").getString("readable")
                    val timings = dayData.getJSONObject("timings")

                    results.add(DayPrayerTimes(
                        dayLabel = "$dayOfMonth",
                        dateLabel = readableDate.split(" ").take(2).joinToString(" "),
                        sehri = timings.getString("Fajr"),
                        iftar = timings.getString("Maghrib")
                    ))
                }
            }
            withContext(Dispatchers.Main) { onResult(results) }
        } catch (e: Exception) { 
            e.printStackTrace()
            withContext(Dispatchers.Main) { onResult(emptyList()) }
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
    } catch (e: Exception) { time24h }
}

fun getCountdownString(targetTime24h: String): String {
    try {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance()
        val parts = targetTime24h.split(":")
        target.set(Calendar.HOUR_OF_DAY, parts[0].toInt())
        target.set(Calendar.MINUTE, parts[1].toInt())
        target.set(Calendar.SECOND, 0)
        if (target.before(Calendar.getInstance())) target.add(Calendar.DAY_OF_YEAR, 1)
        val diff = target.timeInMillis - now.timeInMillis
        val hours = java.util.concurrent.TimeUnit.MILLISECONDS.toHours(diff)
        val minutes = java.util.concurrent.TimeUnit.MILLISECONDS.toMinutes(diff) % 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    } catch (e: Exception) { return "" }
}

fun fetchPrayerTimes(lat: Double, lon: Double, onResult: (List<PrayerTime>) -> Unit) {
    MainScope().launch(Dispatchers.IO) {
        try {
            val url = URL("https://api.aladhan.com/v1/timings?latitude=$lat&longitude=$lon&method=2&school=1")
            val connection = url.openConnection()
            val text = connection.getInputStream().bufferedReader().readText()
            val timings = JSONObject(text).getJSONObject("data").getJSONObject("timings")
            val list = listOf(
                PrayerTime("Sehri / Fajr", timings.getString("Fajr")),
                PrayerTime("Dhuhr", timings.getString("Dhuhr")),
                PrayerTime("Asr", timings.getString("Asr")),
                PrayerTime("Iftar / Maghrib", timings.getString("Maghrib")),
                PrayerTime("Isha", timings.getString("Isha"))
            )
            withContext(Dispatchers.Main) { onResult(list) }
        } catch (e: Exception) { e.printStackTrace() }
    }
}

fun calculateNextEvent(times: List<PrayerTime>): PrayerTime? {
    if (times.isEmpty()) return null
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    val now = sdf.format(Date())
    return times.filter { it.time > now }.minByOrNull { it.time } ?: times.firstOrNull()?.copy(isTomorrow = true)
}

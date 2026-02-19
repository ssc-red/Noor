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
        val sehriTime = times.find { it.name.contains("Sehri") && !it.isTomorrow }?.time ?: ""
        val iftarTime = times.find { it.name.contains("Iftar") && !it.isTomorrow }?.time ?: ""
        
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        val now = sdf.format(Date())
        
        val nextMainEvent = times
            .filter { it.name.contains("Sehri") || it.name.contains("Iftar") }
            .firstOrNull { it.isTomorrow || it.time > now }
            ?: times.first { it.isTomorrow && it.name.contains("Sehri") }
        
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
                        
                        val sehriTime = times.find { it.name.contains("Sehri") && !it.isTomorrow }?.time ?: ""
                        val iftarTime = times.find { it.name.contains("Iftar") && !it.isTomorrow }?.time ?: ""
                        
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
            val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
            val now = sdf.format(Date())
            
            val nextMainEvent = prayerTimes
                .filter { it.name.contains("Sehri") || it.name.contains("Iftar") }
                .firstOrNull { it.isTomorrow || it.time > now }
                ?: prayerTimes.first { it.isTomorrow && it.name.contains("Sehri") }

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

            val next5Times = prayerTimes.filter { it.isTomorrow || it.time > now }.take(5)

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
    var lat by remember { mutableStateOf(prefs.getString("lastLat", null)?.toDoubleOrNull() ?: 0.0) }
    var lon by remember { mutableStateOf(prefs.getString("lastLon", null)?.toDoubleOrNull() ?: 0.0) }

    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    var futureTimes by remember { mutableStateOf<List<DayPrayerTimes>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var locationAttempted by remember { mutableStateOf(false) }
    var lastError by remember { mutableStateOf<String?>(null) }

    val dialogScope = rememberCoroutineScope()

    suspend fun doFetch(latVal: Double, lonVal: Double) {
        isLoading = true
        lastError = null
        try {
            fetchRamadanPrayerTimes(latVal, lonVal) { times ->
                futureTimes = times
                isLoading = false
            }
        } catch (e: Exception) {
            lastError = e.message ?: "Unknown error"
            isLoading = false
        }
    }

    LaunchedEffect(lat, lon) {
        // If we have valid coords, fetch immediately
        if (lat != 0.0 || lon != 0.0) {
            doFetch(lat, lon)
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

                // If no saved location, show action to use device location or instructions
                if ((lat == 0.0 && lon == 0.0) && !isLoading) {
                    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Location not available", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("To load Ramadan timetable we need your location. You can allow the app to use your last known device location or enter coordinates in settings.", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            Button(onClick = {
                                // Try to get last known location if permission granted
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                                    fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                                        if (location != null) {
                                            lat = location.latitude
                                            lon = location.longitude
                                            prefs.edit().apply { putString("lastLat", lat.toString()); putString("lastLon", lon.toString()); apply() }
                                            // trigger fetch via LaunchedEffect
                                        } else {
                                            lastError = "No last known location available"
                                        }
                                    }.addOnFailureListener { ex -> lastError = ex.message }
                                } else {
                                    // Request permission via settings (we are in dialog, better to instruct)
                                    lastError = "Location permission not granted. Please enable location permission in app settings."
                                }
                            }) {
                                Text("Use device location")
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Button(onClick = {
                                // Retry fetch with defaults (may return empty) or instruct user
                                dialogScope.launch { doFetch(lat, lon) }
                            }) {
                                Text("Retry")
                            }
                        }

                        lastError?.let { err ->
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(text = err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } else {
                    // Show loading / results
                    if (isLoading) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = MaterialTheme.colorScheme.primary) }
                    } else if (futureTimes.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("No Ramadan data found for this location.", color = MaterialTheme.colorScheme.error)
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(onClick = { dialogScope.launch { doFetch(lat, lon) } }) { Text("Retry") }
                            }
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
            // Scan the next 90 Gregorian days; collect days whose hijri month == 9 (Ramadan)
            val dateFormat = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault())
            var collecting = false
            for (offset in 0..89) {
                try {
                    val d = Calendar.getInstance()
                    d.add(Calendar.DAY_OF_YEAR, offset)
                    val dateStr = dateFormat.format(d.time)

                    val url = URL("https://api.aladhan.com/v1/timings/$dateStr?latitude=$lat&longitude=$lon&method=2&school=1")
                    val text = url.readText()
                    val data = JSONObject(text).getJSONObject("data")

                    val hijri = data.getJSONObject("date").getJSONObject("hijri")
                    val hijriMonth = try { hijri.getJSONObject("month").getInt("number") } catch (e: Exception) { hijri.getInt("month") }
                    val hijriDay = hijri.getString("day").toIntOrNull() ?: continue

                    val readableDate = data.getJSONObject("date").getString("readable")
                    val timings = data.getJSONObject("timings")

                    if (hijriMonth == 9) {
                        collecting = true
                        results.add(
                            DayPrayerTimes(
                                dayLabel = "$hijriDay",
                                dateLabel = readableDate.split(" ").take(2).joinToString(" "),
                                sehri = timings.getString("Fajr").split(" ").firstOrNull() ?: timings.getString("Fajr"),
                                iftar = timings.getString("Maghrib").split(" ").firstOrNull() ?: timings.getString("Maghrib")
                            )
                        )
                    } else if (collecting) {
                        // Ramadan started earlier and now month changed -> done
                        break
                    }
                } catch (inner: Exception) {
                    // skip this date on error but continue scanning
                    inner.printStackTrace()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        withContext(Dispatchers.Main) {
            onResult(results)
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
            val sdf = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault())
            val today = Calendar.getInstance()
            val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }

            val todayList = fetchTimingsForDate(lat, lon, sdf.format(today.time))
            val tomorrowList = fetchTimingsForDate(lat, lon, sdf.format(tomorrow.time)).map { it.copy(isTomorrow = true) }

            withContext(Dispatchers.Main) {
                onResult(todayList + tomorrowList)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

private fun fetchTimingsForDate(lat: Double, lon: Double, date: String): List<PrayerTime> {
    val url = URL("https://api.aladhan.com/v1/timings/$date?latitude=$lat&longitude=$lon&method=2&school=1")
    val text = url.readText()
    val timings = JSONObject(text).getJSONObject("data").getJSONObject("timings")
    return listOf(
        PrayerTime("Sehri / Fajr", timings.getString("Fajr")),
        PrayerTime("Dhuhr", timings.getString("Dhuhr")),
        PrayerTime("Asr", timings.getString("Asr")),
        PrayerTime("Iftar / Maghrib", timings.getString("Maghrib")),
        PrayerTime("Isha", timings.getString("Isha"))
    )
}

fun calculateNextEvent(times: List<PrayerTime>): PrayerTime? {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    val now = sdf.format(Date())
    return times.firstOrNull { it.isTomorrow || it.time > now }
}

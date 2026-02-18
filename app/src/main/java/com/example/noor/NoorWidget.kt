package com.example.noor

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.example.noor.ui.theme.DeepGreen
import com.example.noor.ui.theme.PureWhite
import com.example.noor.ui.theme.DeepRed
import com.example.noor.ui.theme.DarkBg
import com.example.noor.ui.theme.DarkSurface
import com.example.noor.ui.theme.LightBg
import com.example.noor.ui.theme.LightSurface
import com.example.noor.ui.theme.DarkGrey

class NoorWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val noorPrefs = context.getSharedPreferences("NoorPrefs", Context.MODE_PRIVATE)
        val themePrefs = context.getSharedPreferences("NoorTheme", Context.MODE_PRIVATE)

        val sehri = noorPrefs.getString("sehri", "04:30 AM") ?: "04:30 AM"
        val iftar = noorPrefs.getString("iftar", "06:45 PM") ?: "06:45 PM"
        val nextEvent = noorPrefs.getString("nextEvent", "Iftar") ?: "Iftar"
        val nextTime = noorPrefs.getString("nextTime", "06:45 PM") ?: "06:45 PM"

        // Read app's theme preference - defaults to dark mode (true)
        val isDarkMode = themePrefs.getBoolean("darkMode", true)

        provideContent {
            WidgetContent(sehri, iftar, nextEvent, nextTime, isDarkMode)
        }
    }


    @Composable
    private fun WidgetContent(sehri: String, iftar: String, nextEvent: String, nextTime: String, isDarkMode: Boolean) {
        val bgColor = if (isDarkMode) DarkBg else LightBg
        val cardColor = if (isDarkMode) DarkSurface else LightSurface
        val textColor = if (isDarkMode) PureWhite else DarkGrey
        val accentColor = DeepRed
        val labelColor = if (isDarkMode) DeepGreen else DeepGreen

        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(bgColor)
                .cornerRadius(16.dp)
                .padding(12.dp)
                .clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.Top,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Row(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalAlignment = Alignment.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Noor",
                    style = TextStyle(
                        color = ColorProvider(accentColor),
                        fontWeight = FontWeight.Bold
                    )
                )
            }

            // Main countdown card - Modern design with vibrant red
            Box(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .background(accentColor)
                    .cornerRadius(12.dp)
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = nextEvent,
                        style = TextStyle(
                            color = ColorProvider(PureWhite),
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Spacer(modifier = GlanceModifier.height(4.dp))
                    Text(
                        text = nextTime,
                        style = TextStyle(
                            color = ColorProvider(PureWhite),
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            }

            Spacer(modifier = GlanceModifier.height(12.dp))

            // Prayer times row - Two cards side by side
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Sehri Card
                Box(
                    modifier = GlanceModifier
                        .defaultWeight()
                        .background(cardColor)
                        .cornerRadius(12.dp)
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Sehri",
                            style = TextStyle(
                                color = ColorProvider(labelColor),
                                fontWeight = FontWeight.Bold
                            )
                        )
                        Spacer(modifier = GlanceModifier.height(4.dp))
                        Text(
                            text = sehri,
                            style = TextStyle(
                                color = ColorProvider(textColor),
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                }

                Spacer(modifier = GlanceModifier.width(8.dp))

                // Iftar Card
                Box(
                    modifier = GlanceModifier
                        .defaultWeight()
                        .background(cardColor)
                        .cornerRadius(12.dp)
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Iftar",
                            style = TextStyle(
                                color = ColorProvider(labelColor),
                                fontWeight = FontWeight.Bold
                            )
                        )
                        Spacer(modifier = GlanceModifier.height(4.dp))
                        Text(
                            text = iftar,
                            style = TextStyle(
                                color = ColorProvider(textColor),
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                }
            }
        }
    }
}

class NoorWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NoorWidget()
}

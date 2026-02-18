# 🌙 Noor - Ramadan Prayer Times App

A beautiful, modern Android application for tracking Ramadan prayer times with real-time notifications, widget support, and an intuitive user interface.

![License](https://img.shields.io/badge/License-MIT-blue.svg)
![Android](https://img.shields.io/badge/Android-8.0+-green.svg)
![Language](https://img.shields.io/badge/Language-Kotlin-purple.svg)

## ✨ Features

### 🕐 Prayer Time Tracking
- **Real-time prayer times** fetched from Islamic API based on your location
- **GPS-based location detection** for accurate timing calculations
- **Upcoming prayer times** displayed with upcoming schedule
- **Countdown timers** showing hours and minutes until the next prayer

### 🔔 Smart Notifications
- **Automatic notifications** for Sehri (Pre-dawn) and Iftar (Breaking fast) times
- **Background updates** ensuring timely reminders even when app is closed

### 📱 Home Screen Widget
- **Always-on-display widget** showing next prayer time and countdown
- **Automatic theme sync** with app's light/dark mode preference
- **Quick access** to Sehri and Iftar times from home screen
- **Real-time updates** through background worker

### 🌍 Location Services
- **Automatic location detection** using device GPS
- **Fallback mechanisms** when location is unavailable

## 📋 Requirements

- **Android 8.0 (API level 26)** or higher
- **Location permissions** (Coarse location for GPS detection)
- **Notification permissions** (Android 13+)
- **Internet connection** for fetching prayer times

## 🚀 Installation

### From Source

1. **Clone the repository**
   ```bash
   git clone https://github.com/ssc-red/Noor.git
   cd Noor
   ```

2. **Open in Android Studio**
   - Launch Android Studio
   - Select "Open an Existing Project"
   - Navigate to the Noor directory

3. **Build the project**
   ```bash
   ./gradlew build
   ```

4. **Run on device or emulator**
   ```bash
   ./gradlew installDebug
   ```

### From APK

- Download the latest release APK from the [Releases](https://github.com/ssc-red/Noor/releases) page
- Install on your Android device

**Made with ❤️ during Ramadan for the Muslim community**



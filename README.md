# Karoo to MQTT

This application allows you to monitor the battery levels of your Hammerhead Karoo device and all its paired sensors (Heart Rate, Power, Shifting, etc.) by publishing them to an MQTT broker.

## Features

- **Battery Monitoring**: Tracks Karoo internal battery percentage and string-based status for all paired sensors (Full, GOOD, OK, LOW, CRITICAL).
- **MQTT Discovery**: Publishes a retained discovery/config message per sensor, so compatible MQTT tooling can auto-create entities without manual configuration.
- **Secure Credential Storage**: Uses Android Keystore and `EncryptedSharedPreferences` to securely store your MQTT credentials.
- **Home Wi-Fi Triggering**: A background foreground service watches for connectivity changes in real time and publishes to MQTT within seconds of connecting to the **configured home Wi-Fi SSID**. Shows a low-priority persistent notification while active (required for the service to keep running in the background).
- **High-Contrast UI**: Redesigned layout optimized for the Karoo's screen with gear-icon settings and unified scrolling.
- **Refresh Functionality**: Manually poll all connected sensors for their latest battery levels with a single tap.
- **On-Device Sync Logs**: A "View Sync Logs" button shows a persisted log of sync attempts and background-service activity, so you can diagnose issues directly on the Karoo's screen without adb.

## Setup Instructions

1. **Deploy to Karoo**: Sideload the APK or deploy via Android Studio to your Karoo device.
2. **Configure MQTT**:
   - Open the app on your Karoo.
   - Tap the **cog icon** in the top right to open Settings.
   - Enter your **MQTT Broker IP**.
   - Enter your **MQTT Username** and **Password** (stored securely).
   - Tap **Save Settings**.
3. **Grant Permissions**: When prompted, grant **Location Permission**, including **"Allow all the time"** if the app directs you to system Settings for it. Android requires background location access to read the connected Wi-Fi network's name from a background job — without it, home Wi-Fi detection silently never matches.
4. **Sync**: Tap **Sync Now** to verify the connection (this always runs regardless of network, useful for testing). Your sensors will be published to the broker under a new device named after your Karoo's serial number. Automatic syncs only happen when connected to the home Wi-Fi SSID configured in Settings.

## Technical Details

- **MQTT Discovery**: Automatically publishes sensor configuration to `karoo/sensor/.../config`.
- **Background Trigger**: A foreground `Service` registers a `ConnectivityManager.NetworkCallback` and checks the current SSID against the configured home Wi-Fi as soon as a Wi-Fi network connects, then enqueues a one-off `WorkManager` job (`MqttWorker`) to do the actual publish. Restarted after reboot and if the process is killed. Manifest-declared connectivity broadcast receivers are not delivered on Android 8.0+ (API 26+), so this replaces an earlier, non-functional `BroadcastReceiver`-based trigger (and a later periodic-`WorkManager` attempt, which had a worst-case ~15-minute delay).
- **Diagnostics**: If sync isn't happening, check **View Sync Logs** in Settings first — it shows whether the background service is running, whether it's seeing your home network, and why any sync attempt failed (e.g. MQTT connection refused, Karoo system service not reachable).
- **Internal Battery**: Polled directly from the Android system for high-precision real-time tracking while charging.
- **External Sensors**: Monitored via the Hammerhead Extension SDK battery stream.

## Developer Setup

To build this project, you need to provide credentials for the Hammerhead Extension SDK.

1. Create a GitHub Personal Access Token (PAT) with `read:packages` scope.
2. Add the following to your `local.properties` file:
   ```properties
   gpr.user=YOUR_GITHUB_USERNAME
   gpr.key=YOUR_GITHUB_TOKEN
   ```

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

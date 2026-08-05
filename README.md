# Karoo to HA Integration

This application allows you to monitor the battery levels of your Hammerhead Karoo device and all its paired sensors (Heart Rate, Power, Shifting, etc.) directly in Home Assistant.

## Features

- **Battery Monitoring**: Tracks Karoo internal battery percentage and string-based status for all paired sensors (Full, GOOD, OK, LOW, CRITICAL).
- **Home Assistant Integration**: Uses MQTT Discovery to automatically create entities in HA.
- **Secure Credential Storage**: Uses Android Keystore and `EncryptedSharedPreferences` to securely store your MQTT credentials.
- **Universal Wi-Fi Triggering**: Automatically syncs data whenever the Karoo establishes a valid connection to **any** Wi-Fi network.
- **High-Contrast UI**: Redesigned layout optimized for the Karoo's screen with gear-icon settings and unified scrolling.
- **Refresh Functionality**: Manually poll all connected sensors for their latest battery levels with a single tap.

## Setup Instructions

1. **Deploy to Karoo**: Sideload the APK or deploy via Android Studio to your Karoo device.
2. **Configure MQTT**:
   - Open the app on your Karoo.
   - Tap the **cog icon** in the top right to open Settings.
   - Enter your **MQTT Broker IP**.
   - Enter your **MQTT Username** and **Password** (stored securely).
   - Tap **Save Settings**.
3. **Grant Permissions**: When prompted, grant **Location Permission**. This is required by Android for Wi-Fi and network detection.
4. **Sync**: Tap **Sync to HA Now** to verify the connection. Your sensors will appear in Home Assistant under a new device named after your Karoo's serial number.

## Technical Details

- **MQTT Discovery**: Automatically handles sensor configuration in HA.
- **Background Worker**: Uses `WorkManager` for reliable background syncs triggered by network changes.
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

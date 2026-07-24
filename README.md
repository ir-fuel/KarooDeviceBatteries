# Karoo to Home Assistant Integration

This application allows you to monitor the battery levels of your Hammerhead Karoo device and all its paired sensors (Heart Rate, Power, Shifting, etc.) directly in Home Assistant.

## Features

- **Battery Monitoring**: Tracks Karoo internal battery and all system-paired sensors.
- **Home Assistant Integration**: Uses MQTT Discovery to automatically create entities in HA.
- **Smart Triggering**: Automatically syncs data when the Karoo connects to your designated Home Wi-Fi network.
- **High-Contrast UI**: Optimized for readability on the Karoo's screen.
- **Persistence**: Remembers numerical battery percentages even when sensors are offline.

## Setup Instructions

1. **Deploy to Karoo**: Sideload the APK or deploy via Android Studio to your Karoo device.
2. **Configure MQTT**:
   - Open the app on your Karoo.
   - Tap the gear icon in the top right to open Settings.
   - Enter your **MQTT Broker IP** (e.g., `192.168.1.5`).
   - Enter your **Home WiFi SSID**.
   - Tap **Save Settings**.
3. **Grant Permissions**: When prompted, grant **Location Permission**. This is required for the app to detect your Wi-Fi name.
4. **Sync**: Tap **Sync to Home Assistant Now** to verify the connection. Your sensors should appear in Home Assistant under a new "Karoo" device.

## Developer Setup

To build this project, you need to provide credentials for the Hammerhead Extension SDK (hosted on GitHub Packages).

1. Create a GitHub Personal Access Token (PAT) with `read:packages` scope.
2. Add the following to your `local.properties` file (do not commit this!):
   ```properties
   gpr.user=YOUR_GITHUB_USERNAME
   gpr.key=YOUR_GITHUB_TOKEN
   ```

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

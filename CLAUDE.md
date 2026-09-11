# CLAUDE.md

## Working directory constraint

Do not do anything outside this project's home directory
(`/Users/jorismans/Development/Astus/Karoo/karooDeviceBatteries`). No
searching, no file reads, no file modifications, nothing outside this tree.
The only exception is accessing the Android SDK (e.g. platform sources,
`ANDROID_HOME`/`ANDROID_SDK_ROOT`, installed SDK/build-tools) when needed to
resolve a library or API question — that is allowed.

## What this project is

This is **not** a general-purpose Android app. It is a **Karoo extension** — an
Android app built against the Hammerhead **karoo-ext** SDK (`com.github.hammerheadnav:karoo-ext`,
see `gradle/libs.versions.toml`) that runs *on* a Hammerhead Karoo bike computer
and integrates with the Karoo's own system service (`KarooSystemService`).

Package: `be.astus.karoodevicebatteries`. Purpose: read the Karoo's internal
battery and the battery status of all Bluetooth sensors paired to the Karoo
(heart rate, power meter, shifting, etc.), then publish them to an MQTT broker
(with Home-Assistant-style MQTT discovery messages).

Because the runtime is a Karoo head unit, not a phone/tablet, keep this in mind
when reasoning about behavior or proposing fixes:

- **Device data comes from the Karoo system service**, not from the phone's own
  Bluetooth stack — see `MainActivity.kt` and `MqttWorker.kt` use of
  `KarooSystemService`, `OnStreamState`/`DataType.Type.BATTERY_PERCENT`, and
  `SavedDevices` for the list of paired sensors and their `BatteryStatus`.
- **`karooSystem.connect { }` requires binding to that system service.** If the
  Karoo system service isn't reachable, the connect callback may simply never
  fire — don't assume a hang here is application logic.
- **Networking on-device is real-world Wi-Fi**, e.g. tethering to a phone
  hotspot, home Wi-Fi after a ride, etc. There is no cellular connectivity
  path to assume.
- **Building requires Hammerhead's private GitHub Packages registry** for
  `karoo-ext` — `local.properties` needs `gpr.user` / `gpr.key` (a GitHub PAT
  with `read:packages`). Don't assume `./gradlew build` will work out of the
  box without those credentials configured.
- **Deployment is via sideloading or `adb`/Android Studio directly onto the
  Karoo hardware**, not the Play Store — see README "Setup Instructions".

## Home Wi-Fi sync design (fixed 2026-09-11, revised same day to foreground service)

The original `WifiTriggerReceiver` was a **manifest (static) `<receiver>`**
for `CONNECTIVITY_CHANGE` / `android.net.wifi.STATE_CHANGE`. Since API 26+,
Android does not deliver these implicit broadcasts to manifest-declared
receivers at all, so it never fired — the only thing that ever published to
MQTT was the manual **Sync Now** button. That class and manifest entry were
removed. A first fix used a periodic WorkManager job, but that has a
worst-case ~15-minute delay (WorkManager's enforced minimum period, plus the
job may not be re-evaluated until the *next* window even if you connect right
after the previous check ran) — too slow for "publish shortly after getting
home." Current design reacts to the connectivity event directly instead:

- **`HomeWifiForegroundService`** is a persistent foreground service (small
  low-priority ongoing notification, required for a background component to
  survive Android's process management) that registers a
  `ConnectivityManager.NetworkCallback` for Wi-Fi/internet networks. On
  `onAvailable`/`onCapabilitiesChanged`, once the network is `VALIDATED`, it
  reads the current SSID and compares it to `AppConfig.homeSsid`
  (case-insensitive); on a match it enqueues a one-off `MqttWorker` run
  immediately (seconds, not minutes). It's started from `MainActivity`
  (`onCreate`/`onStart`, idempotent) and from `BootCompletedReceiver` after
  reboot (`BOOT_COMPLETED` is one of the few implicit broadcasts still
  delivered to manifest receivers on modern Android, unlike
  `CONNECTIVITY_CHANGE`). Returns `START_STICKY` so Android attempts to
  restart it if the process is killed.
- The manual **Sync Now** button still enqueues `MqttWorker` directly,
  bypassing the SSID check — useful for testing the MQTT connection
  regardless of network.
- **Reading the SSID from a background context requires
  `ACCESS_BACKGROUND_LOCATION`** (Android 10+), on top of the fine/coarse
  location this app already requested — without it `WifiManager` silently
  returns `"<unknown ssid>"` (treated as "unreadable" by `WifiSsidHelper`) and
  the match would always fail, silently reintroducing "never syncs." On API
  30+, this permission cannot be granted from the same runtime dialog as
  foreground location; the user must enable "Allow all the time" in system
  Settings. `MainActivity` requests it alongside fine/coarse location and
  `POST_NOTIFICATIONS` (API 33+, needed for the foreground service's
  notification to actually show), and shows a tappable hint in the status bar
  (opens the app's system Settings page) whenever background location is
  still missing.

### Diagnosing whether the service is alive / getting killed

`DiagnosticLog` (`DiagnosticLog.kt`) writes timestamped lines to a capped file
in app-private storage (`filesDir/karoo_mqtt_diagnostic.log`), mirrored to
logcat, so behavior can be inspected from the Karoo's own screen — no adb
needed. Tap **View Sync Logs** in the app's Settings panel (`MainActivity`) to
read it (with a **Clear** option).

- `HomeWifiForegroundService` logs `onCreate`/`onStartCommand`/`onDestroy`/
  `onTaskRemoved`, every `NetworkCallback` event, every SSID-match decision,
  and a **heartbeat line every 5 minutes** while alive. A gap in the heartbeat
  timeline larger than ~5 minutes, followed by a fresh `onCreate` line, is
  direct evidence the OS killed the process and it was later restarted
  (`START_STICKY` and/or `BootCompletedReceiver` and/or `MainActivity`
  restarting it on open).
- `MqttWorker` and `MqttManager` log every meaningful step and failure branch
  (missing host, Karoo connect failure/timeout, devices-list timeout, MQTT
  connect/publish/disconnect exceptions) — previously several of these were
  silently swallowed (`catch (e: Exception) {}` with no logging), which made
  "why didn't it publish" undiagnosable from the device itself.

If this ever needs revisiting: a "real" Karoo extension (a `KarooExtension`
`Service` declared with the `io.hammerhead.karooext.KAROO_EXTENSION`
intent-filter, per Hammerhead's SDK) is bound/invoked by Karoo OS based on
declared capabilities (scan/map/fit) tied to ride lifecycle — it is not
documented to stay alive purely to watch for idle Wi-Fi connectivity, so it
wasn't chosen as the trigger mechanism here. This app does not currently use
that extension pattern at all; it only uses `KarooSystemService` as a client.
`HomeWifiForegroundService` is a plain Android `Service`, unrelated to that
SDK-provided extension mechanism.

Evidence a persistent foreground service is viable on Karoo 3 hardware: the
Home Assistant Companion app (which itself runs a long-lived foreground
service for background sync) has been reported running via sideload on Karoo,
and real `KarooExtension`-pattern projects exist for Karoo 3 (e.g.
`markhaines/karoo-garage` on GitHub).

### Verified on real hardware (2026-09-11, Karoo `k24`, Android 12 / API 32)

Tested live via `adb` against a Karoo in developer mode (debug build,
`./gradlew installDebug`). All of the following held up:

- Cold app launch → `NetworkCallback.onAvailable` fires immediately →
  SSID read succeeds (`ACCESS_BACKGROUND_LOCATION` granted via
  `adb shell pm grant`, no on-device Settings detour needed for testing) →
  matches configured home SSID → `MqttWorker` runs → connects to the real
  broker → publishes all sensors (17 devices in this test) → `Result.success()`.
- **Save Settings while already connected to the (new) home SSID** correctly
  triggers an immediate recheck (`HomeWifiForegroundService.recheckIntent()`,
  sent from `MainActivity`'s save handler) rather than waiting for a future
  connectivity transition that may never come. Verified the dedup guard
  (`lastSyncedSsid`) correctly resets on a genuine SSID mismatch and correctly
  skips a redundant re-publish when the SSID hasn't actually changed.
- `HomeWifiForegroundService` is `exported="false"` and confirmed to reject
  external start attempts (`adb shell am start-foreground-service` from the
  shell UID fails with "Requires permission not exported") — the recheck path
  only works because `MainActivity` calls it in-process.
- **Reboot path**: `adb reboot` → `BootCompletedReceiver` fires → starts
  `HomeWifiForegroundService` → matches home Wi-Fi → full MQTT publish
  completes, all within ~7 seconds of boot completing, with **no app UI ever
  opened**. Android logged
  `"Foreground service started from background can not have location/camera/
  microphone access"` for this boot-triggered start (`allowWhileInUsePermissionInFgs=false`
  in `dumpsys`), but the SSID read still succeeded because
  `ACCESS_BACKGROUND_LOCATION` is a standalone app-level grant independent of
  that per-instance FGS flag — this warning is expected and non-blocking as
  long as background location is actually granted.
- The service survived well past the 20-second background-start exemption
  window Android grants to boot-triggered FGS starts (`tempAllowListReason`
  `duration:20000`) — confirmed via a heartbeat at +5 minutes with the same
  `createTime`, i.e. no kill/restart in between.
- **The persisted log file caught boot-sequence lines that `adb logcat -d`
  had already evicted** from the volatile ring buffer under the boot-time
  logging flood (dozens of system services logging at once) — read via
  `adb shell run-as be.astus.karoodevicebatteries cat files/karoo_mqtt_diagnostic.log`
  when logcat alone isn't enough. This is the practical reason
  `DiagnosticLog` writes to a file rather than relying on logcat alone.

Not yet verified: multi-hour idle survival (screen off, actual ride, real
Doze/App Standby bucket transitions over time) and behavior under Hammerhead's
own battery-optimization policy if one exists beyond stock Android — keep
watching the heartbeat log for gaps during real-world use rather than treating
this one clean session as a permanent guarantee.

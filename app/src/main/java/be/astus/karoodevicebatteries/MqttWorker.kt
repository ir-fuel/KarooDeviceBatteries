package be.astus.karoodevicebatteries

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.SavedDevices
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

// Triggered either by HomeWifiForegroundService (after it confirms the device is on
// the configured home Wi-Fi) or directly by the manual "Sync Now" button, which
// bypasses that check on purpose so it always works for testing the MQTT connection.
class MqttWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        DiagnosticLog.i("MqttWorker: starting")
        val config = AppConfig(applicationContext)
        val host = config.mqttHost
        if (host == null) {
            DiagnosticLog.w("MqttWorker: no MQTT host configured, aborting")
            return Result.failure()
        }

        val batteryStore = BatteryStore(applicationContext)

        val karooSystem = KarooSystemService(applicationContext)
        val connectionDeferred = CompletableDeferred<Boolean>()

        karooSystem.connect { connected ->
            connectionDeferred.complete(connected)
        }

        if (!connectionDeferred.await()) {
            DiagnosticLog.w("MqttWorker: failed to connect to Karoo system service, will retry")
            return Result.retry()
        }

        val info = karooSystem.info
        if (info == null) {
            DiagnosticLog.e("MqttWorker: Karoo system connected but info is null, aborting")
            return Result.failure()
        }
        val serial = info.serial

        // Get internal battery from Android system
        val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { ifilter ->
            applicationContext.registerReceiver(null, ifilter)
        }
        val batteryLevel = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val batteryScale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val internalPercentage = if (batteryLevel != -1 && batteryScale != -1) {
            (batteryLevel * 100 / batteryScale.toFloat()).toInt()
        } else {
            -1
        }
        if (internalPercentage != -1) {
            batteryStore.savePercentage("karoo_internal", internalPercentage)
        }

        // 1. Capture All Batteries
        val batteryConsumerId = karooSystem.addConsumer<OnStreamState>(
            params = OnStreamState.StartStreaming(DataType.Type.BATTERY_PERCENT),
            onEvent = { event ->
                val streaming = event.state as? StreamState.Streaming
                val percentage = streaming?.dataPoint?.singleValue?.toInt()
                val sourceId = streaming?.dataPoint?.sourceId

                DiagnosticLog.d("MqttWorker: battery event sourceId=$sourceId, percentage=$percentage")

                if (percentage != null) {
                    val storeId = if (sourceId == null || sourceId == "internal") "karoo_internal" else sourceId
                    batteryStore.savePercentage(storeId, percentage)
                }
            }
        )

        // 2. Capture Saved Devices list
        val devicesDeferred = CompletableDeferred<List<SavedDevices.SavedDevice>>()
        val devicesConsumerId = karooSystem.addConsumer<SavedDevices>(
            onEvent = { event ->
                devicesDeferred.complete(event.devices)
            }
        )

        val devices = withTimeoutOrNull(kotlin.time.Duration.parse("5s")) { devicesDeferred.await() }

        // Wait for sensors to report their battery
        delay(3000)

        karooSystem.removeConsumer(batteryConsumerId)
        karooSystem.removeConsumer(devicesConsumerId)
        karooSystem.disconnect()

        if (devices == null) {
            DiagnosticLog.w("MqttWorker: timed out waiting for saved devices list, will retry")
            return Result.retry()
        }
        DiagnosticLog.i("MqttWorker: got ${devices.size} saved device(s)")

        val mqtt = MqttManager(
            host = host,
            port = config.mqttPort,
            username = config.mqttUsername,
            password = config.mqttPassword
        )
        if (!mqtt.connect()) {
            DiagnosticLog.w("MqttWorker: failed to connect to MQTT broker at $host:${config.mqttPort}, will retry")
            return Result.retry()
        }
        DiagnosticLog.i("MqttWorker: connected to MQTT broker at $host:${config.mqttPort}")

        // Report Internal Battery as Percentage
        val karooPercentage = batteryStore.getPercentage("karoo_internal")
        if (karooPercentage != -1) {
            val discoveryTopic = "karoo/sensor/karoo_$serial/internal_battery/config"
            val configPayload = buildJsonObject {
                put("name", "Karoo Battery")
                put("state_topic", "karoo/$serial/sensor/internal_battery/state")
                put("unit_of_measurement", "%")
                put("device_class", "battery")
                put("unique_id", "karoo_${serial}_internal_battery")
                putJsonObject("device") {
                    put("identifiers", serial)
                    put("name", "Karoo $serial")
                    put("model", "Hammerhead Karoo")
                    put("manufacturer", "Hammerhead")
                }
            }.toString()
            mqtt.publish(discoveryTopic, configPayload, retain = true)
            mqtt.publish("karoo/$serial/sensor/internal_battery/state", karooPercentage.toString())
        }

        devices.forEach { device ->
            val sensorId = device.id.replace(":", "_")
            val rawStatus = device.details.lastBattery?.name ?: "UNKNOWN"
            val status = when (rawStatus.uppercase()) {
                "NEW" -> "Full"
                "GOOD" -> "High"
                "OK" -> "Medium"
                "LOW" -> "Low"
                "CRITICAL" -> "Critical"
                else -> rawStatus
            }

            DiagnosticLog.d("MqttWorker: publishing external sensor '${device.name}', status=$status")

            // Publish Discovery Config for External Sensor (String state)
            val discoveryTopic = "karoo/sensor/karoo_$serial/$sensorId/config"
            val configPayload = buildJsonObject {
                put("name", "${device.name} Battery Status")
                put("state_topic", "karoo/$serial/sensor/$sensorId/state")
                put("json_attributes_topic", "karoo/$serial/sensor/$sensorId/attributes")
                // Removing device_class "battery" as it requires a numeric value and unit
                put("icon", "mdi:battery")
                put("unique_id", "karoo_${serial}_${sensorId}_status")
                putJsonObject("device") {
                    put("identifiers", serial)
                    put("name", "Karoo $serial")
                    put("model", "Hammerhead Karoo")
                    put("manufacturer", "Hammerhead")
                }
            }.toString()
            mqtt.publish(discoveryTopic, configPayload, retain = true)

            // Publish State as String (Retained so subscribers pick it up immediately)
            val stateTopic = "karoo/$serial/sensor/$sensorId/state"
            mqtt.publish(stateTopic, status, retain = true)

            // Publish Attributes
            val attributesTopic = "karoo/$serial/sensor/$sensorId/attributes"
            val percentage = batteryStore.getPercentage(device.id)
            val attributesPayload = buildJsonObject {
                put("manufacturer", device.details.manufacturer ?: "Generic")
                put("raw_status", rawStatus)
                if (percentage != -1) {
                    put("percentage_raw", percentage)
                }
            }.toString()
            mqtt.publish(attributesTopic, attributesPayload, retain = true)
        }

        mqtt.disconnect()
        DiagnosticLog.i("MqttWorker: finished successfully, published ${devices.size} device(s)")
        return Result.success()
    }
}

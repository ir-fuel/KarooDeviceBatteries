package com.example.karoohaextension

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.SavedDevices
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class MqttWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val config = AppConfig(applicationContext)
        val host = config.mqttHost ?: return Result.failure()
        
        val karooSystem = KarooSystemService(applicationContext)
        val connectionDeferred = CompletableDeferred<Boolean>()
        
        karooSystem.connect { connected ->
            connectionDeferred.complete(connected)
        }

        if (!connectionDeferred.await()) return Result.retry()

        val info = karooSystem.info ?: return Result.failure()
        val serial = info.serial

        // 1. Capture Internal Battery
        val internalBatteryDeferred = CompletableDeferred<Int>()
        val internalBatteryConsumerId = karooSystem.addConsumer<OnStreamState>(
            params = OnStreamState.StartStreaming(DataType.Type.BATTERY_PERCENT),
            onEvent = { event ->
                (event.state as? StreamState.Streaming)?.dataPoint?.singleValue?.let {
                    internalBatteryDeferred.complete(it.toInt())
                }
            }
        )
        val internalBattery = withTimeoutOrNull(kotlin.time.Duration.parse("5s")) { internalBatteryDeferred.await() }
        karooSystem.removeConsumer(internalBatteryConsumerId)

        // 2. Capture Saved Devices
        val devicesDeferred = CompletableDeferred<List<SavedDevices.SavedDevice>>()
        val consumerId = karooSystem.addConsumer<SavedDevices>(
            onEvent = { event ->
                devicesDeferred.complete(event.devices)
            }
        )
        val devices = withTimeoutOrNull(kotlin.time.Duration.parse("5s")) { devicesDeferred.await() }
        karooSystem.removeConsumer(consumerId)
        
        karooSystem.disconnect()

        if (devices == null && internalBattery == null) return Result.retry()

        val mqtt = MqttManager(host, config.mqttPort)
        if (!mqtt.connect()) return Result.retry()

        // Report Internal Battery if captured
        internalBattery?.let { percentage ->
            val discoveryTopic = "homeassistant/sensor/karoo_$serial/internal_battery/config"
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
            mqtt.publish("karoo/$serial/sensor/internal_battery/state", percentage.toString())
        }

        devices?.forEach { device ->
            val sensorId = device.id.replace(":", "_")
            val discoveryTopic = "homeassistant/sensor/karoo_$serial/$sensorId/config"
            
            val configPayload = buildJsonObject {
                put("name", "${device.name} Battery")
                put("state_topic", "karoo/$serial/sensor/$sensorId/state")
                put("unit_of_measurement", "%")
                put("device_class", "battery")
                put("unique_id", "karoo_${serial}_${sensorId}")
                putJsonObject("device") {
                    put("identifiers", serial)
                    put("name", "Karoo $serial")
                    put("model", "Hammerhead Karoo")
                    put("manufacturer", "Hammerhead")
                }
            }.toString()

            mqtt.publish(discoveryTopic, configPayload, retain = true)

            val status = device.details.lastBattery?.name ?: "UNKNOWN"
            mqtt.publish("karoo/$serial/sensor/$sensorId/state", status)
        }

        mqtt.disconnect()
        return Result.success()
    }
}

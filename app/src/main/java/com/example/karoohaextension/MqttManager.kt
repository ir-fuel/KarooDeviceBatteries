package com.example.karoohaextension

import com.hivemq.client.mqtt.mqtt5.Mqtt5BlockingClient
import com.hivemq.client.mqtt.mqtt5.Mqtt5Client
import java.util.UUID

class MqttManager(private val host: String, private val port: Int) {
    private var client: Mqtt5BlockingClient? = null

    fun connect(): Boolean {
        return try {
            client = Mqtt5Client.builder()
                .identifier(UUID.randomUUID().toString())
                .serverHost(host)
                .serverPort(port)
                .buildBlocking()
            client?.connect()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun publish(topic: String, payload: String, retain: Boolean = false) {
        try {
            client?.publishWith()
                ?.topic(topic)
                ?.payload(payload.toByteArray())
                ?.retain(retain)
                ?.send()
        } catch (e: Exception) {
            // Log or handle error
        }
    }

    fun disconnect() {
        try {
            client?.disconnect()
        } catch (e: Exception) {
            // Ignore
        }
    }
}

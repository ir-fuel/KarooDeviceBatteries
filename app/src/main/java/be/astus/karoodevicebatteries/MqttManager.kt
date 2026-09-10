package be.astus.karoodevicebatteries

import com.hivemq.client.mqtt.mqtt5.Mqtt5BlockingClient
import com.hivemq.client.mqtt.mqtt5.Mqtt5Client
import java.util.UUID

class MqttManager(
    private val host: String, 
    private val port: Int,
    private val username: String? = null,
    private val password: String? = null
) {
    private var client: Mqtt5BlockingClient? = null

    fun connect(): Boolean {
        return try {
            val builder = Mqtt5Client.builder()
                .identifier(UUID.randomUUID().toString())
                .serverHost(host)
                .serverPort(port)
            
            if (!username.isNullOrBlank() && !password.isNullOrBlank()) {
                builder.simpleAuth()
                    .username(username)
                    .password(password.toByteArray())
                    .applySimpleAuth()
            }

            client = builder.buildBlocking()
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

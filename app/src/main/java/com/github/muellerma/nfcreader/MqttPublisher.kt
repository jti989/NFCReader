package com.github.muellerma.nfcreader

import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.UUID

object MqttPublisher {
    fun publish(brokerUri: String, topic: String, payload: String) {
        val client = MqttClient(
            brokerUri,
            "nfc-reader-${UUID.randomUUID()}",
            MemoryPersistence()
        )
        try {
            val options = MqttConnectOptions().apply {
                isAutomaticReconnect = false
                isCleanSession = true
                connectionTimeout = 5
            }
            client.connect(options)
            val mqttMessage = MqttMessage(payload.toByteArray(Charsets.UTF_8)).apply {
                qos = 0
                isRetained = false
            }
            client.publish(topic, mqttMessage)
        } finally {
            if (client.isConnected) {
                client.disconnect()
            }
            client.close()
        }
    }
}

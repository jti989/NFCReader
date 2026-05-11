package com.github.muellerma.nfcreader

import android.content.Context

data class ConnectionSettings(
    val offlineModeEnabled: Boolean,
    val mqttEnabled: Boolean,
    val mqttBrokerUri: String,
    val mqttTopic: String
)

object ConnectionSettingsStore {
    private const val PREFS_NAME = "connection_settings"
    private const val KEY_OFFLINE_MODE = "offline_mode_enabled"
    private const val KEY_MQTT_ENABLED = "mqtt_enabled"
    private const val KEY_MQTT_BROKER_URI = "mqtt_broker_uri"
    private const val KEY_MQTT_TOPIC = "mqtt_topic"
    private const val DEFAULT_MQTT_TOPIC = "nfc-reader/tags"

    fun load(context: Context): ConnectionSettings {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return ConnectionSettings(
            offlineModeEnabled = prefs.getBoolean(KEY_OFFLINE_MODE, true),
            mqttEnabled = prefs.getBoolean(KEY_MQTT_ENABLED, false),
            mqttBrokerUri = prefs.getString(KEY_MQTT_BROKER_URI, "")?.trim().orEmpty(),
            mqttTopic = prefs.getString(KEY_MQTT_TOPIC, DEFAULT_MQTT_TOPIC)?.trim().orEmpty()
        )
    }

    fun save(context: Context, settings: ConnectionSettings) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_OFFLINE_MODE, settings.offlineModeEnabled)
            .putBoolean(KEY_MQTT_ENABLED, settings.mqttEnabled)
            .putString(KEY_MQTT_BROKER_URI, settings.mqttBrokerUri)
            .putString(KEY_MQTT_TOPIC, settings.mqttTopic.ifBlank { DEFAULT_MQTT_TOPIC })
            .apply()
    }
}

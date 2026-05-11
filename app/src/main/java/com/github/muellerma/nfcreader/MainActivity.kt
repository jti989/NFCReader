package com.github.muellerma.nfcreader

import android.app.PendingIntent
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.github.muellerma.nfcreader.record.ParsedNdefRecord
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private var tagList: LinearLayout? = null
    private var nfcAdapter: NfcAdapter? = null
    private lateinit var connectionSettings: ConnectionSettings
    private lateinit var mqttExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        tagList = findViewById<View>(R.id.list) as LinearLayout
        connectionSettings = ConnectionSettingsStore.load(this)
        mqttExecutor = Executors.newSingleThreadExecutor()
        resolveIntent(intent)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            showNoNfcDialog()
            return
        }
    }

    override fun onResume() {
        super.onResume()
        if (nfcAdapter?.isEnabled == false) {
            openNfcSettings()
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent_Mutable
        )
        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, null, null)
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        mqttExecutor.shutdown()
    }

    public override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        resolveIntent(intent)
    }

    private fun showNoNfcDialog() {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.no_nfc)
            .setNeutralButton(R.string.close_app) { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun openNfcSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Intent(Settings.Panel.ACTION_NFC)
        } else {
            Intent(Settings.ACTION_WIRELESS_SETTINGS)
        }
        startActivity(intent)
    }

    private fun resolveIntent(intent: Intent) {
        val validActions = listOf(
            NfcAdapter.ACTION_TAG_DISCOVERED,
            NfcAdapter.ACTION_TECH_DISCOVERED,
            NfcAdapter.ACTION_NDEF_DISCOVERED
        )
        if (intent.action in validActions) {
            val detectedTag = intent.parcelable<Tag>(NfcAdapter.EXTRA_TAG)
            val rawMsgs = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
            val messages = mutableListOf<NdefMessage>()
            if (rawMsgs != null) {
                rawMsgs.forEach {
                    messages.add(it as NdefMessage)
                }
            } else {
                // Unknown tag type
                val empty = ByteArray(0)
                val id = intent.getByteArrayExtra(NfcAdapter.EXTRA_ID)
                val tag = detectedTag ?: return
                val payload = dumpTagData(tag).toByteArray()
                val record = NdefRecord(NdefRecord.TNF_UNKNOWN, empty, id, payload)
                val msg = NdefMessage(arrayOf(record))
                messages.add(msg)
            }
            maybePublishTagContent(messages, detectedTag)
            // Setup the views
            buildTagViews(messages)
        }
    }

    private fun maybePublishTagContent(messages: List<NdefMessage>, tag: Tag?) {
        connectionSettings = ConnectionSettingsStore.load(this)
        if (connectionSettings.offlineModeEnabled || !connectionSettings.mqttEnabled) {
            return
        }
        if (connectionSettings.mqttBrokerUri.isBlank() || connectionSettings.mqttTopic.isBlank()) {
            return
        }
        val payload = buildMqttPayload(messages, tag)
        mqttExecutor.execute {
            try {
                MqttPublisher.publish(
                    brokerUri = connectionSettings.mqttBrokerUri,
                    topic = connectionSettings.mqttTopic,
                    payload = payload
                )
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        getString(R.string.mqtt_publish_failed, e.localizedMessage ?: "unknown"),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun buildMqttPayload(messages: List<NdefMessage>, tag: Tag?): String {
        val lines = mutableListOf<String>()
        lines.add("timestamp=${System.currentTimeMillis()}")
        tag?.let {
            lines.add("tag_id_hex=${toReversedHex(it.id)}")
            lines.add("tag_tech=${it.techList.joinToString(",")}")
        }
        messages.forEachIndexed { messageIndex, message ->
            message.records.forEachIndexed { recordIndex, record ->
                lines.add(
                    "message_${messageIndex}_record_${recordIndex}=" +
                            "tnf:${record.tnf}," +
                            "type:${toReversedHex(record.type)}," +
                            "id:${toReversedHex(record.id)}," +
                            "payload:${toReversedHex(record.payload)}"
                )
            }
        }
        return lines.joinToString(separator = "\n")
    }

    private fun dumpTagData(tag: Tag): String {
        val sb = StringBuilder()
        val id = tag.id
        sb.append("ID (hex): ").append(toHex(id)).append('\n')
        sb.append("ID (reversed hex): ").append(toReversedHex(id)).append('\n')
        sb.append("ID (dec): ").append(toDec(id)).append('\n')
        sb.append("ID (reversed dec): ").append(toReversedDec(id)).append('\n')
        val prefix = "android.nfc.tech."
        sb.append("Technologies: ")
        for (tech in tag.techList) {
            sb.append(tech.substring(prefix.length))
            sb.append(", ")
        }
        sb.delete(sb.length - 2, sb.length)
        for (tech in tag.techList) {
            if (tech == MifareClassic::class.java.name) {
                sb.append('\n')
                var type = "Unknown"
                try {
                    val mifareTag = MifareClassic.get(tag)

                    when (mifareTag.type) {
                        MifareClassic.TYPE_CLASSIC -> type = "Classic"
                        MifareClassic.TYPE_PLUS -> type = "Plus"
                        MifareClassic.TYPE_PRO -> type = "Pro"
                    }
                    sb.appendLine("Mifare Classic type: $type")
                    sb.appendLine("Mifare size: ${mifareTag.size} bytes")
                    sb.appendLine("Mifare sectors: ${mifareTag.sectorCount}")
                    sb.appendLine("Mifare blocks: ${mifareTag.blockCount}")
                } catch (e: Exception) {
                    sb.appendLine("Mifare classic error: ${e.message}")
                }
            }
            if (tech == MifareUltralight::class.java.name) {
                sb.append('\n')
                val mifareUlTag = MifareUltralight.get(tag)
                var type = "Unknown"
                when (mifareUlTag.type) {
                    MifareUltralight.TYPE_ULTRALIGHT -> type = "Ultralight"
                    MifareUltralight.TYPE_ULTRALIGHT_C -> type = "Ultralight C"
                }
                sb.append("Mifare Ultralight type: ")
                sb.append(type)
            }
        }
        return sb.toString()
    }

    private fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (i in bytes.indices.reversed()) {
            val b = bytes[i].toInt() and 0xff
            if (b < 0x10) sb.append('0')
            sb.append(Integer.toHexString(b))
            if (i > 0) {
                sb.append(" ")
            }
        }
        return sb.toString()
    }

    private fun toReversedHex(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (i in bytes.indices) {
            if (i > 0) {
                sb.append(" ")
            }
            val b = bytes[i].toInt() and 0xff
            if (b < 0x10) sb.append('0')
            sb.append(Integer.toHexString(b))
        }
        return sb.toString()
    }

    private fun toDec(bytes: ByteArray): Long {
        var result: Long = 0
        var factor: Long = 1
        for (i in bytes.indices) {
            val value = bytes[i].toLong() and 0xffL
            result += value * factor
            factor *= 256L
        }
        return result
    }

    private fun toReversedDec(bytes: ByteArray): Long {
        var result: Long = 0
        var factor: Long = 1
        for (i in bytes.indices.reversed()) {
            val value = bytes[i].toLong() and 0xffL
            result += value * factor
            factor *= 256L
        }
        return result
    }

    private fun buildTagViews(msgs: List<NdefMessage>) {
        if (msgs.isEmpty()) {
            return
        }
        val inflater = LayoutInflater.from(this)
        val content = tagList

        // Parse the first message in the list
        // Build views for all of the sub records
        val now = Date()
        val records = NdefMessageParser.parse(msgs[0])
        val size = records.size
        for (i in 0 until size) {
            val timeView = TextView(this)
            timeView.text = TIME_FORMAT.format(now)
            content!!.addView(timeView, 0)
            val record: ParsedNdefRecord = records[i]
            content.addView(record.getView(this, inflater, content, i), 1 + i)
            content.addView(inflater.inflate(R.layout.tag_divider, content, false), 2 + i)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_main_clear -> {
                clearTags()
                true
            }
            R.id.menu_main_connection -> {
                showConnectionSettingsDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showConnectionSettingsDialog() {
        val currentSettings = ConnectionSettingsStore.load(this)
        val contentView = layoutInflater.inflate(R.layout.dialog_connection_settings, null)
        val offlineSwitch = contentView.findViewById<SwitchMaterial>(R.id.switch_offline_mode)
        val mqttSwitch = contentView.findViewById<SwitchMaterial>(R.id.switch_mqtt_enabled)
        val mqttBrokerInput = contentView.findViewById<TextInputEditText>(R.id.input_mqtt_broker)
        val mqttTopicInput = contentView.findViewById<TextInputEditText>(R.id.input_mqtt_topic)

        offlineSwitch.isChecked = currentSettings.offlineModeEnabled
        mqttSwitch.isChecked = currentSettings.mqttEnabled && !currentSettings.offlineModeEnabled
        mqttSwitch.isEnabled = !offlineSwitch.isChecked
        mqttBrokerInput.setText(currentSettings.mqttBrokerUri)
        mqttTopicInput.setText(currentSettings.mqttTopic)

        val updateInputs = {
            val enabled = mqttSwitch.isChecked && !offlineSwitch.isChecked
            mqttBrokerInput.isEnabled = enabled
            mqttTopicInput.isEnabled = enabled
        }
        updateInputs()

        offlineSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                mqttSwitch.isChecked = false
            }
            mqttSwitch.isEnabled = !isChecked
            updateInputs()
        }
        mqttSwitch.setOnCheckedChangeListener { _, _ ->
            updateInputs()
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.connection_settings)
            .setView(contentView)
            .setPositiveButton(R.string.save) { _, _ ->
                val newSettings = ConnectionSettings(
                    offlineModeEnabled = offlineSwitch.isChecked,
                    mqttEnabled = mqttSwitch.isChecked && !offlineSwitch.isChecked,
                    mqttBrokerUri = mqttBrokerInput.text?.toString()?.trim().orEmpty(),
                    mqttTopic = mqttTopicInput.text?.toString()?.trim().orEmpty()
                )
                ConnectionSettingsStore.save(this, newSettings)
                connectionSettings = newSettings
                Toast.makeText(this, R.string.mqtt_settings_saved, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun clearTags() {
        for (i in tagList!!.childCount - 1 downTo 0) {
            val view = tagList!!.getChildAt(i)
            if (view.id != R.id.tag_viewer_text) {
                tagList!!.removeViewAt(i)
            }
        }
    }

    companion object {
        private val TIME_FORMAT = SimpleDateFormat.getDateTimeInstance()
    }
}

package com.example.portainerapp.ui

import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.example.portainerapp.R
import com.example.portainerapp.util.Prefs
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_settings)
        setSupportActionBar(toolbar)
        supportActionBar?.title = "Settings"

        val prefs = Prefs(this)
        val poll = findViewById<TextInputEditText>(R.id.input_poll_ms)
        val history = findViewById<TextInputEditText>(R.id.input_history)
        val pollLayout = findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.layout_poll_ms)
        val historyLayout = findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.layout_history)
        val showHeaderTotal = findViewById<MaterialSwitch>(R.id.switch_header_total)
        val proxyEnabled = findViewById<MaterialSwitch>(R.id.switch_proxy_enabled)
        val proxyHostLayout = findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.layout_proxy_host)
        val proxyPortLayout = findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.layout_proxy_port)
        val proxyHost = findViewById<TextInputEditText>(R.id.input_proxy_host)
        val proxyPort = findViewById<TextInputEditText>(R.id.input_proxy_port)
        val ignoreSsl = findViewById<MaterialSwitch>(R.id.switch_ignore_ssl)
        val save = findViewById<Button>(R.id.button_save_settings)

        poll.setText(prefs.pollIntervalMs().toString())
        history.setText(prefs.historyLength().toString())
        showHeaderTotal.isChecked = prefs.showHeaderTotal()
        proxyEnabled.isChecked = prefs.proxyEnabled()
        proxyHost.setText(prefs.proxyHost())
        proxyPort.setText(if (prefs.proxyPort() > 0) prefs.proxyPort().toString() else "")
        ignoreSsl.isChecked = prefs.ignoreSslErrors()

        fun updateProxyFields(enabled: Boolean) {
            proxyHostLayout.isEnabled = enabled
            proxyPortLayout.isEnabled = enabled
        }
        updateProxyFields(proxyEnabled.isChecked)
        proxyEnabled.setOnCheckedChangeListener { _, isChecked -> updateProxyFields(isChecked) }

        save.setOnClickListener {
            pollLayout.error = null
            historyLayout.error = null

            val pollStr = poll.text?.toString()?.trim() ?: ""
            val histStr = history.text?.toString()?.trim() ?: ""
            val pollMs = pollStr.toLongOrNull()
            val hist = histStr.toIntOrNull()

            var valid = true
            if (pollMs == null || pollMs < 1000 || pollMs > 60000) {
                pollLayout.error = "Must be 1000–60000"
                valid = false
            }
            if (hist == null || hist < 20 || hist > 600) {
                historyLayout.error = "Must be 20–600"
                valid = false
            }

            // Proxy validation (optional)
            proxyHostLayout.error = null
            proxyPortLayout.error = null
            if (proxyEnabled.isChecked) {
                val host = proxyHost.text?.toString()?.trim().orEmpty()
                val portVal = proxyPort.text?.toString()?.toIntOrNull()
                if (host.isEmpty()) { proxyHostLayout.error = "Required"; valid = false }
                if (portVal == null || portVal !in 1..65535) { proxyPortLayout.error = "1–65535"; valid = false }
            }

            if (!valid) return@setOnClickListener

            prefs.setPollIntervalMs(pollMs!!.toLong())
            prefs.setHistoryLength(hist!!)
            prefs.setShowHeaderTotal(showHeaderTotal.isChecked)
            prefs.setProxyEnabled(proxyEnabled.isChecked)
            if (proxyEnabled.isChecked) {
                prefs.setProxyHost(proxyHost.text?.toString()?.trim().orEmpty())
                prefs.setProxyPort(proxyPort.text?.toString()?.toIntOrNull() ?: 0)
            } else {
                prefs.setProxyHost("")
                prefs.setProxyPort(0)
            }
            prefs.setIgnoreSslErrors(ignoreSsl.isChecked)
            finish()
        }
    }
}

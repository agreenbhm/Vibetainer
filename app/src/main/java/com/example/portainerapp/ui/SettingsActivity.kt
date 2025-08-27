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
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        com.example.portainerapp.ui.EdgeToEdge.apply(this, toolbar, findViewById(R.id.content_settings))

        val prefs = Prefs(this)
        val poll = findViewById<TextInputEditText>(R.id.input_poll_ms)
        val history = findViewById<TextInputEditText>(R.id.input_history)
        val pollLayout = findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.layout_poll_ms)
        val historyLayout = findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.layout_history)
        val proxyEnabled = findViewById<MaterialSwitch>(R.id.switch_proxy_enabled)
        val proxyHostLayout = findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.layout_proxy_host)
        val proxyHost = findViewById<TextInputEditText>(R.id.input_proxy_host)
        val ignoreSsl = findViewById<MaterialSwitch>(R.id.switch_ignore_ssl)
        val save = findViewById<Button>(R.id.button_save_settings)

        poll.setText(prefs.pollIntervalMs().toString())
        history.setText(prefs.historyLength().toString())
        proxyEnabled.isChecked = prefs.proxyEnabled()
        val existingProxyUrl = prefs.proxyUrl()
        if (existingProxyUrl.isNotBlank()) {
            proxyHost.setText(existingProxyUrl)
        } else {
            val host = prefs.proxyHost()
            val port = prefs.proxyPort()
            if (host.isNotBlank() && port > 0) proxyHost.setText("http://$host:$port")
        }
        ignoreSsl.isChecked = prefs.ignoreSslErrors()

        fun updateProxyFields(enabled: Boolean) {
            proxyHostLayout.isEnabled = enabled
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
            if (proxyEnabled.isChecked) {
                var url = proxyHost.text?.toString()?.trim().orEmpty()
                if (url.isEmpty()) { proxyHostLayout.error = "Required"; valid = false }
                if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
                    // Default to http if not provided
                    url = "http://" + url
                    proxyHost.setText(url)
                }
                // Basic check for host
                val after = url.substringAfter("://").trimEnd('/')
                if (after.isEmpty()) { proxyHostLayout.error = "Required"; valid = false }
            }

            if (!valid) return@setOnClickListener

            prefs.setPollIntervalMs(pollMs!!.toLong())
            prefs.setHistoryLength(hist!!)
            // Header total toggle removed
            prefs.setProxyEnabled(proxyEnabled.isChecked)
            if (proxyEnabled.isChecked) {
                var url = proxyHost.text?.toString()?.trim().orEmpty()
                if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
                    url = "http://" + url
                }
                val normalized = prefs.normalizeBaseUrl(url).removeSuffix("/")
                prefs.setProxyUrl(normalized)
            } else {
                prefs.setProxyUrl("")
                prefs.setProxyHost("")
                prefs.setProxyPort(0)
                prefs.setProxyScheme("http")
            }
            prefs.setIgnoreSslErrors(ignoreSsl.isChecked)
            finish()
        }
    }
}

package com.example.portainerapp.util

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class Prefs(context: Context) {
    private val prefs = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "portainer_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }.getOrElse {
        // Fallback to regular SharedPreferences if crypto unavailable
        context.getSharedPreferences("portainer_prefs_fallback", Context.MODE_PRIVATE)
    }

    fun saveConfig(domain: String, port: String, token: String) {
        val base = buildBaseUrl(domain, port)
        prefs.edit()
            .putString(KEY_BASE_URL, base)
            .putString(KEY_TOKEN, token)
            .apply()
    }

    fun hasValidConfig(): Boolean = token().isNotBlank() && baseUrl().isNotBlank()

    fun token(): String = prefs.getString(KEY_TOKEN, "") ?: ""
    fun baseUrl(): String = prefs.getString(KEY_BASE_URL, "") ?: ""
    fun endpointId(): Int = prefs.getInt(KEY_ENDPOINT_ID, -1)
    fun endpointName(): String = prefs.getString(KEY_ENDPOINT_NAME, "") ?: ""
    fun saveEndpoint(id: Int, name: String) {
        prefs.edit().putInt(KEY_ENDPOINT_ID, id).putString(KEY_ENDPOINT_NAME, name).apply()
    }

    fun pollIntervalMs(): Long = prefs.getLong(KEY_POLL_INTERVAL_MS, 5000L)
    fun historyLength(): Int = prefs.getInt(KEY_HISTORY_LENGTH, 60)
    fun setPollIntervalMs(ms: Long) { prefs.edit().putLong(KEY_POLL_INTERVAL_MS, ms).apply() }
    fun setHistoryLength(points: Int) { prefs.edit().putInt(KEY_HISTORY_LENGTH, points).apply() }

    fun showHeaderTotal(): Boolean = prefs.getBoolean(KEY_SHOW_HEADER_TOTAL, true)
    fun setShowHeaderTotal(show: Boolean) { prefs.edit().putBoolean(KEY_SHOW_HEADER_TOTAL, show).apply() }

    // Proxy settings
    fun proxyEnabled(): Boolean = prefs.getBoolean(KEY_PROXY_ENABLED, false)
    fun setProxyEnabled(v: Boolean) { prefs.edit().putBoolean(KEY_PROXY_ENABLED, v).apply() }
    fun proxyHost(): String = prefs.getString(KEY_PROXY_HOST, "") ?: ""
    fun setProxyHost(v: String) { prefs.edit().putString(KEY_PROXY_HOST, v).apply() }
    fun proxyPort(): Int = prefs.getInt(KEY_PROXY_PORT, 0)
    fun setProxyPort(v: Int) { prefs.edit().putInt(KEY_PROXY_PORT, v).apply() }
    fun ignoreSslErrors(): Boolean = prefs.getBoolean(KEY_IGNORE_SSL, false)
    fun setIgnoreSslErrors(v: Boolean) { prefs.edit().putBoolean(KEY_IGNORE_SSL, v).apply() }
    fun clearAll() { prefs.edit().clear().apply() }

    // Logs preferences
    fun logsTail(): Int = prefs.getInt(KEY_LOGS_TAIL, 200)
    fun setLogsTail(v: Int) { prefs.edit().putInt(KEY_LOGS_TAIL, v).apply() }
    fun logsTimestamps(): Boolean = prefs.getBoolean(KEY_LOGS_TIMESTAMPS, true)
    fun setLogsTimestamps(v: Boolean) { prefs.edit().putBoolean(KEY_LOGS_TIMESTAMPS, v).apply() }
    fun logsAutoRefresh(): Boolean = prefs.getBoolean(KEY_LOGS_AUTO_REFRESH, false)
    fun setLogsAutoRefresh(v: Boolean) { prefs.edit().putBoolean(KEY_LOGS_AUTO_REFRESH, v).apply() }

    fun getAxisMax(endpointId: Int, nodeId: String): Float {
        val key = axisKey(endpointId, nodeId)
        return prefs.getFloat(key, 0f)
    }
    fun setAxisMax(endpointId: Int, nodeId: String, value: Float) {
        val key = axisKey(endpointId, nodeId)
        prefs.edit().putFloat(key, value).apply()
    }
    private fun axisKey(endpointId: Int, nodeId: String) = "axis_max_${'$'}endpointId_${'$'}nodeId"

    private fun buildBaseUrl(domain: String, port: String): String {
        val scheme = if (domain.startsWith("http")) "" else "https://"
        val host = domain.removeSuffix("/")
        return "$scheme$host:$port/"
    }

    companion object {
        private const val KEY_TOKEN = "token"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_ENDPOINT_ID = "endpoint_id"
        private const val KEY_ENDPOINT_NAME = "endpoint_name"
        private const val KEY_POLL_INTERVAL_MS = "poll_interval_ms"
        private const val KEY_HISTORY_LENGTH = "history_length"
        private const val KEY_SHOW_HEADER_TOTAL = "show_header_total"
        private const val KEY_PROXY_ENABLED = "proxy_enabled"
        private const val KEY_PROXY_HOST = "proxy_host"
        private const val KEY_PROXY_PORT = "proxy_port"
        private const val KEY_IGNORE_SSL = "ignore_ssl_errors"
        private const val KEY_LOGS_TAIL = "logs_tail"
        private const val KEY_LOGS_TIMESTAMPS = "logs_timestamps"
        private const val KEY_LOGS_AUTO_REFRESH = "logs_auto_refresh"
    }
}

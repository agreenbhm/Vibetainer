package com.agreenbhm.vibetainer.network

import android.content.Context
import okhttp3.*
import okio.ByteString
import java.util.concurrent.TimeUnit

class ExecWebSocketClient(
    private val context: Context,
    private val baseUrl: String,
    private val apiToken: String,
    private val onMessage: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onClosed: () -> Unit,
    private val accumulateAndDetectEof: Boolean = true,
    private val ttyMode: Boolean = false,
    private val onBinary: ((ByteArray) -> Unit)? = null
) {
    private var webSocket: WebSocket? = null
    private val client: OkHttpClient by lazy {
        createOkHttpClient()
    }
    private val outputBuffer = StringBuilder()
    private val eofMarker = "---VibetainerEOF---"

    private fun createOkHttpClient(): OkHttpClient {
        val prefs = com.agreenbhm.vibetainer.util.Prefs(context)
        
        val builder = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)

        // Configure HTTP proxy if enabled
        if (prefs.proxyEnabled()) {
            val url = prefs.proxyUrl()
            if (url.isNotBlank()) {
                try {
                    val normalized = prefs.normalizeBaseUrl(url).removeSuffix("/")
                    val scheme = if (normalized.startsWith("https://", true)) "https" else "http"
                    val after = normalized.substringAfter("://")
                    val host = after.substringBefore(":")
                    val portStr = after.substringAfter(":", "")
                    val port = portStr.toIntOrNull() ?: if (scheme == "https") 443 else 80
                    val proxy = java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress(host, port))
                    builder.proxy(proxy)
                } catch (_: Exception) { /* ignore invalid proxy URL */ }
            } else {
                // Back-compat: host/port settings
                val host = prefs.proxyHost()
                val port = prefs.proxyPort()
                if (host.isNotBlank() && port in 1..65535) {
                    val proxy = java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress(host, port))
                    builder.proxy(proxy)
                }
            }
        }

        // Optionally ignore SSL errors (trust all + accept all hostnames)
        if (prefs.ignoreSslErrors()) {
            try {
                val trustAll = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
                    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                    override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                    override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                })
                val sslContext = javax.net.ssl.SSLContext.getInstance("TLS")
                sslContext.init(null, trustAll, java.security.SecureRandom())
                val sslSocketFactory = sslContext.socketFactory
                builder.sslSocketFactory(sslSocketFactory, trustAll[0] as javax.net.ssl.X509TrustManager)
                builder.hostnameVerifier { _, _ -> true }
            } catch (_: Exception) {
                // If SSL context fails, proceed without relaxing SSL
            }
        }

        return builder.build()
    }

    fun connect(endpointId: Int, execId: String, nodeName: String?) {
        try {
            // Convert HTTP(S) URL to WebSocket URL
            val wsBaseUrl = baseUrl.replace("http://", "ws://").replace("https://", "wss://")
            val url = buildString {
                append(wsBaseUrl.removeSuffix("/"))
                append("/api/websocket/exec")
                append("?endpointId=$endpointId")
                append("&id=$execId")
                if (!nodeName.isNullOrBlank()) {
                    append("&nodeName=$nodeName")
                }
            }

            val request = Request.Builder()
                .url(url)
                .addHeader("X-Api-Key", apiToken)
                .build()

            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    // WebSocket connection opened successfully
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (ttyMode && onBinary != null) {
                        onBinary.invoke(text.toByteArray(Charsets.UTF_8))
                    } else {
                        processMessage(text)
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (ttyMode && onBinary != null) {
                        onBinary.invoke(bytes.toByteArray())
                    } else {
                        val text = demuxDockerOutput(bytes.toByteArray())
                        processMessage(text)
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    // Don't report error 1006 as it's a normal closure when we close intentionally
                    if (t.message != "Code 1006 is reserved and may not be used.") {
                        val errorMsg = response?.message ?: t.message ?: "WebSocket connection failed"
                        onError(errorMsg)
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    // Only call onClosed if we haven't already handled it in processMessage
                    if (webSocket != null) {
                        onClosed()
                    }
                }
            })
        } catch (e: Exception) {
            onError("Failed to create WebSocket connection: ${e.message}")
        }
    }

    fun close() {
        webSocket?.close(1000, "Closing connection")
        webSocket = null
    }

    private fun processMessage(message: String) {
        if (!accumulateAndDetectEof) {
            onMessage(message)
            return
        }
        outputBuffer.append(message)
        val currentOutput = outputBuffer.toString()
        if (currentOutput.contains(eofMarker)) {
            val cleanOutput = currentOutput.replace(eofMarker, "")
            onMessage(cleanOutput)
            webSocket?.close(1000, "Command completed successfully")
            webSocket = null
            onClosed()
        } else {
            onMessage(currentOutput)
        }
    }

    private fun demuxDockerOutput(bytes: ByteArray): String {
        // Docker stdcopy format: 8-byte header + payload
        // Header: [stream(1)][000][len(4, BE)]
        val sb = StringBuilder()
        var i = 0
        var frames = 0
        
        while (i + 8 <= bytes.size) {
            // Read the payload length from bytes 4-7 (big-endian)
            val len = ((bytes[i + 4].toInt() and 0xFF) shl 24) or
                    ((bytes[i + 5].toInt() and 0xFF) shl 16) or
                    ((bytes[i + 6].toInt() and 0xFF) shl 8) or
                    (bytes[i + 7].toInt() and 0xFF)
            
            val start = i + 8
            val end = start + len
            
            // Validate bounds
            if (len < 0 || end > bytes.size) break
            
            // Extract payload
            val chunk = bytes.copyOfRange(start, end)
            sb.append(String(chunk, Charsets.UTF_8))
            frames++
            i = end
        }
        
        // If we parsed frames successfully, return the result
        if (frames > 0 && sb.isNotEmpty()) {
            return sb.toString()
        }
        
        // Fallback: treat as plain UTF-8
        return String(bytes, Charsets.UTF_8)
    }

    fun send(text: String) {
        webSocket?.send(text)
    }
}

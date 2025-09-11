package com.agreenbhm.vibetainer.ui

import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.KeyEvent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.agreenbhm.vibetainer.R
import com.agreenbhm.vibetainer.network.ContainerExecRequest
import com.agreenbhm.vibetainer.network.ExecWebSocketClient
import com.agreenbhm.vibetainer.network.PortainerApi
import com.agreenbhm.vibetainer.util.Prefs
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TerminalActivity : AppCompatActivity() {
    private var endpointId: Int = -1
    private var containerId: String = ""
    private var agentTarget: String? = null
    private var wsClient: ExecWebSocketClient? = null

    private lateinit var webView: android.webkit.WebView
    private lateinit var btnCtrl: ToggleButton
    private lateinit var btnAlt: ToggleButton
    private var pageReady: Boolean = false
    private val pendingWrites = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminal)

        endpointId = intent.getIntExtra(EXTRA_ENDPOINT_ID, -1)
        containerId = intent.getStringExtra(EXTRA_CONTAINER_ID) ?: ""
        agentTarget = intent.getStringExtra(EXTRA_AGENT_TARGET)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_terminal)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        EdgeToEdge.apply(this, toolbar, findViewById(R.id.root_terminal))

        webView = findViewById(R.id.web_terminal)
        btnCtrl = findViewById(R.id.btn_key_ctrl)
        btnAlt = findViewById(R.id.btn_key_alt)

        // Setup WebView terminal
        val ws = webView.settings
        ws.javaScriptEnabled = true
        ws.domStorageEnabled = true
        ws.allowFileAccess = true
        ws.allowFileAccessFromFileURLs = true
        ws.allowUniversalAccessFromFileURLs = true
        webView.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun send(data: String) {
                // Data typed in xterm, apply modifiers if toggled for next char
                val processed = applyModifiers(data)
                wsClient?.send(processed)
                // One-shot behavior for toggles
                runOnUiThread {
                    if (btnCtrl.isChecked) btnCtrl.isChecked = false
                    if (btnAlt.isChecked) btnAlt.isChecked = false
                    // reflect to JS
                    setCtrlAltInJs(false, false)
                }
            }

            @android.webkit.JavascriptInterface
            fun onReady() {
                runOnUiThread {
                    pageReady = true
                    if (pendingWrites.isNotEmpty()) {
                        for (b64 in pendingWrites) webView.evaluateJavascript("writeBase64('$b64')", null)
                        pendingWrites.clear()
                    }
                    setCtrlAltInJs(btnCtrl.isChecked, btnAlt.isChecked)
                }
            }
        }, "Android")
        webView.webViewClient = object : android.webkit.WebViewClient() {}
        webView.loadUrl("file:///android_asset/terminal/terminal.html")

        val esc = findViewById<View>(R.id.btn_key_esc)
        val tab = findViewById<View>(R.id.btn_key_tab)
        val up = findViewById<View>(R.id.btn_key_up)
        val down = findViewById<View>(R.id.btn_key_down)
        val left = findViewById<View>(R.id.btn_key_left)
        val right = findViewById<View>(R.id.btn_key_right)

        // Clear button
        findViewById<View>(R.id.btn_terminal_clear).setOnClickListener {
            webView.evaluateJavascript("clearTerminal()", null)
        }

        esc.setOnClickListener { sendRaw("\u001B") }
        tab.setOnClickListener { sendRaw("\t") }
        up.setOnClickListener { sendRaw("\u001B[A") }
        down.setOnClickListener { sendRaw("\u001B[B") }
        left.setOnClickListener { sendRaw("\u001B[D") }
        right.setOnClickListener { sendRaw("\u001B[C") }

        // Keep JS in sync with modifier toggles
        btnCtrl.setOnCheckedChangeListener { _, isChecked -> setCtrlAltInJs(isChecked, btnAlt.isChecked) }
        btnAlt.setOnCheckedChangeListener { _, isChecked -> setCtrlAltInJs(btnCtrl.isChecked, isChecked) }

        promptSessionOptions()
    }

    private fun setCtrlAltInJs(ctrl: Boolean, alt: Boolean) {
        val js = "setCtrlAlt(${if (ctrl) 1 else 0}, ${if (alt) 1 else 0})"
        webView.evaluateJavascript(js, null)
    }

    private fun promptSessionOptions() {
        val view = layoutInflater.inflate(R.layout.dialog_start_terminal, null)
        val spinner = view.findViewById<Spinner>(R.id.spinner_shell)
        val toggleCustom = view.findViewById<Switch>(R.id.switch_custom_cmd)
        val editCustom = view.findViewById<EditText>(R.id.edit_custom_cmd)
        val editUser = view.findViewById<EditText>(R.id.edit_username)
        val shells = listOf("/bin/bash", "/bin/sh", "/usr/bin/bash", "/usr/bin/sh")
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, shells)
        toggleCustom.setOnCheckedChangeListener { _, isChecked ->
            spinner.visibility = if (isChecked) View.GONE else View.VISIBLE
            editCustom.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.title_start_terminal))
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(getString(R.string.action_start)) { _, _ ->
                val cmd = if (toggleCustom.isChecked) editCustom.text.toString().trim().ifEmpty { "/bin/sh" } else shells[spinner.selectedItemPosition]
                val user = editUser.text.toString().trim().ifEmpty { null }
                startSession(cmd, user)
            }
            .setCancelable(false)
            .show()
    }

    private fun startSession(command: String, user: String?) {
        val prefs = Prefs(this)
        val api = PortainerApi.create(this, prefs.baseUrl(), prefs.token())
        lifecycleScope.launch {
            try {
                val cmdParts = splitCommand(command)
                val execRequest = ContainerExecRequest(
                    Cmd = cmdParts,
                    AttachStdout = true,
                    AttachStderr = true,
                    AttachStdin = true,
                    Tty = true,
                    User = user
                )
                val resp = withContext(Dispatchers.IO) { api.containerExec(endpointId, containerId, execRequest, agentTarget) }
                val execId = resp.Id ?: throw IllegalStateException("No exec id returned")
                wsClient = ExecWebSocketClient(
                    context = this@TerminalActivity,
                    baseUrl = prefs.baseUrl(),
                    apiToken = prefs.token(),
                    onMessage = { /* ignore UTF-8 text in TTY mode */ },
                    onError = { showError(it) },
                    onClosed = { runOnUiThread { Snackbar.make(findViewById(R.id.root_terminal), R.string.terminal_closed, Snackbar.LENGTH_SHORT).show() } },
                    accumulateAndDetectEof = false,
                    ttyMode = true,
                    onBinary = { bytes -> appendOutputBytes(bytes) }
                ).also { it.connect(endpointId, execId, agentTarget) }
            } catch (e: Exception) {
                showError(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun appendOutputBytes(bytes: ByteArray) {
        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        runOnUiThread {
            if (pageReady) {
                webView.evaluateJavascript("writeBase64('$b64')", null)
            } else {
                pendingWrites.add(b64)
            }
        }
    }

    private fun showError(msg: String) {
        runOnUiThread {
            Snackbar.make(findViewById(R.id.root_terminal), msg, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun sendRaw(s: String) {
        val processed = applyModifiers(s)
        wsClient?.send(processed)
        if (btnCtrl.isChecked) btnCtrl.isChecked = false
        if (btnAlt.isChecked) btnAlt.isChecked = false
    }

    private fun applyModifiers(input: String): String {
        var s = input
        if (btnCtrl.isChecked) {
            s = buildString {
                for (ch in s) {
                    append(applyCtrl(ch))
                }
            }
        }
        if (btnAlt.isChecked) {
            s = "\u001B" + s
        }
        return s
    }

    private fun applyCtrl(ch: Char): Char {
        val c = ch.lowercaseChar()
        return if (c in 'a'..'z') {
            ((c.code - 'a'.code + 1).toChar())
        } else when (ch) {
            ' ' -> 0.toChar() // Ctrl-Space -> NUL
            '[' -> 27.toChar()
            '\\' -> 28.toChar()
            ']' -> 29.toChar()
            '^' -> 30.toChar()
            '_' -> 31.toChar()
            else -> ch
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        wsClient?.close()
        wsClient = null
    }

    companion object {
        const val EXTRA_ENDPOINT_ID = "endpoint_id"
        const val EXTRA_CONTAINER_ID = "container_id"
        const val EXTRA_AGENT_TARGET = "agent_target"
    }

    private fun splitCommand(cmd: String): List<String> {
        // naive split respecting simple quotes
        val result = mutableListOf<String>()
        var cur = StringBuilder()
        var quote: Char? = null
        for (ch in cmd) {
            when {
                quote != null -> {
                    if (ch == quote) { quote = null } else { cur.append(ch) }
                }
                ch == '\'' || ch == '"' -> quote = ch
                ch.isWhitespace() -> { if (cur.isNotEmpty()) { result.add(cur.toString()); cur = StringBuilder() } }
                else -> cur.append(ch)
            }
        }
        if (cur.isNotEmpty()) result.add(cur.toString())
        if (result.isEmpty()) result.add("/bin/sh")
        return result
    }
}

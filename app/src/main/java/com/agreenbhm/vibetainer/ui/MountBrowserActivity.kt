package com.agreenbhm.vibetainer.ui

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.agreenbhm.vibetainer.R
import com.agreenbhm.vibetainer.network.PortainerApi
import com.agreenbhm.vibetainer.network.ContainerExecRequest
import com.agreenbhm.vibetainer.network.ExecWebSocketClient
import com.agreenbhm.vibetainer.util.Prefs
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay

class MountBrowserActivity : AppCompatActivity() {
    
    private var endpointId: Int = -1
    private var containerId: String? = null
    private var containerName: String? = null
    private var mountPath: String? = null
    private var agentTarget: String? = null
    private var wsClient: ExecWebSocketClient? = null
    
    companion object {
        const val EXTRA_ENDPOINT_ID = "endpoint_id"
        const val EXTRA_CONTAINER_ID = "container_id"
        const val EXTRA_CONTAINER_NAME = "container_name"
        const val EXTRA_MOUNT_PATH = "mount_path"
        const val EXTRA_AGENT_TARGET = "agent_target"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mount_browser)

        endpointId = intent.getIntExtra(EXTRA_ENDPOINT_ID, -1)
        containerId = intent.getStringExtra(EXTRA_CONTAINER_ID)
        containerName = intent.getStringExtra(EXTRA_CONTAINER_NAME)
        mountPath = intent.getStringExtra(EXTRA_MOUNT_PATH)
        agentTarget = intent.getStringExtra(EXTRA_AGENT_TARGET)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_mount_browser)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        
        EdgeToEdge.apply(this, toolbar, findViewById(R.id.swipe_mount_browser))

        // Set up UI
        setupUI()
        
        val swipe = findViewById<SwipeRefreshLayout>(R.id.swipe_mount_browser)
        swipe.setOnRefreshListener { loadMountContents() }
        
        // Load mount contents
        loadMountContents()
    }

    override fun onDestroy() {
        super.onDestroy()
        wsClient?.close()
    }

    private fun setupUI() {
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_mount_browser)
        supportActionBar?.title = mountPath ?: "Unknown Path"
        supportActionBar?.subtitle = if (!agentTarget.isNullOrBlank()) agentTarget else "Unknown Node"
        
        val longFormatSwitch = findViewById<MaterialSwitch>(R.id.switch_long_format)
        longFormatSwitch.setOnCheckedChangeListener { _, _ ->
            loadMountContents()
        }
        
        updateMountInfo("Loading...")
    }

    private fun updateMountInfo(status: String) {
        findViewById<TextView>(R.id.text_mount_info).text = buildString {
            append("Container: ${containerName ?: containerId?.take(12) ?: "Unknown"}")
            if (!agentTarget.isNullOrBlank()) {
                append("\nNode: $agentTarget")
            }
            append("\nStatus: $status")
        }
    }

    private fun loadMountContents() {
        val swipe = findViewById<SwipeRefreshLayout>(R.id.swipe_mount_browser)
        val contentsText = findViewById<TextView>(R.id.text_directory_contents)
        
        swipe.isRefreshing = true
        contentsText.text = "Loading..."
        updateMountInfo("Creating exec session...")

        lifecycleScope.launch {
            try {
                val prefs = Prefs(this@MountBrowserActivity)
                val api = PortainerApi.create(this@MountBrowserActivity, prefs.baseUrl(), prefs.token())
                
                // Create exec request to list directory contents
                val longFormatSwitch = findViewById<MaterialSwitch>(R.id.switch_long_format)
                val lsCommand = if (longFormatSwitch.isChecked) "ls -la" else "ls -a1"
                val execRequest = ContainerExecRequest(
                    Cmd = listOf("/bin/sh", "-c", "$lsCommand ${mountPath ?: "/"}; printf '%s' '---VibetainerEOF---'"),
                    AttachStdout = true,
                    AttachStderr = true
                )
                
                val execResponse = withContext(Dispatchers.IO) {
                    api.containerExec(endpointId, containerId ?: "", execRequest, agentTarget)
                }
                
                val execId = execResponse.Id
                if (!execId.isNullOrBlank()) {
                    updateMountInfo("Connecting to exec session...")
                    
                    // Initialize WebSocket client
                    wsClient = ExecWebSocketClient(
                        context = this@MountBrowserActivity,
                        baseUrl = prefs.baseUrl(),
                        apiToken = prefs.token(),
                        onMessage = { message ->
                            runOnUiThread {
                                contentsText.text = message
                            }
                        },
                        onError = { error ->
                            runOnUiThread {
                                updateMountInfo("WebSocket Error: $error")
                                swipe.isRefreshing = false
                                Snackbar.make(
                                    findViewById(android.R.id.content),
                                    "WebSocket Error: $error",
                                    Snackbar.LENGTH_LONG
                                ).show()
                            }
                        },
                        onClosed = {
                            runOnUiThread {
                                updateMountInfo("Command completed")
                                swipe.isRefreshing = false
                            }
                        }
                    )
                    
                    // Connect to WebSocket
                    wsClient?.connect(endpointId, execId, agentTarget)
                    updateMountInfo("Connected - waiting for output...")
                    
                    // No timeout needed - EOF marker will terminate the connection
                    
                } else {
                    updateMountInfo("Failed to create exec session")
                    contentsText.text = "Error: Unable to create exec session"
                    swipe.isRefreshing = false
                }
                
            } catch (e: Exception) {
                updateMountInfo("Error: ${e.message}")
                contentsText.text = "Error executing command: ${e.message}"
                swipe.isRefreshing = false
                
                Snackbar.make(
                    findViewById(android.R.id.content),
                    "Error: ${e.message}",
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }
}
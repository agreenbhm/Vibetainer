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
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.agreenbhm.vibetainer.ui.adapters.FileEntry
import com.agreenbhm.vibetainer.ui.adapters.FileEntryAdapter

class MountBrowserActivity : AppCompatActivity() {
    
    private var endpointId: Int = -1
    private var containerId: String? = null
    private var containerName: String? = null
    private var mountPath: String? = null
    private var agentTarget: String? = null
    private var wsClient: ExecWebSocketClient? = null
    private lateinit var filesAdapter: FileEntryAdapter
    private var currentPath: String = "/"
    private var rootPath: String = "/"
    private var lastWsOutput: String = ""
    
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
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        
        EdgeToEdge.apply(this, toolbar, findViewById(R.id.swipe_mount_browser))

        // Set up UI
        setupUI()
        
        val swipe = findViewById<SwipeRefreshLayout>(R.id.swipe_mount_browser)
        swipe.setOnRefreshListener { loadDirectory(currentPath) }
        
        // Load mount contents
        rootPath = (mountPath ?: "/").removeSuffix("/")
        currentPath = if (rootPath.isBlank()) "/" else rootPath
        loadDirectory(currentPath)
    }

    override fun onDestroy() {
        super.onDestroy()
        wsClient?.close()
    }

    private fun setupUI() {
        supportActionBar?.title = containerName ?: containerId?.take(12) ?: "Container"
        supportActionBar?.subtitle = mountPath ?: "/"

        filesAdapter = FileEntryAdapter { entry ->
            if (entry.isDir) {
                val next = if (currentPath == "/") "/${entry.name}" else "$currentPath/${entry.name}"
                loadDirectory(next)
            } else {
                Snackbar.make(findViewById(R.id.recycler_files), entry.name, Snackbar.LENGTH_SHORT).show()
            }
        }
        val rv = findViewById<RecyclerView>(R.id.recycler_files)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = filesAdapter
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

    private fun loadDirectory(path: String) {
        val swipe = findViewById<SwipeRefreshLayout>(R.id.swipe_mount_browser)
        val empty = findViewById<TextView>(R.id.text_empty)
        val pathText = findViewById<TextView>(R.id.text_current_path)
        pathText.text = path
        swipe.isRefreshing = true
        empty.visibility = android.view.View.GONE
        updateMountInfo("Listing directory...")

        lifecycleScope.launch {
            try {
                val prefs = Prefs(this@MountBrowserActivity)
                val api = PortainerApi.create(this@MountBrowserActivity, prefs.baseUrl(), prefs.token())
                
                val execRequest = ContainerExecRequest(
                    Cmd = listOf("/bin/sh", "-c", buildListCommand(path)),
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
                            lastWsOutput = message
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
                                parseAndShow(lastWsOutput)
                            }
                        }
                    )
                    
                    // Connect to WebSocket
                    wsClient?.connect(endpointId, execId, agentTarget)
                    updateMountInfo("Connected - waiting for output...")
                    
                    // No timeout needed - EOF marker will terminate the connection
                    
                } else {
                    updateMountInfo("Failed to create exec session")
                    Snackbar.make(findViewById(android.R.id.content), "Unable to create exec session", Snackbar.LENGTH_LONG).show()
                    swipe.isRefreshing = false
                }
                
            } catch (e: Exception) {
                updateMountInfo("Error: ${e.message}")
                swipe.isRefreshing = false
                
                Snackbar.make(
                    findViewById(android.R.id.content),
                    "Error: ${e.message}",
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun buildListCommand(path: String): String {
        val p = path.ifBlank { "/" }
        val escaped = p.replace("'", "'\"'\"'")
        return "cd -- '$escaped' || exit; LC_ALL=C ls -a1 -p --group-directories-first; printf '%s' '---VibetainerEOF---'"
    }

    private fun parseAndShow(output: String) {
        val swipe = findViewById<SwipeRefreshLayout>(R.id.swipe_mount_browser)
        val empty = findViewById<TextView>(R.id.text_empty)
        swipe.isRefreshing = false
        updateMountInfo("Ready")
        val lines = output.replace("\r", "").split("\n")
        val entries = lines
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filter { it != "." && it != ".." }
            .map { name ->
                val isDir = name.endsWith('/')
                FileEntry(name = name.trimEnd('/'), isDir = isDir)
            }
        filesAdapter.submit(entries)
        empty.visibility = if (entries.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        currentPath = findViewById<TextView>(R.id.text_current_path).text.toString()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.menu_mount_browser, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_up_dir -> { navigateUp(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun navigateUp() {
        val atRoot = currentPath.removeSuffix("/") == rootPath.removeSuffix("/")
        if (atRoot) { finish(); return }
        val parent = currentPath.removeSuffix("/").substringBeforeLast('/', missingDelimiterValue = "/")
        loadDirectory(if (parent.isBlank()) "/" else parent)
    }

    override fun onBackPressed() {
        val atRoot = currentPath.removeSuffix("/") == rootPath.removeSuffix("/")
        if (!atRoot) {
            navigateUp()
        } else {
            super.onBackPressed()
        }
    }
}

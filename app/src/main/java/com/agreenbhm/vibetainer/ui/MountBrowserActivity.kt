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
    private var showHidden: Boolean = false
    private var sortAsc: Boolean = true
    
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
        val prefsInit = Prefs(this)
        showHidden = prefsInit.mountShowHidden()
        sortAsc = prefsInit.mountSortAsc()
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
                openFilePreview(entry)
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
        val normalized = resolveAbsolutePath(path)
        pathText.text = normalized
        swipe.isRefreshing = true
        empty.visibility = android.view.View.GONE
        updateMountInfo("Listing directory...")

        lifecycleScope.launch {
            try {
                val prefs = Prefs(this@MountBrowserActivity)
                val api = PortainerApi.create(this@MountBrowserActivity, prefs.baseUrl(), prefs.token())
                
                val execRequest = ContainerExecRequest(
                    Cmd = listOf("/bin/sh", "-c", buildListCommand(normalized)),
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
        val hiddenFlag = if (showHidden) "-a" else ""
        // Client-side sorting for consistent behavior across GNU and BusyBox ls
        return "cd -- '$escaped' || exit; LC_ALL=C ls $hiddenFlag -1 -p; printf '%s' '---VibetainerEOF---'"
    }

    private fun resolveAbsolutePath(path: String): String {
        val abs = if (path.startsWith("/")) path else if (currentPath.endsWith("/")) currentPath + path else "$currentPath/$path"
        val stack = mutableListOf<String>()
        for (seg in abs.split('/')) {
            if (seg.isEmpty() || seg == ".") continue
            if (seg == "..") { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) } else stack.add(seg)
        }
        return "/" + stack.joinToString("/")
    }

    private fun parseAndShow(output: String) {
        val swipe = findViewById<SwipeRefreshLayout>(R.id.swipe_mount_browser)
        val empty = findViewById<TextView>(R.id.text_empty)
        swipe.isRefreshing = false
        updateMountInfo("Ready")
        val lines = output.replace("\r", "").split("\n")
        val base = lines
            .map { it.trim() }
            .filter { it.isNotBlank() }
            // Always exclude current directory entry
            .filter { it != "." && it != "./" }
            .map { name ->
                val isDir = name.endsWith('/') || name == ".." || name == "../"
                FileEntry(name = name.trimEnd('/'), isDir = isDir)
            }
            .toMutableList()
        // Ensure parent directory entry ".." is always visible
        if (base.none { it.name == ".." }) {
            base.add(0, FileEntry(name = "..", isDir = true))
        }
        // Apply directories-first sorting, then A→Z or Z→A, keeping ".." at the top
        val parent = base.firstOrNull { it.name == ".." }
        val others = base.filter { it.name != ".." }
        val dirs = others.filter { it.isDir }
        val files = others.filter { !it.isDir }
        val comparator = compareBy<FileEntry> { it.name.lowercase() }
        val sortedDirs = if (sortAsc) dirs.sortedWith(comparator) else dirs.sortedWith(comparator.reversed())
        val sortedFiles = if (sortAsc) files.sortedWith(comparator) else files.sortedWith(comparator.reversed())
        val result = mutableListOf<FileEntry>()
        if (parent != null) result.add(parent)
        result.addAll(sortedDirs)
        result.addAll(sortedFiles)
        filesAdapter.submit(result)
        empty.visibility = if (others.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        currentPath = findViewById<TextView>(R.id.text_current_path).text.toString()
    }

    private fun openFilePreview(entry: FileEntry) {
        val fullPath = if (currentPath == "/") "/${entry.name}" else "$currentPath/${entry.name}"
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottomsheet_file_preview, null)
        sheet.setContentView(view)
        val title = view.findViewById<TextView>(R.id.text_preview_title)
        val pathView = view.findViewById<TextView>(R.id.text_preview_path)
        val content = view.findViewById<TextView>(R.id.text_preview_content)
        val progress = view.findViewById<android.widget.ProgressBar>(R.id.progress_preview)
        title.text = entry.name
        pathView.text = fullPath
        content.text = "Loading..."
        progress.visibility = android.view.View.VISIBLE
        sheet.show()

        val prefs = Prefs(this)
        val api = PortainerApi.create(this, prefs.baseUrl(), prefs.token())
        val cmd = buildPreviewCommand(currentPath, entry.name)
        val execRequest = ContainerExecRequest(
            Cmd = listOf("/bin/sh", "-c", cmd),
            AttachStdout = true,
            AttachStderr = true
        )
        lifecycleScope.launch {
            try {
                val execResponse = withContext(Dispatchers.IO) {
                    api.containerExec(endpointId, containerId ?: "", execRequest, agentTarget)
                }
                val execId = execResponse.Id
                if (!execId.isNullOrBlank()) {
                    val client = ExecWebSocketClient(
                        context = this@MountBrowserActivity,
                        baseUrl = prefs.baseUrl(),
                        apiToken = prefs.token(),
                        onMessage = { msg ->
                            runOnUiThread { content.text = msg }
                        },
                        onError = { err ->
                            runOnUiThread {
                                progress.visibility = android.view.View.GONE
                                content.text = "Error: $err"
                            }
                        },
                        onClosed = {
                            runOnUiThread { progress.visibility = android.view.View.GONE }
                        }
                    )
                    client.connect(endpointId, execId, agentTarget)
                } else {
                    progress.visibility = android.view.View.GONE
                    content.text = "Unable to create exec session"
                }
            } catch (e: Exception) {
                progress.visibility = android.view.View.GONE
                content.text = "Error: ${e.message}"
            }
        }
    }

    private fun buildPreviewCommand(dir: String, name: String): String {
        val escapedDir = dir.replace("'", "'\"'\"'")
        val escapedName = name.replace("'", "'\"'\"'")
        return "cd -- '$escapedDir' || exit; if [ -f '$escapedName' ]; then if command -v head >/dev/null 2>&1; then head -c 65536 -- '$escapedName'; else sed -n '1,400p' -- '$escapedName'; fi; else printf '%s' 'Not a regular file'; fi; printf '%s' '---VibetainerEOF---'"
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.menu_mount_browser, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_show_hidden -> {
                showHidden = !showHidden
                Prefs(this).setMountShowHidden(showHidden)
                invalidateOptionsMenu()
                loadDirectory(currentPath)
                true
            }
            R.id.action_sort_az -> {
                sortAsc = true
                Prefs(this).setMountSortAsc(true)
                invalidateOptionsMenu()
                loadDirectory(currentPath)
                true
            }
            R.id.action_sort_za -> {
                sortAsc = false
                Prefs(this).setMountSortAsc(false)
                invalidateOptionsMenu()
                loadDirectory(currentPath)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onPrepareOptionsMenu(menu: android.view.Menu): Boolean {
        menu.findItem(R.id.action_show_hidden)?.isChecked = showHidden
        menu.findItem(R.id.action_sort_az)?.isChecked = sortAsc
        menu.findItem(R.id.action_sort_za)?.isChecked = !sortAsc
        // No "up" menu item per request
        return super.onPrepareOptionsMenu(menu)
    }

    private fun navigateUp() {
        val atFsRoot = currentPath.removeSuffix("/") == ""
        if (atFsRoot) { finish(); return }
        val parent = currentPath.removeSuffix("/").substringBeforeLast('/', missingDelimiterValue = "/")
        loadDirectory(if (parent.isBlank()) "/" else parent)
    }

    override fun onBackPressed() {
        val atFsRoot = currentPath.removeSuffix("/") == ""
        if (!atFsRoot) navigateUp() else super.onBackPressed()
    }
}

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
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.widget.ProgressBar
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import android.util.Base64
import org.json.JSONObject
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive

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
    private var pendingDownloadPath: String? = null
    private var pendingDownloadDirPath: String? = null
    private var saveAsTarForDir: Boolean = false
    private var currentDownloadJob: Job? = null

    private val createDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri: Uri? ->
        val fullPath = pendingDownloadPath
        if (uri != null && !fullPath.isNullOrBlank()) {
            if (saveAsTarForDir) {
                startTarSaveToUri(uri, fullPath)
            } else {
                startDownloadToUri(uri, fullPath)
            }
        } else if (fullPath != null) {
            Snackbar.make(findViewById(android.R.id.content), "Download canceled", Snackbar.LENGTH_SHORT).show()
        }
        pendingDownloadPath = null
        saveAsTarForDir = false
    }

    private val openDocumentTreeLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        val dirPath = pendingDownloadDirPath
        if (uri != null && !dirPath.isNullOrBlank()) {
            startDirectoryDownloadToTree(uri, dirPath)
        } else if (dirPath != null) {
            Snackbar.make(findViewById(android.R.id.content), "Download canceled", Snackbar.LENGTH_SHORT).show()
        }
        pendingDownloadDirPath = null
    }
    
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

        filesAdapter = FileEntryAdapter(
            onClick = { entry ->
                if (entry.isDir) {
                    val next = if (currentPath == "/") "/${entry.name}" else "$currentPath/${entry.name}"
                    loadDirectory(next)
                } else {
                    promptDownload(entry)
                }
            },
            onDownload = { entry ->
                if (entry.name == "..") {
                    showEntryDetails(entry)
                } else {
                    promptDownload(entry)
                }
            },
            onDetails = { entry ->
                showEntryDetails(entry)
            }
        )
        val rv = findViewById<RecyclerView>(R.id.recycler_files)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = filesAdapter
        updateMountInfo("Loading...")

        // Setup hint visibility and dismiss behavior
        val hint = findViewById<TextView>(R.id.text_hint_download)
        val prefs = Prefs(this)
        if (prefs.mountDownloadHintDismissed()) {
            hint.visibility = android.view.View.GONE
        } else {
            hint.visibility = android.view.View.VISIBLE
            hint.setOnClickListener {
                Prefs(this).setMountDownloadHintDismissed(true)
                hint.visibility = android.view.View.GONE
            }
        }
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

    private fun promptDownload(entry: FileEntry) {
        val fullPath = if (currentPath == "/") "/${entry.name}" else "$currentPath/${entry.name}"
        if (entry.isDir) {
            val dlg = MaterialAlertDialogBuilder(this)
                .setTitle("Download directory?")
                .setMessage("$fullPath\nSize: unknown")
                .setPositiveButton("Choose Folder") { _, _ ->
                    pendingDownloadDirPath = fullPath
                    openDocumentTreeLauncher.launch(null)
                }
                .setNeutralButton("Save as .tar") { _, _ ->
                    pendingDownloadPath = fullPath
                    saveAsTarForDir = true
                    val tarName = entry.name.removeSuffix("/") + ".tar"
                    createDocumentLauncher.launch(tarName)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
            dlg.show()
        } else {
            val dlg = MaterialAlertDialogBuilder(this)
                .setTitle("Download file?")
                .setMessage("$fullPath\nFetching size...")
                .setPositiveButton("Save As") { _, _ ->
                    pendingDownloadPath = fullPath
                    createDocumentLauncher.launch(entry.name)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
            dlg.show()
            lifecycleScope.launch {
                val size = fetchFileSize(fullPath)
                val msg = if (size != null && size >= 0) "$fullPath\nSize: ${formatBytes(size)}" else "$fullPath\nSize: unknown"
                dlg.findViewById<TextView>(android.R.id.message)?.text = msg
            }
        }
    }

    private fun showEntryDetails(entry: FileEntry) {
        val fullPath = if (currentPath == "/") "/${entry.name}" else "$currentPath/${entry.name}"
        val title = entry.name.ifBlank { fullPath }
        val view = layoutInflater.inflate(R.layout.dialog_details, null)
        val detailsMsg = view.findViewById<TextView>(R.id.details_message)
        val calcContainer = view.findViewById<android.view.View>(R.id.calc_container)
        detailsMsg.text = "Loading..."

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle("Details")
            .setView(view)
            .setPositiveButton(android.R.string.ok, null)
        if (entry.isDir) {
            builder.setNeutralButton(getString(R.string.action_calculate_size), null)
        }
        val dlg = builder.create()
        dlg.show()

        var baseDetails: String = ""
        lifecycleScope.launch {
            val size = fetchFileSize(fullPath)
            val type = if (entry.isDir) "Directory" else (guessMimeTypeFromName(entry.name) ?: "File")
            val sb = StringBuilder()
            sb.append("Name: ").append(title)
            sb.append("\nPath: ").append(fullPath)
            sb.append("\nType: ").append(type)
            if (size != null && size >= 0) sb.append("\nSize: ").append(formatBytes(size))
            baseDetails = sb.toString()
            detailsMsg.text = baseDetails
        }

        if (entry.isDir) {
            // Wire up Calculate Size after show so we can override click behavior
            val btn = dlg.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL)
            btn?.setOnClickListener {
                btn.isEnabled = false
                val base = if (baseDetails.isNotEmpty()) baseDetails else (detailsMsg.text?.toString() ?: "")
                detailsMsg.text = base
                calcContainer.visibility = android.view.View.VISIBLE
                calculateDirectorySize(fullPath) { bytes, error ->
                    btn.isEnabled = true
                    calcContainer.visibility = android.view.View.GONE
                    val resolvedBase = if (baseDetails.isNotEmpty()) baseDetails else (detailsMsg.text?.toString() ?: "")
                    val extra = if (error != null) "\nTotal size: error (${error})" else "\nTotal size: ${formatBytes(bytes ?: 0)}"
                    detailsMsg.text = resolvedBase + extra
                }
            }
        }
    }

    private fun calculateDirectorySize(path: String, callback: (Long?, String?) -> Unit) {
        val prefs = Prefs(this)
        val api = PortainerApi.create(this, prefs.baseUrl(), prefs.token())
        val cmd = buildDirSizeCommand(path)
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
                    var buffer = StringBuilder()
                    val client = ExecWebSocketClient(
                        context = this@MountBrowserActivity,
                        baseUrl = prefs.baseUrl(),
                        apiToken = prefs.token(),
                        onMessage = { msg -> buffer.append(msg) },
                        onError = { err -> runOnUiThread { callback(null, err) } },
                        onClosed = {
                            val out = buffer.toString()
                            val parsed = parseDirSizeOutput(out)
                            runOnUiThread {
                                if (parsed != null) callback(parsed, null) else callback(null, "Unable to parse size")
                            }
                        }
                    )
                    client.connect(endpointId, execId, agentTarget)
                } else {
                    callback(null, "Unable to create exec session")
                }
            } catch (e: Exception) {
                callback(null, e.message)
            }
        }
    }

    private fun buildDirSizeCommand(path: String): String {
        val escaped = path.replace("'", "'\"'\"'")
        return "cd -- '$escaped' || exit; " +
                "if out=\"$(du -sb . 2>/dev/null)\"; then echo BYTES:${'$'}{out%%[[:space:]]*}; " +
                "elif out=\"$(du -sk . 2>/dev/null)\"; then echo KB:${'$'}{out%%[[:space:]]*}; " +
                "elif out=\"$(du -s . 2>/dev/null)\"; then echo KDU:${'$'}{out%%[[:space:]]*}; " +
                "elif command -v find >/dev/null 2>&1 && command -v awk >/dev/null 2>&1 && command -v stat >/dev/null 2>&1; then echo BYTES:$(find . -type f -print0 | xargs -0 stat -c %s 2>/dev/null | awk '{s+=$1} END {print s+0}'); " +
                "else echo BYTES:0; fi; printf '%s' '---VibetainerEOF---'"
    }

    private fun parseDirSizeOutput(out: String): Long? {
        val clean = out.replace("\r", "").trim()
        val line = clean.lines().firstOrNull { it.startsWith("BYTES:") || it.startsWith("KB:") || it.startsWith("KDU:") }
            ?: return null
        return try {
            when {
                line.startsWith("BYTES:") -> line.removePrefix("BYTES:").trim().toLong()
                line.startsWith("KB:") -> line.removePrefix("KB:").trim().toLong() * 1024L
                line.startsWith("KDU:") -> line.removePrefix("KDU:").trim().toLong() * 1024L
                else -> null
            }
        } catch (_: Exception) { null }
    }

    private fun startDownloadToUri(target: Uri, fullPath: String) {
        val progressDialog = MaterialAlertDialogBuilder(this)
            .setView(ProgressBar(this))
            .setCancelable(true)
            .setNegativeButton("Cancel", null)
            .create()
        progressDialog.show()

        val prefs = Prefs(this)
        val api = PortainerApi.create(this, prefs.baseUrl(), prefs.token())

        currentDownloadJob = lifecycleScope.launch {
            try {
                val out = withContext(Dispatchers.IO) { contentResolver.openOutputStream(target) }
                if (out == null) throw IllegalStateException("Unable to open output stream")

                val body = withContext(Dispatchers.IO) {
                    // Docker API returns a TAR stream of the path
                    api.containerGetArchive(
                        endpointId = endpointId,
                        id = containerId ?: "",
                        path = fullPath,
                        agentTarget = agentTarget
                    )
                }

                withContext(Dispatchers.IO) {
                    body.use { resp ->
                        val success = extractFirstFileFromTar(resp.byteStream(), out) { !isActive }
                        out.flush()
                        out.close()
                        if (!success) throw IllegalStateException("Archive did not contain a regular file")
                    }
                }

                progressDialog.dismiss()
                val viewMime = contentResolver.getType(target)
                    ?: guessMimeTypeFromName(fullPath)
                    ?: "*/*"
                Snackbar.make(findViewById(android.R.id.content), "File saved", Snackbar.LENGTH_LONG)
                    .setAction("Open File") {
                        try {
                            val open = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                setDataAndType(target, viewMime)
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            startActivity(open)
                        } catch (_: Exception) {
                            Snackbar.make(findViewById(android.R.id.content), "No app to open file", Snackbar.LENGTH_SHORT).show()
                        }
                    }
                    .show()
            } catch (e: Exception) {
                progressDialog.dismiss()
                if (e is kotlinx.coroutines.CancellationException) {
                    Snackbar.make(findViewById(android.R.id.content), "Download canceled", Snackbar.LENGTH_SHORT).show()
                } else {
                    Snackbar.make(
                        findViewById(android.R.id.content),
                        "Error: ${e.message}",
                        Snackbar.LENGTH_LONG
                    ).show()
                }
            }
        }
        progressDialog.setOnShowListener {
            (progressDialog as AlertDialog).getButton(AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener {
                currentDownloadJob?.cancel()
                progressDialog.dismiss()
            }
        }
    }

    private fun startDirectoryDownloadToTree(treeUri: Uri, dirPath: String) {
        val progressDialog = MaterialAlertDialogBuilder(this)
            .setView(ProgressBar(this))
            .setCancelable(true)
            .setNegativeButton("Cancel", null)
            .create()
        progressDialog.show()

        val prefs = Prefs(this)
        val api = PortainerApi.create(this, prefs.baseUrl(), prefs.token())
        val rootDoc = DocumentFile.fromTreeUri(this, treeUri)
            ?: run {
                progressDialog.dismiss()
                Snackbar.make(findViewById(android.R.id.content), "Invalid destination folder", Snackbar.LENGTH_LONG).show()
                return
            }

        currentDownloadJob = lifecycleScope.launch {
            try {
                val body = withContext(Dispatchers.IO) {
                    api.containerGetArchive(
                        endpointId = endpointId,
                        id = containerId ?: "",
                        path = dirPath,
                        agentTarget = agentTarget
                    )
                }
                withContext(Dispatchers.IO) {
                    body.use { resp ->
                        extractTarToTree(resp.byteStream(), rootDoc) { !isActive }
                    }
                }
                progressDialog.dismiss()
                Snackbar.make(findViewById(android.R.id.content), "Directory saved", Snackbar.LENGTH_LONG).show()
            } catch (e: Exception) {
                progressDialog.dismiss()
                if (e is kotlinx.coroutines.CancellationException) {
                    Snackbar.make(findViewById(android.R.id.content), "Download canceled", Snackbar.LENGTH_SHORT).show()
                } else {
                    Snackbar.make(findViewById(android.R.id.content), "Error: ${e.message}", Snackbar.LENGTH_LONG).show()
                }
            }
        }
        progressDialog.setOnShowListener {
            (progressDialog as AlertDialog).getButton(AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener {
                currentDownloadJob?.cancel()
                progressDialog.dismiss()
            }
        }
    }

    private fun startTarSaveToUri(target: Uri, fullPath: String) {
        val progressDialog = MaterialAlertDialogBuilder(this)
            .setView(ProgressBar(this))
            .setCancelable(true)
            .setNegativeButton("Cancel", null)
            .create()
        progressDialog.show()

        val prefs = Prefs(this)
        val api = PortainerApi.create(this, prefs.baseUrl(), prefs.token())
        currentDownloadJob = lifecycleScope.launch {
            try {
                val out = withContext(Dispatchers.IO) { contentResolver.openOutputStream(target) }
                if (out == null) throw IllegalStateException("Unable to open output stream")
                val body = withContext(Dispatchers.IO) {
                    api.containerGetArchive(endpointId, containerId ?: "", fullPath, agentTarget)
                }
                withContext(Dispatchers.IO) {
                    body.use { resp ->
                        resp.byteStream().use { input ->
                            val buf = ByteArray(64 * 1024)
                            while (isActive) {
                                val r = input.read(buf)
                                if (r == -1) break
                                out.write(buf, 0, r)
                            }
                            out.flush()
                            out.close()
                        }
                    }
                }
                progressDialog.dismiss()
                Snackbar.make(findViewById(android.R.id.content), "TAR saved", Snackbar.LENGTH_LONG).show()
            } catch (e: Exception) {
                progressDialog.dismiss()
                if (e is kotlinx.coroutines.CancellationException) {
                    Snackbar.make(findViewById(android.R.id.content), "Download canceled", Snackbar.LENGTH_SHORT).show()
                } else {
                    Snackbar.make(findViewById(android.R.id.content), "Error: ${e.message}", Snackbar.LENGTH_LONG).show()
                }
            }
        }
        progressDialog.setOnShowListener {
            (progressDialog as AlertDialog).getButton(AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener {
                currentDownloadJob?.cancel()
                progressDialog.dismiss()
            }
        }
    }

private fun extractTarToTree(input: java.io.InputStream, root: DocumentFile, shouldCancel: () -> Boolean = { false }) {
    val header = ByteArray(512)
    while (true) {
        if (shouldCancel()) throw kotlinx.coroutines.CancellationException()
        val read = readFully(input, header)
        if (read == -1) return
        if (isZeroBlock(header)) return

            val name = readTarName(header)
            val typeFlag = header[156].toInt()
            val size = parseTarSize(header, 124, 12)

            when (typeFlag) {
                '5'.code -> { // directory
                    ensureTreeDir(root, name)
                    // no content
                }
                0, '0'.code -> { // regular file
                    val parent = name.substringBeforeLast('/', "")
                    val base = name.substringAfterLast('/')
                    val dirDoc = ensureTreeDir(root, parent)
                    val mime = guessMimeTypeFromName(base) ?: "application/octet-stream"
                    val existing = dirDoc.findFile(base)
                    if (existing != null) existing.delete()
                    val fileDoc = dirDoc.createFile(mime, base)
                        ?: throw IllegalStateException("Unable to create file: $base")
            contentResolver.openOutputStream(fileDoc.uri)?.use { out ->
                copyExactlyCancellable(input, out, size, shouldCancel)
            } ?: throw IllegalStateException("Unable to open output stream for: $base")
            skipPadding(input, size)
        }
        else -> {
            // skip unknown type
            skipExactlyCancellable(input, size, shouldCancel)
            skipPadding(input, size)
        }
    }
}
    }

    private fun readTarName(header: ByteArray): String {
        val nameBytes = header.copyOfRange(0, 100)
        val prefixBytes = header.copyOfRange(345, 500)
        val name = bytesToString(nameBytes)
        val prefix = bytesToString(prefixBytes)
        return if (prefix.isNotEmpty()) "$prefix/$name" else name
    }

    private fun bytesToString(b: ByteArray): String {
        var end = b.size
        for (i in b.indices) {
            if (b[i].toInt() == 0) { end = i; break }
        }
        return if (end <= 0) "" else String(b, 0, end, Charsets.UTF_8)
    }

    private fun ensureTreeDir(root: DocumentFile, relative: String): DocumentFile {
        var cur = root
        if (relative.isBlank()) return cur
        val parts = relative.trim('/').split('/')
        for (p in parts) {
            if (p.isBlank()) continue
            val existing = cur.findFile(p)
            cur = if (existing != null && existing.isDirectory) {
                existing
            } else {
                existing?.delete()
                cur.createDirectory(p) ?: throw IllegalStateException("Cannot create directory: $p")
            }
        }
        return cur
    }

    private fun guessMimeTypeFromName(name: String): String? {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isBlank()) return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    }

    private fun extractFirstFileFromTar(input: java.io.InputStream, output: java.io.OutputStream, shouldCancel: () -> Boolean = { false }): Boolean {
        val header = ByteArray(512)
        while (true) {
            if (shouldCancel()) throw kotlinx.coroutines.CancellationException()
            val read = readFully(input, header)
            if (read == -1) return false
            if (isZeroBlock(header)) return false // End of archive

            val typeFlag = header[156].toInt()
            val size = parseTarSize(header, 124, 12)

            val isRegular = (typeFlag == '0'.code) || (typeFlag == 0)
            if (isRegular) {
                copyExactlyCancellable(input, output, size, shouldCancel)
                // Skip padding up to 512 boundary
                skipPadding(input, size)
                return true
            } else {
                // Skip entry content + padding
                skipExactlyCancellable(input, size, shouldCancel)
                skipPadding(input, size)
            }
        }
    }

    private fun readFully(input: java.io.InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val r = input.read(buf, off, buf.size - off)
            if (r == -1) return if (off == 0) -1 else off
            off += r
        }
        return off
    }

    private fun isZeroBlock(block: ByteArray): Boolean {
        for (b in block) if (b.toInt() != 0) return false
        return true
        }

    private fun parseTarSize(header: ByteArray, offset: Int, length: Int): Long {
        var i = offset
        var end = offset + length
        // Trim nulls and spaces
        while (i < end && (header[i].toInt() == 0 || header[i].toInt() == 32)) i++
        while (end > i && (header[end - 1].toInt() == 0 || header[end - 1].toInt() == 32)) end--
        var result = 0L
        for (j in i until end) {
            val c = header[j].toInt()
            if (c < '0'.code || c > '7'.code) break
            result = (result shl 3) + (c - '0'.code)
        }
        return result
    }

    private fun copyExactly(input: java.io.InputStream, output: java.io.OutputStream, size: Long) {
        var remaining = size
        val buf = ByteArray(64 * 1024)
        while (remaining > 0) {
            val toRead = if (remaining > buf.size) buf.size else remaining.toInt()
            val r = input.read(buf, 0, toRead)
            if (r == -1) throw java.io.EOFException("Unexpected EOF in tar entry")
            output.write(buf, 0, r)
            remaining -= r
        }
    }

    private fun copyExactlyCancellable(input: java.io.InputStream, output: java.io.OutputStream, size: Long, shouldCancel: () -> Boolean) {
        var remaining = size
        val buf = ByteArray(64 * 1024)
        while (remaining > 0) {
            if (shouldCancel()) throw kotlinx.coroutines.CancellationException()
            val toRead = if (remaining > buf.size) buf.size else remaining.toInt()
            val r = input.read(buf, 0, toRead)
            if (r == -1) throw java.io.EOFException("Unexpected EOF in tar entry")
            output.write(buf, 0, r)
            remaining -= r
        }
    }

    private fun skipExactly(input: java.io.InputStream, size: Long) {
        var remaining = size
        val buf = ByteArray(32 * 1024)
        while (remaining > 0) {
            val toRead = if (remaining > buf.size) buf.size else remaining.toInt()
            val r = input.read(buf, 0, toRead)
            if (r == -1) throw java.io.EOFException("Unexpected EOF skipping tar entry")
            remaining -= r
        }
    }

    private fun skipExactlyCancellable(input: java.io.InputStream, size: Long, shouldCancel: () -> Boolean) {
        var remaining = size
        val buf = ByteArray(32 * 1024)
        while (remaining > 0) {
            if (shouldCancel()) throw kotlinx.coroutines.CancellationException()
            val toRead = if (remaining > buf.size) buf.size else remaining.toInt()
            val r = input.read(buf, 0, toRead)
            if (r == -1) throw java.io.EOFException("Unexpected EOF skipping tar entry")
            remaining -= r
        }
    }

    private fun skipPadding(input: java.io.InputStream, size: Long) {
        val pad = ((512 - (size % 512)) % 512).toInt()
        if (pad > 0) {
            skipExactly(input, pad.toLong())
        }
    }

    private suspend fun fetchFileSize(path: String): Long? {
        return try {
            val prefs = Prefs(this)
            val api = PortainerApi.create(this, prefs.baseUrl(), prefs.token())
            val resp = api.containerStatArchive(endpointId, containerId ?: "", path, agentTarget)
            val statB64 = resp.headers()["X-Docker-Container-Path-Stat"] ?: return null
            val decoded = String(Base64.decode(statB64, Base64.DEFAULT), Charsets.UTF_8)
            val json = JSONObject(decoded)
            if (json.has("size")) json.getLong("size") else null
        } catch (_: Exception) { null }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB","MB","GB","TB")
        var v = bytes.toDouble() / 1024.0
        var i = 0
        while (v >= 1024.0 && i < units.size - 1) { v /= 1024.0; i++ }
        return String.format(java.util.Locale.US, "%.1f %s", v, units[i])
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

package com.agreenbhm.vibetainer.ui

import android.os.Bundle
import android.widget.TextView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.view.View
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope

import com.agreenbhm.vibetainer.R
import com.agreenbhm.vibetainer.network.PortainerApi
import com.agreenbhm.vibetainer.util.Prefs
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.content.Intent
 

class ContainerDetailActivity : AppCompatActivity() {
    private var agentTarget: String? = null
    // No overflow menu on this screen
    private fun demuxDockerLogs(bytes: ByteArray): String {
        // Docker stdcopy: frame = 8-byte header + payload; header: [stream(1)][000][len(4, BE)]
        val sb = StringBuilder()
        var i = 0
        var frames = 0
        while (i + 8 <= bytes.size) {
            val len = ((bytes[i + 4].toInt() and 0xFF) shl 24) or
                    ((bytes[i + 5].toInt() and 0xFF) shl 16) or
                    ((bytes[i + 6].toInt() and 0xFF) shl 8) or
                    (bytes[i + 7].toInt() and 0xFF)
            val start = i + 8
            val end = start + len
            if (len < 0 || end > bytes.size) break
            val chunk = bytes.copyOfRange(start, end)
            sb.append(String(chunk, Charsets.UTF_8))
            frames++
            i = end
        }
        if (frames > 0 && sb.isNotEmpty()) return sb.toString()
        // Not multiplexed, interpret as UTF-8
        return String(bytes, Charsets.UTF_8)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_container_detail)

        val endpointId = intent.getIntExtra(EXTRA_ENDPOINT_ID, -1)
        val containerId = intent.getStringExtra(EXTRA_CONTAINER_ID) ?: return finish()
        agentTarget = intent.getStringExtra(EXTRA_AGENT_TARGET)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_container)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        com.agreenbhm.vibetainer.ui.EdgeToEdge.apply(this, toolbar, findViewById(R.id.scroll_container))

        val txtName = findViewById<TextView>(R.id.text_container_name)
        val txtImage = findViewById<TextView>(R.id.text_container_image)
        val txtStatus = findViewById<TextView>(R.id.text_container_status)
        val txtAffinity = findViewById<TextView>(R.id.text_container_affinity)
        val txtLogs = findViewById<TextView>(R.id.text_container_logs)
        val spinnerTail = findViewById<Spinner>(R.id.spinner_tail)
        val switchTimestamps = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_timestamps)
        val switchAuto = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_autorefresh)
        val switchFollow = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_follow)
        val swipe = findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout>(R.id.swipe_container)
        val scroll = findViewById<android.widget.ScrollView>(R.id.scroll_container)
        var userTouching = false
        var followLogs = false // toggled via switch or user scroll-to-bottom
        scroll.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> userTouching = true
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> userTouching = false
            }
            false
        }
        scroll.viewTreeObserver.addOnScrollChangedListener {
            val view = scroll.getChildAt(0)
            val diff = view.bottom - (scroll.height + scroll.scrollY)
            val atBottom = diff <= 8
            if (userTouching) {
                // Reflect manual intent in the toggle: on when at bottom, off when scrolled up
                if (atBottom && !switchFollow.isChecked) switchFollow.isChecked = true
                if (!atBottom && switchFollow.isChecked) switchFollow.isChecked = false
            }
        }
        // start/stop buttons moved to the action bar menu

        val prefs = Prefs(this)
        val api = PortainerApi.create(this, prefs.baseUrl(), prefs.token())

        // Controls defaults (persisted)
        val tails = listOf(50, 200, 1000)
        spinnerTail.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, tails)
        val tailPref = prefs.logsTail()
        val tailIndex = tails.indexOf(tailPref).let { if (it >= 0) it else 1 }
        spinnerTail.setSelection(tailIndex)
        switchTimestamps.isChecked = prefs.logsTimestamps()
        switchAuto.isChecked = prefs.logsAutoRefresh()

        // start/stop moved to menu actions

        // Pull-to-refresh support
        swipe.setOnRefreshListener { refresh(true) }
        refresh()
        switchTimestamps.setOnCheckedChangeListener { _, isChecked ->
            prefs.setLogsTimestamps(isChecked)
            if (!switchAuto.isChecked) refresh(true)
        }
        spinnerTail.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                prefs.setLogsTail(tails[position])
                if (!switchAuto.isChecked) refresh(true)
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
        switchAuto.setOnCheckedChangeListener { _, isChecked -> prefs.setLogsAutoRefresh(isChecked) }
        switchFollow.setOnCheckedChangeListener { _, isChecked ->
            followLogs = isChecked
            if (isChecked) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
        lifecycleScope.launch {
            while (true) {
                kotlinx.coroutines.delay(1000)
                if (switchAuto.isChecked) refresh(false)
            }
        }

        // Streaming removed per request; using snapshot only
    }
    private fun refresh(reset: Boolean = false) {
        val endpointId = intent.getIntExtra(EXTRA_ENDPOINT_ID, -1)
        val containerId = intent.getStringExtra(EXTRA_CONTAINER_ID) ?: return
        val prefs = Prefs(this)
        val api = PortainerApi.create(this, prefs.baseUrl(), prefs.token())
        val swipe = findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout>(R.id.swipe_container)
        swipe.isRefreshing = true
        lifecycleScope.launch {
            try {
                if (agentTarget.isNullOrBlank()) {
                    runCatching {
                        val containers = withContext(Dispatchers.IO) { api.listContainers(endpointId, true, null) }
                        val match = containers.firstOrNull { it.Id.startsWith(containerId) or containerId.startsWith(it.Id) or (it.Id == containerId) }
                        val nodeId = match?.Labels?.get("com.docker.swarm.node.id")
                        if (!nodeId.isNullOrBlank()) {
                            val nodes = withContext(Dispatchers.IO) { api.listNodes(endpointId) }
                            val node = nodes.firstOrNull { it.ID == nodeId }
                            agentTarget = node?.Description?.Hostname ?: agentTarget
                        }
                    }
                }

                val insp = withContext(Dispatchers.IO) { api.containerInspect(endpointId, containerId, agentTarget) }
                val nm = insp.Name?.removePrefix("/") ?: containerId.take(12)
                val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_container)
                toolbar.title = nm
                toolbar.subtitle = agentTarget
                findViewById<TextView>(R.id.text_container_name).text = nm
                findViewById<TextView>(R.id.text_container_image).text = "Image: ${cleanImageName(insp.Config?.Image ?: insp.Image)}"
                val status = insp.State?.Status ?: "unknown"
                findViewById<TextView>(R.id.text_container_status).text = "Status: ${status}"

                val svc = intent.getStringExtra(EXTRA_SERVICE_NAME).orEmpty()
                val stack = intent.getStringExtra(EXTRA_STACK_NAME).orEmpty()
                val txtAffinity = findViewById<TextView>(R.id.text_container_affinity)
                txtAffinity.text = buildString {
                    if (svc.isNotBlank()) append("Service: ").append(svc)
                    if (svc.isNotBlank() && stack.isNotBlank()) append("\n")
                    if (stack.isNotBlank()) append("Stack: ").append(stack)
                }
                txtAffinity.visibility = if (txtAffinity.text.isNullOrBlank()) View.GONE else View.VISIBLE

                val mountsContainer = findViewById<android.widget.LinearLayout>(R.id.container_mounts)
                mountsContainer.removeAllViews()
                val mounts = insp.Mounts ?: emptyList()
                if (mounts.isEmpty()) {
                    val tv = TextView(this@ContainerDetailActivity)
                    tv.text = "No mounts"
                    mountsContainer.addView(tv)
                } else {
                    for (m in mounts.sortedBy { it.Destination }) {
                        val card = layoutInflater.inflate(R.layout.item_mount_card, mountsContainer, false)
                        val sourceRow = card.findViewById<android.view.View>(R.id.mount_row_host)
                        val targetRow = card.findViewById<android.view.View>(R.id.mount_row_target)
                        val titleSrc = card.findViewById<TextView>(R.id.mount_source)
                        val titleTgt = card.findViewById<TextView>(R.id.mount_target)
                        val subtitle = card.findViewById<TextView>(R.id.mount_subtitle)
                        val chip = card.findViewById<com.google.android.material.chip.Chip>(R.id.mount_type_chip)
                        val browseButton = card.findViewById<com.google.android.material.button.MaterialButton>(R.id.mount_browse_button)
                        val sourceLabelView = card.findViewById<TextView>(R.id.mount_label_host)
                        val src = m.Source ?: ""
                        val tgt = m.Target ?: m.Destination ?: ""
                        val typ = m.Type ?: ""
                        
                        // Update source label based on mount type
                        sourceLabelView.text = when {
                            typ.equals("volume", ignoreCase = true) -> "Volume: "
                            typ.equals("bind", ignoreCase = true) -> "Host: "
                            typ.equals("tmpfs", ignoreCase = true) -> "Tmpfs: "
                            else -> "Source: " // fallback for unknown types
                        }
                        
                        // For volume mounts, show just the volume name
                        titleSrc.text = if (typ.equals("volume", ignoreCase = true)) {
                            // Extract volume name - handle both direct names and paths
                            when {
                                src.contains('/') -> src.substringBeforeLast("/_data").substringAfterLast("/")
                                src.isNotBlank() -> src
                                else -> "unnamed-volume"
                            }
                        } else {
                            src
                        }
                        titleTgt.text = tgt
                        targetRow.visibility = if (tgt.isNotBlank()) View.VISIBLE else View.GONE
                        chip.text = typ.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                        
                        // Show browse button for browsable mount types
                        val isBrowsable = typ.equals("volume", ignoreCase = true) || typ.equals("bind", ignoreCase = true)
                        browseButton.visibility = if (isBrowsable) View.VISIBLE else View.GONE
                        
                        if (isBrowsable) {
                            browseButton.setOnClickListener {
                                browseMountContents(containerId, tgt)
                            }
                        }
                        if (typ.equals("volume", ignoreCase = true)) {
                            val driver = m.VolumeOptions?.DriverConfig?.Name ?: ""
                            val opts = m.VolumeOptions?.DriverConfig?.Options?.entries?.joinToString(", ") { "${it.key}=${it.value}" } ?: ""
                            val labels = m.VolumeOptions?.Labels?.entries?.joinToString(", ") { "${it.key}=${it.value}" } ?: ""
                            val parts = listOfNotNull(if (driver.isNotBlank()) "driver=$driver" else null, if (opts.isNotBlank()) opts else null, if (labels.isNotBlank()) labels else null)
                            subtitle.text = parts.joinToString(" • ")
                            subtitle.visibility = if (subtitle.text.isNullOrBlank()) View.GONE else View.VISIBLE
                            
                            // Add click listener for volume cards to navigate to VolumeDetailActivity
                            card.setOnClickListener {
                                val volumeName = titleSrc.text.toString()
                                val intent = Intent(this@ContainerDetailActivity, VolumeDetailActivity::class.java).apply {
                                    putExtra(VolumeDetailActivity.EXTRA_ENDPOINT_ID, endpointId)
                                    putExtra(VolumeDetailActivity.EXTRA_VOLUME_NAME, volumeName)
                                    putExtra(VolumeDetailActivity.EXTRA_AGENT_TARGET, agentTarget)
                                }
                                startActivity(intent)
                            }
                        } else {
                            subtitle.text = ""
                            subtitle.visibility = View.GONE
                        }
                        mountsContainer.addView(card)
                    }
                }

                val tails = listOf(50, 200, 1000)
                val spinnerTail = findViewById<Spinner>(R.id.spinner_tail)
                val switchTimestamps = findViewById<MaterialSwitch>(R.id.switch_timestamps)
                val switchAuto = findViewById<MaterialSwitch>(R.id.switch_autorefresh)
                val switchFollow = findViewById<MaterialSwitch>(R.id.switch_follow)
                val scroll = findViewById<android.widget.ScrollView>(R.id.scroll_container)
                val tail = tails[spinnerTail.selectedItemPosition]
                val logsBody = withContext(Dispatchers.IO) {
                    runCatching {
                        api.containerLogs(
                            endpointId = endpointId,
                            id = containerId,
                            stdout = 1,
                            stderr = 1,
                            tail = tail,
                            timestamps = if (switchTimestamps.isChecked) 1 else 0,
                            follow = 0,
                            agentTarget = agentTarget
                        )
                    }.getOrNull()
                }
                val bytes = withContext(Dispatchers.IO) { logsBody?.bytes() }
                val snapshot = if (bytes != null) demuxDockerLogs(bytes) else ""
                findViewById<TextView>(R.id.text_container_logs).text = snapshot
                if (switchFollow.isChecked) {
                    val vto = scroll.viewTreeObserver
                    val listener = object : android.view.ViewTreeObserver.OnPreDrawListener {
                        override fun onPreDraw(): Boolean {
                            scroll.viewTreeObserver.removeOnPreDrawListener(this)
                            scroll.fullScroll(View.FOCUS_DOWN)
                            return true
                        }
                    }
                    vto.addOnPreDrawListener(listener)
                }
            } catch (e: Exception) {
                val msg = e.message ?: e.javaClass.simpleName
                findViewById<TextView>(R.id.text_container_status).text = "Error: ${msg}"
            } finally {
                swipe.isRefreshing = false
            }
        }
    }
    companion object {
        const val EXTRA_ENDPOINT_ID = "endpoint_id"
        const val EXTRA_CONTAINER_ID = "container_id"
        const val EXTRA_AGENT_TARGET = "agent_target"
        const val EXTRA_SERVICE_NAME = "service_name"
        const val EXTRA_STACK_NAME = "stack_name"
        const val EXTRA_IMAGE_NAME = "image_name"
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.menu_container_detail, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        val endpointId = intent.getIntExtra(EXTRA_ENDPOINT_ID, -1)
        val containerId = intent.getStringExtra(EXTRA_CONTAINER_ID) ?: return super.onOptionsItemSelected(item)
        when (item.itemId) {
            R.id.action_start -> {
                lifecycleScope.launch {
                    val prefs = Prefs(this@ContainerDetailActivity)
                    val api = PortainerApi.create(this@ContainerDetailActivity, prefs.baseUrl(), prefs.token())
                    withContext(Dispatchers.IO) { runCatching { api.containerStart(endpointId, containerId, agentTarget) } }
                    refresh()
                }
                return true
            }
            R.id.action_stop -> {
                lifecycleScope.launch {
                    val prefs = Prefs(this@ContainerDetailActivity)
                    val api = PortainerApi.create(this@ContainerDetailActivity, prefs.baseUrl(), prefs.token())
                    withContext(Dispatchers.IO) { runCatching { api.containerStop(endpointId, containerId, agentTarget) } }
                    refresh()
                }
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun cleanImageName(image: String?): String {
        if (image.isNullOrBlank()) return "Unknown"
        
        // Remove digest suffix if present (name@sha256:...)
        val cleanedImage = if (image.contains('@')) {
            image.substringBefore('@')
        } else {
            image
        }
        
        return when {
            // Hide pure SHA256 references (sha256:abc123...)
            cleanedImage.startsWith("sha256:") -> "Unknown"
            // Hide raw 64-character hex strings
            cleanedImage.matches(Regex("^[a-f0-9]{64}$")) -> "Unknown"
            // Return the clean image name
            cleanedImage.isNotBlank() -> cleanedImage
            else -> "Unknown"
        }
    }

    private fun browseMountContents(containerId: String, mountPath: String) {
        val endpointId = intent.getIntExtra(EXTRA_ENDPOINT_ID, -1)
        
        // Get container name from the current inspect data or use container ID
        val containerName = findViewById<MaterialToolbar>(R.id.toolbar_container).title?.toString()
        
        // Launch MountBrowserActivity with all necessary data
        val intent = Intent(this, MountBrowserActivity::class.java).apply {
            putExtra(MountBrowserActivity.EXTRA_ENDPOINT_ID, endpointId)
            putExtra(MountBrowserActivity.EXTRA_CONTAINER_ID, containerId)
            putExtra(MountBrowserActivity.EXTRA_CONTAINER_NAME, containerName)
            putExtra(MountBrowserActivity.EXTRA_MOUNT_PATH, mountPath)
            putExtra(MountBrowserActivity.EXTRA_AGENT_TARGET, agentTarget)
        }
        startActivity(intent)
    }
}

package com.agreenbhm.vibetainer.ui

import android.os.Bundle
import android.widget.Button
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
 

class ContainerDetailActivity : AppCompatActivity() {
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
        var agentTarget: String? = intent.getStringExtra(EXTRA_AGENT_TARGET)

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
        val buttonRefresh = findViewById<Button>(R.id.button_refresh_logs)
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
        val btnStart = findViewById<Button>(R.id.button_start)
        val btnStop = findViewById<Button>(R.id.button_stop)

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

        fun refresh(reset: Boolean = false) {
            lifecycleScope.launch {
                try {
                    // Resolve agent target as node hostname if not provided
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
                    toolbar.title = nm
                    txtName.text = nm
                    txtImage.text = insp.Image ?: ""
                    val status = insp.State?.Status ?: "unknown"
                    txtStatus.text = status

                    // Show service/stack from intent extras if provided
                    val svc = intent.getStringExtra(EXTRA_SERVICE_NAME).orEmpty()
                    val stack = intent.getStringExtra(EXTRA_STACK_NAME).orEmpty()
                    txtAffinity.text = buildString {
                        if (svc.isNotBlank()) append("Service: ").append(svc)
                        if (svc.isNotBlank() && stack.isNotBlank()) append(" • ")
                        if (stack.isNotBlank()) append("Stack: ").append(stack)
                    }
                    txtAffinity.visibility = if (txtAffinity.text.isNullOrBlank()) View.GONE else View.VISIBLE

                    // Show mounts (if any)
                    val mountsContainer = findViewById<android.widget.LinearLayout>(R.id.container_mounts)
                    mountsContainer.removeAllViews()
                    val mounts = insp.Mounts ?: emptyList()
                    if (mounts.isEmpty()) {
                        val tv = TextView(this@ContainerDetailActivity)
                        tv.text = "No mounts"
                        mountsContainer.addView(tv)
                    } else {
                        for (m in mounts) {
                            val row = layoutInflater.inflate(android.R.layout.simple_list_item_2, mountsContainer, false)
                            val title = row.findViewById<TextView>(android.R.id.text1)
                            val subtitle = row.findViewById<TextView>(android.R.id.text2)
                            val src = m.Source ?: ""
                            val tgt = m.Target ?: m.Destination ?: ""
                            val typ = m.Type ?: ""
                            if (typ.equals("volume", ignoreCase = true)) {
                                title.text = "Volume: $src → $tgt"
                                val driver = m.VolumeOptions?.DriverConfig?.Name ?: ""
                                val opts = m.VolumeOptions?.DriverConfig?.Options?.entries?.joinToString(", ") { "${it.key}=${it.value}" } ?: ""
                                val labels = m.VolumeOptions?.Labels?.entries?.joinToString(", ") { "${it.key}=${it.value}" } ?: ""
                                val parts = listOfNotNull(if (driver.isNotBlank()) "driver=$driver" else null, if (opts.isNotBlank()) opts else null, if (labels.isNotBlank()) labels else null)
                                subtitle.text = parts.joinToString(" • ")
                            } else {
                                title.text = "Bind: $src → $tgt"
                                subtitle.text = "Type: $typ"
                            }
                            mountsContainer.addView(row)
                        }
                    }

                    // Fetch a snapshot of recent logs
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
                    // Snapshot model: replace content each refresh to avoid duplicates
                    txtLogs.text = snapshot
                    if (followLogs) {
                        // Ensure we scroll to bottom before this frame is drawn to avoid mid-frame jumps
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
                    txtStatus.text = "Error: ${msg}"
                }
            }
        }

        

        btnStart.setOnClickListener {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { runCatching { api.containerStart(endpointId, containerId, agentTarget) } }
                refresh()
            }
        }
        btnStop.setOnClickListener {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { runCatching { api.containerStop(endpointId, containerId, agentTarget) } }
                refresh()
            }
        }

        refresh()
        buttonRefresh.setOnClickListener { refresh(false) }
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

    // Streaming helpers removed; using snapshot logs in refresh()
    companion object {
        const val EXTRA_ENDPOINT_ID = "endpoint_id"
        const val EXTRA_CONTAINER_ID = "container_id"
        const val EXTRA_AGENT_TARGET = "agent_target"
        const val EXTRA_SERVICE_NAME = "service_name"
        const val EXTRA_STACK_NAME = "stack_name"
    }
}

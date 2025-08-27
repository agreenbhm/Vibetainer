package com.example.portainerapp.ui

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
 
import com.example.portainerapp.R
import com.example.portainerapp.network.PortainerApi
import com.example.portainerapp.util.Prefs
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.launch
 

class ContainerDetailActivity : AppCompatActivity() {
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

        val txtName = findViewById<TextView>(R.id.text_container_name)
        val txtImage = findViewById<TextView>(R.id.text_container_image)
        val txtStatus = findViewById<TextView>(R.id.text_container_status)
        val txtLogs = findViewById<TextView>(R.id.text_container_logs)
        val spinnerTail = findViewById<Spinner>(R.id.spinner_tail)
        val switchTimestamps = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_timestamps)
        val switchAuto = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_autorefresh)
        val buttonRefresh = findViewById<Button>(R.id.button_refresh_logs)
        val scroll = findViewById<android.widget.ScrollView>(R.id.scroll_container)
        var isAtBottom = true
        scroll.viewTreeObserver.addOnScrollChangedListener {
            val view = scroll.getChildAt(0)
            val diff = view.bottom - (scroll.height + scroll.scrollY)
            isAtBottom = diff <= 8
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
                            val containers = api.listContainers(endpointId, true, null)
                            val match = containers.firstOrNull { it.Id.startsWith(containerId) or containerId.startsWith(it.Id) or (it.Id == containerId) }
                            val nodeId = match?.Labels?.get("com.docker.swarm.node.id")
                            if (!nodeId.isNullOrBlank()) {
                                val nodes = api.listNodes(endpointId)
                                val node = nodes.firstOrNull { it.ID == nodeId }
                                agentTarget = node?.Description?.Hostname ?: agentTarget
                            }
                        }
                    }
                    val insp = api.containerInspect(endpointId, containerId, agentTarget)
                    val nm = insp.Name?.removePrefix("/") ?: containerId.take(12)
                    toolbar.title = nm
                    txtName.text = nm
                    txtImage.text = insp.Image ?: ""
                    val status = insp.State?.Status ?: "unknown"
                    txtStatus.text = status

                    // Fetch a snapshot of recent logs
                    val tail = tails[spinnerTail.selectedItemPosition]
                    val logsBody = runCatching {
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
                    val bytes = logsBody?.bytes()
                    val snapshot = if (bytes != null) demuxDockerLogs(bytes) else ""
                    if (reset || txtLogs.text.isEmpty()) {
                        txtLogs.text = snapshot
                    } else {
                        val appendPart = computeAppend(txtLogs.text.toString(), snapshot)
                        if (appendPart.isNotEmpty()) {
                            txtLogs.append(appendPart)
                        }
                    }
                    if (isAtBottom) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                } catch (e: Exception) {
                    txtStatus.text = "Error: ${'$'}{e.message}"
                }
            }
        }

        

        btnStart.setOnClickListener {
            lifecycleScope.launch { runCatching { api.containerStart(endpointId, containerId, agentTarget) }; refresh() }
        }
        btnStop.setOnClickListener {
            lifecycleScope.launch { runCatching { api.containerStop(endpointId, containerId, agentTarget) }; refresh() }
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
        lifecycleScope.launch {
            while (true) {
                kotlinx.coroutines.delay(1000)
                if (switchAuto.isChecked) refresh(false)
            }
        }

        // Streaming removed per request; using snapshot only
    }

    private fun computeAppend(existing: String, snapshot: String): String {
        if (existing.isEmpty()) return snapshot
        if (snapshot.isEmpty()) return ""
        val maxOverlap = minOf(existing.length, snapshot.length, 4000)
        var k = maxOverlap
        while (k > 0) {
            val suffix = existing.substring(existing.length - k)
            if (snapshot.startsWith(suffix)) {
                return snapshot.substring(k)
            }
            k--
        }
        // No overlap; if snapshot is entirely new segment, append whole snapshot
        return snapshot
    }

    // Streaming helpers removed; using snapshot logs in refresh()
    companion object {
        const val EXTRA_ENDPOINT_ID = "endpoint_id"
        const val EXTRA_CONTAINER_ID = "container_id"
        const val EXTRA_AGENT_TARGET = "agent_target"
    }
}

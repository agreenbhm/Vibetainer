package com.agreenbhm.vibetainer.ui

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.agreenbhm.vibetainer.R
import com.agreenbhm.vibetainer.network.PortainerApi
import com.agreenbhm.vibetainer.network.SystemDf
import com.agreenbhm.vibetainer.util.Prefs
import com.google.android.material.appbar.MaterialToolbar
import com.agreenbhm.vibetainer.ui.chart.ValueMarker
import com.github.mikephil.charting.listener.OnChartValueSelectedListener
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.agreenbhm.vibetainer.ui.adapters.ContainerAdapter
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay

class NodeDetailActivity : AppCompatActivity() {
    // No overflow menu on this screen
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_node_detail)

        val nodeId = intent.getStringExtra("node_id") ?: return finish()
        val endpointId = intent.getIntExtra("endpoint_id", 1)

        // Title/details removed from layout for cleaner header
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_detail)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        com.agreenbhm.vibetainer.ui.EdgeToEdge.apply(this, toolbar, findViewById(R.id.swipe_node_detail))
        val chipCpu = findViewById<Chip>(R.id.chip_cpu)
        val chipMem = findViewById<Chip>(R.id.chip_mem)
        val chipRunning = findViewById<Chip>(R.id.chip_running)
        val chipStopped = findViewById<Chip>(R.id.chip_stopped)
        val textCpu = findViewById<TextView>(R.id.text_cpu_value)
        val textMem = findViewById<TextView>(R.id.text_mem_value)
        val cardContainers = findViewById<android.view.View>(R.id.card_containers_node)
        val cardImages = findViewById<android.view.View>(R.id.card_images_node)
        val cardVolumes = findViewById<android.view.View>(R.id.card_volumes_node)
        val textContainersCountCard = findViewById<TextView>(R.id.text_containers_count_card)
        val textImagesCount = findViewById<TextView>(R.id.text_images_count)
        val textVolumesCount = findViewById<TextView>(R.id.text_volumes_count)
        // bottom containers list removed
        val swipe = findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout>(R.id.swipe_node_detail)
        var agentTargetValue: String? = null
        val liveChart = findViewById<LineChart>(R.id.chart_live)
        // bottom list removed

        val prefs = Prefs(this)
        val api = PortainerApi.create(this, prefs.baseUrl(), prefs.token())
        var hostMemHint: Long = 0L

        fun reload() {
            swipe.isRefreshing = true
            lifecycleScope.launch {
                try {
                val node = api.getNode(endpointId, nodeId)
                val nodeTitle = node.Description?.Hostname ?: node.ID
                toolbar.title = nodeTitle
                toolbar.subtitle = Prefs(this@NodeDetailActivity).endpointName()
                // Use node hostname (simple text) for agent targeting; fallback to ID
                agentTargetValue = node.Description?.Hostname ?: node.ID

                var cpuCores = (node.Description?.Resources?.NanoCPUs ?: 0L) / 1_000_000_000f
                var memGB = (node.Description?.Resources?.MemoryBytes ?: 0L) / (1024f * 1024f * 1024f)

                // Fallback to /info if node resources missing
                var normalizeCpus = if (cpuCores > 0f) cpuCores.toInt() else 1
                if (cpuCores <= 0f || memGB <= 0f) {
                    runCatching { api.info(endpointId) }.onSuccess { info ->
                        if (cpuCores <= 0f) cpuCores = (info.NCPU ?: 0).toFloat()
                        if (memGB <= 0f) memGB = (info.MemTotal ?: 0L) / (1024f * 1024f * 1024f)
                        if ((info.NCPU ?: 0) > 0) normalizeCpus = info.NCPU ?: normalizeCpus
                    }
                }
                hostMemHint = (memGB * 1024f * 1024f * 1024f).toLong()
                textCpu.text = String.format("%.0f", cpuCores)
                textMem.text = String.format("%.1f", memGB)

                // live utilization handled below with repeatOnLifecycle
                } catch (e: Exception) {
                    toolbar.title = "Error loading node"
                    // details removed
                } finally {
                    swipe.isRefreshing = false
                }
            }
        }
        // initial load
        reload()
        swipe.setOnRefreshListener { reload() }

// Container adapter moved to ui.adapters.ContainerAdapter

        // Initialize live chart with CPU and Mem datasets
        val history = Prefs(this).historyLength().coerceIn(20, 240)
        val entriesCpu = (0..history).map { Entry(it.toFloat(), 0f) }
        val entriesMem = (0..history).map { Entry(it.toFloat(), 0f) }
        val setCpu = LineDataSet(entriesCpu, "CPU %").apply {
            color = android.graphics.Color.parseColor("#FF4081")
            setDrawCircles(false)
            valueTextColor = android.graphics.Color.TRANSPARENT
        }
        val setMem = LineDataSet(entriesMem, "Mem %").apply {
            color = android.graphics.Color.parseColor("#00BFA5")
            setDrawCircles(false)
            valueTextColor = android.graphics.Color.TRANSPARENT
        }
        liveChart.data = LineData(setCpu, setMem)
        liveChart.description.isEnabled = false
        liveChart.axisRight.isEnabled = false
        liveChart.axisLeft.axisMinimum = 0f
        // Match chart text (axes/legend) to onSurface, like toolbar title
        val onSurface = MaterialColors.getColor(liveChart, com.google.android.material.R.attr.colorOnSurface)
        liveChart.axisLeft.textColor = onSurface
        liveChart.xAxis.textColor = onSurface
        liveChart.legend.textColor = onSurface
        liveChart.invalidate()
        // Show value on tap via marker
        liveChart.marker = ValueMarker(this)
        liveChart.isHighlightPerTapEnabled = true
        liveChart.setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
            override fun onValueSelected(e: Entry?, h: Highlight?) { /* marker handles display */ }
            override fun onNothingSelected() {}
        })

        // Poll live stats while visible
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Fetch host memory once for normalization
                var hostMem = runCatching { api.info(endpointId).MemTotal ?: 0L }.getOrDefault(0L)
                if (hostMem <= 0L) hostMem = hostMemHint
                if (hostMem <= 0L) hostMem = (16L * 1024L * 1024L * 1024L) // conservative fallback

                val pollInterval = Prefs(this@NodeDetailActivity).pollIntervalMs().coerceIn(1000L, 30000L)
                var axisMaxSeen = 10f
                while (true) {
                    try {
                        // All containers for listing; running subset for CPU aggregation
                        val containersAll = api.listContainers(endpointId, true, agentTargetValue)
                        val filteredAll = containersAll.filter { c ->
                            val nodeLabel = c.Labels?.get("com.docker.swarm.node.id")
                            nodeLabel == nodeId
                        }
                        // removed bottom list submit

                        val runningCount = filteredAll.count { (it.State ?: "").equals("running", ignoreCase = true) }
                        val totalCount = filteredAll.size
                        val stoppedCount = totalCount - runningCount
                        chipRunning.text = "Running %d".format(runningCount)
                        chipStopped.text = "Stopped %d".format(stoppedCount)
                        textContainersCountCard.text = totalCount.toString()
                        val images = runCatching { api.listImages(endpointId, agentTargetValue) }.getOrDefault(emptyList())
                        textImagesCount.text = images.size.toString()
                        val volumesResp = runCatching { api.listVolumes(endpointId, agentTargetValue) }.getOrNull()
                        textVolumesCount.text = ((volumesResp?.Volumes) ?: emptyList()).size.toString()

                        val running = filteredAll.filter { (it.State ?: "").equals("running", ignoreCase = true) }
                        val stats = running.map { c ->
                            // Include agent target so stats are routed to the correct node
                            async { runCatching { api.containerStats(endpointId, c.Id, false, agentTargetValue) }.getOrNull() }
                        }.awaitAll().filterNotNull()

                        fun cpuPercent(s: com.agreenbhm.vibetainer.network.ContainerStats): Float {
                            val cpu = s.cpu_stats
                            val pre = s.precpu_stats
                            if (cpu == null || pre == null) return 0f
                            val cpuDelta = (cpu.cpu_usage?.total_usage ?: 0L) - (pre.cpu_usage?.total_usage ?: 0L)
                            val sysDelta = (cpu.system_cpu_usage ?: 0L) - (pre.system_cpu_usage ?: 0L)
                            val online = (cpu.online_cpus ?: cpu.cpu_usage?.percpu_usage?.size ?: 1)
                            if (cpuDelta <= 0L || sysDelta <= 0L || online <= 0) return 0f
                            return (cpuDelta.toFloat() / sysDelta.toFloat()) * online * 100f
                        }

                        val totalCpu = stats.sumOf { cpuPercent(it).toDouble() }.toFloat().coerceIn(0f, 100f)

                        val totalMemUsage = stats.sumOf { (it.memory_stats?.usage ?: 0L).toDouble() }.toFloat()
                        val memPct = if (hostMem > 0L) ((totalMemUsage / hostMem.toFloat()) * 100f).coerceIn(0f, 100f) else 0f

                        chipCpu.text = String.format("CPU %d%%", totalCpu.toInt())
                        chipMem.text = String.format("Mem %d%%", memPct.toInt())

                        // Shift left and append new points
                        for (i in 0 until setCpu.entryCount - 1) {
                            val e = setCpu.getEntryForIndex(i + 1)
                            setCpu.getEntryForIndex(i).y = e.y
                        }
                        setCpu.getEntryForIndex(setCpu.entryCount - 1).y = totalCpu
                        for (i in 0 until setMem.entryCount - 1) {
                            val e = setMem.getEntryForIndex(i + 1)
                            setMem.getEntryForIndex(i).y = e.y
                        }
                        setMem.getEntryForIndex(setMem.entryCount - 1).y = memPct

                        val maxInData = kotlin.math.max(
                            (0 until setCpu.entryCount).maxOf { setCpu.getEntryForIndex(it).y },
                            (0 until setMem.entryCount).maxOf { setMem.getEntryForIndex(it).y }
                        )
                        val targetMax = kotlin.math.max(10f, maxInData * 1.2f)
                        if (targetMax > axisMaxSeen) {
                            axisMaxSeen = targetMax
                            liveChart.axisLeft.axisMaximum = axisMaxSeen
                        }
                        liveChart.data.notifyDataChanged()
                        liveChart.notifyDataSetChanged()
                        liveChart.invalidate()
                    } catch (_: Exception) {
                        // ignore
                    }
                    delay(pollInterval)
                }
            }
        }

        // Clickable cards to navigate to node item lists
        cardContainers.setOnClickListener {
            val i = android.content.Intent(this, NodeContainersActivity::class.java)
            i.putExtra("endpoint_id", endpointId)
            i.putExtra("agent_target", agentTargetValue)
            i.putExtra("node_id", nodeId)
            startActivity(i)
        }
        cardImages.setOnClickListener {
            val i = android.content.Intent(this, NodeImagesActivity::class.java)
            i.putExtra("endpoint_id", endpointId)
            i.putExtra("agent_target", agentTargetValue)
            startActivity(i)
        }
        cardVolumes.setOnClickListener {
            val i = android.content.Intent(this, NodeVolumesActivity::class.java)
            i.putExtra("endpoint_id", endpointId)
            i.putExtra("agent_target", agentTargetValue)
            startActivity(i)
        }

        // Chip filters for running/stopped containers
        chipRunning.setOnClickListener {
            val i = android.content.Intent(this, NodeContainersActivity::class.java)
            i.putExtra("endpoint_id", endpointId)
            i.putExtra("agent_target", agentTargetValue)
            i.putExtra("node_id", nodeId)
            i.putExtra("state_filter", "running")
            startActivity(i)
        }
        chipStopped.setOnClickListener {
            val i = android.content.Intent(this, NodeContainersActivity::class.java)
            i.putExtra("endpoint_id", endpointId)
            i.putExtra("agent_target", agentTargetValue)
            i.putExtra("node_id", nodeId)
            i.putExtra("state_filter", "stopped")
            startActivity(i)
        }
    }

    companion object {
        const val EXTRA_NODE_ID = "node_id"
        const val EXTRA_ENDPOINT_ID = "endpoint_id"
    }
}

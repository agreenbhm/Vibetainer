package com.example.portainerapp.ui

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.portainerapp.R
import com.example.portainerapp.network.PortainerApi
import com.example.portainerapp.network.SystemDf
import com.example.portainerapp.util.Prefs
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.google.android.material.appbar.MaterialToolbar
import com.example.portainerapp.ui.chart.ValueMarker
import com.google.android.material.chip.Chip
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.portainerapp.ui.adapters.ContainerAdapter
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay

class NodeDetailActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_node_detail)

        val nodeId = intent.getStringExtra(EXTRA_NODE_ID) ?: return finish()
        val endpointId = intent.getIntExtra(EXTRA_ENDPOINT_ID, 1)

        val title = findViewById<TextView>(R.id.text_node_title)
        val details = findViewById<TextView>(R.id.text_node_details)
        val chart = findViewById<BarChart>(R.id.chart_placeholder)
        val storageChart = findViewById<com.github.mikephil.charting.charts.PieChart>(R.id.chart_storage)
        val liveChart = findViewById<LineChart>(R.id.chart_live)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_detail)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        val chipCpu = findViewById<Chip>(R.id.chip_cpu)
        val chipMem = findViewById<Chip>(R.id.chip_mem)
        val chipTotal = findViewById<Chip>(R.id.chip_total)
        val chipRunning = findViewById<Chip>(R.id.chip_running)
        val chipStopped = findViewById<Chip>(R.id.chip_stopped)
        val containersRv = findViewById<RecyclerView>(R.id.recycler_containers)
        containersRv.layoutManager = LinearLayoutManager(this)
        var agentTargetValue: String? = null
        val containerAdapter = ContainerAdapter { container ->
            val intent = android.content.Intent(this, ContainerDetailActivity::class.java)
            intent.putExtra(ContainerDetailActivity.EXTRA_ENDPOINT_ID, endpointId)
            intent.putExtra(ContainerDetailActivity.EXTRA_CONTAINER_ID, container.Id)
            // Use the node's simple hostname for agent target, if available
            intent.putExtra(
                ContainerDetailActivity.EXTRA_AGENT_TARGET,
                agentTargetValue ?: title.text?.toString()
            )
            startActivity(intent)
        }
        containersRv.adapter = containerAdapter

        val prefs = Prefs(this)
        val api = PortainerApi.create(this, prefs.baseUrl(), prefs.token())
        var hostMemHint: Long = 0L

        lifecycleScope.launch {
            try {
                val node = api.getNode(endpointId, nodeId)
                val nodeTitle = node.Description?.Hostname ?: node.ID
                title.text = nodeTitle
                toolbar.title = nodeTitle
                toolbar.subtitle = Prefs(this@NodeDetailActivity).endpointName()
                details.text = "ID: ${'$'}{node.ID}\nHostname: ${'$'}{node.Description?.Hostname ?: \"n/a\"}"
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

                val entries = ArrayList<BarEntry>()
                entries.add(BarEntry(0f, cpuCores))
                entries.add(BarEntry(1f, memGB))

                val dataSet = BarDataSet(entries, "Capacity").apply {
                    setColors(intArrayOf(R.color.purple_500, R.color.teal_700), this@NodeDetailActivity)
                    valueTextColor = android.graphics.Color.WHITE
                }
                chart.data = BarData(dataSet)
                chart.description.isEnabled = false
                chart.xAxis.apply {
                    setDrawGridLines(false)
                    granularity = 1f
                    valueFormatter = com.github.mikephil.charting.formatter.IndexAxisValueFormatter(arrayOf("CPU Cores", "Memory GB"))
                }
                chart.axisLeft.axisMinimum = 0f
                chart.axisRight.isEnabled = false
                chart.invalidate()

                // live utilization handled below with repeatOnLifecycle

                // Storage usage pie from /system/df
                runCatching { api.systemDf(endpointId) }.onSuccess { df: SystemDf ->
                    val containers = (df.Containers ?: emptyList()).mapNotNull { it.SizeRootFs }.sum()
                    val images = (df.Images ?: emptyList()).mapNotNull { it.Size }.sum()
                    val volumes = (df.Volumes ?: emptyList()).mapNotNull { it.UsageData?.Size }.sum()
                    val total = (df.LayersSize ?: 0L) + containers + images + volumes
                    if (total > 0L) {
                        val toGB = 1024f * 1024f * 1024f
                        val entries = listOf(
                            com.github.mikephil.charting.data.PieEntry(images / toGB, "Images"),
                            com.github.mikephil.charting.data.PieEntry(containers / toGB, "Containers"),
                            com.github.mikephil.charting.data.PieEntry(volumes / toGB, "Volumes")
                        )
                        val set = com.github.mikephil.charting.data.PieDataSet(entries, "Storage GB").apply {
                            setColors(intArrayOf(R.color.purple_500, R.color.teal_700, android.R.color.holo_orange_light), this@NodeDetailActivity)
                            valueTextColor = android.graphics.Color.WHITE
                        }
                        storageChart.data = com.github.mikephil.charting.data.PieData(set)
                        storageChart.description.isEnabled = false
                        storageChart.centerText = "Storage"
                        storageChart.invalidate()
                    }
                }
            } catch (e: Exception) {
                title.text = "Error loading node"
                details.text = e.message
            }
}

// Container adapter moved to ui.adapters.ContainerAdapter

        // Live metrics chart with CPU + Memory series
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
        liveChart.invalidate()

        // Markers on charts to show values inline
        val marker = ValueMarker(this)
        liveChart.marker = marker
        chart.marker = marker
        storageChart.marker = marker
        liveChart.setExtraTopOffset(16f)
        liveChart.axisLeft.spaceTop = 20f

        // Initialize axis max from persisted value (per endpoint + node)
        val persistedMax = Prefs(this).getAxisMax(endpointId, nodeId)
        if (persistedMax > 0f) liveChart.axisLeft.axisMaximum = persistedMax

        // Always show both series
        setMem.setVisible(true)

        // Poll live stats while visible
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Fetch host memory once for normalization
                var hostMem = runCatching { api.info(endpointId).MemTotal ?: 0L }.getOrDefault(0L)
                if (hostMem <= 0L) hostMem = hostMemHint
                if (hostMem <= 0L) hostMem = (16L * 1024L * 1024L * 1024L) // conservative fallback

                var axisMaxSeen = if (persistedMax > 0f) persistedMax else 10f
                val pollInterval = Prefs(this@NodeDetailActivity).pollIntervalMs().coerceIn(1000L, 30000L)
                while (true) {
                    try {
                        // All containers for listing; running subset for CPU aggregation
                        val containersAll = api.listContainers(endpointId, true, agentTargetValue)
                        val filteredAll = containersAll.filter { c ->
                            val nodeLabel = c.Labels?.get("com.docker.swarm.node.id")
                            nodeLabel == nodeId
                        }
                        containerAdapter.submit(filteredAll)

                        val runningCount = filteredAll.count { (it.State ?: "").equals("running", ignoreCase = true) }
                        val totalCount = filteredAll.size
                        val stoppedCount = totalCount - runningCount
                        chipTotal.text = "Total ${'$'}totalCount"
                        chipRunning.text = "Running ${'$'}runningCount"
                        chipStopped.text = "Stopped ${'$'}stoppedCount"

                        val containers = filteredAll.filter { (it.State ?: "").equals("running", ignoreCase = true) }
                        val running = containers.filter { (it.State ?: "").equals("running", ignoreCase = true) }
                        val stats = running.map { c ->
                            // Include agent target so stats are routed to the correct node
                            async { runCatching { api.containerStats(endpointId, c.Id, false, agentTargetValue) }.getOrNull() }
                        }.awaitAll().filterNotNull()

                        fun cpuPercent(s: com.example.portainerapp.network.ContainerStats): Float {
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

                        // shift and append CPU
                        for (i in 0 until setCpu.entryCount - 1) {
                            val e = setCpu.getEntryForIndex(i + 1)
                            setCpu.getEntryForIndex(i).y = e.y
                        }
                        setCpu.getEntryForIndex(setCpu.entryCount - 1).y = totalCpu

                        // shift and append Mem
                        for (i in 0 until setMem.entryCount - 1) {
                            val e = setMem.getEntryForIndex(i + 1)
                            setMem.getEntryForIndex(i).y = e.y
                        }
                        setMem.getEntryForIndex(setMem.entryCount - 1).y = memPct

                        chipCpu.text = "CPU ${'$'}{'%d'.format(totalCpu.toInt())}%"
                        chipMem.text = "Mem ${'$'}{'%d'.format(memPct.toInt())}%"

                        // Dynamic y-axis scaling based on entire dataset; never shrink
                        val maxInData = kotlin.math.max(
                            (0 until setCpu.entryCount).maxOf { setCpu.getEntryForIndex(it).y },
                            (0 until setMem.entryCount).maxOf { setMem.getEntryForIndex(it).y }
                        )
                        val targetMax = kotlin.math.max(10f, maxInData * 1.2f)
                        if (targetMax > axisMaxSeen) {
                            axisMaxSeen = targetMax
                            liveChart.axisLeft.axisMaximum = axisMaxSeen
                            Prefs(this@NodeDetailActivity).setAxisMax(endpointId, nodeId, axisMaxSeen)
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
    }

    companion object {
        const val EXTRA_NODE_ID = "node_id"
        const val EXTRA_ENDPOINT_ID = "endpoint_id"
    }
}

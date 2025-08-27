package com.example.portainerapp.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.chip.Chip
import com.example.portainerapp.R
import com.example.portainerapp.network.PortainerApi
import com.example.portainerapp.ui.adapters.ContainerAdapter
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class NodeContainersActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_containers_list)

        val endpointId = intent.getIntExtra("endpoint_id", -1)
        val agentTarget = intent.getStringExtra("agent_target")
        val nodeId = intent.getStringExtra("node_id")
        val stateFilter = intent.getStringExtra("state_filter") // "running", "stopped", or null

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_list)
        toolbar.title = "Containers"
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        com.example.portainerapp.ui.EdgeToEdge.apply(this, toolbar, findViewById(R.id.swipe_list))

        val recycler: RecyclerView = findViewById(R.id.recycler_list)
        val swipe: SwipeRefreshLayout = findViewById(R.id.swipe_list)
        val chipAll: Chip = findViewById(R.id.chip_filter_all)
        val chipRunning: Chip = findViewById(R.id.chip_filter_running)
        val chipStopped: Chip = findViewById(R.id.chip_filter_stopped)
        recycler.layoutManager = LinearLayoutManager(this)

        val prefs = com.example.portainerapp.util.Prefs(this)
        val api = PortainerApi.create(this, prefs.baseUrl(), prefs.token())

        val adapter = ContainerAdapter { c ->
            val i = Intent(this, ContainerDetailActivity::class.java)
            i.putExtra(ContainerDetailActivity.EXTRA_ENDPOINT_ID, endpointId)
            i.putExtra(ContainerDetailActivity.EXTRA_CONTAINER_ID, c.Id)
            i.putExtra(ContainerDetailActivity.EXTRA_AGENT_TARGET, agentTarget)
            startActivity(i)
        }
        recycler.adapter = adapter

        var baseForNode: List<com.example.portainerapp.network.ContainerSummary> = emptyList()

        fun applyFilter() {
            val sel = when {
                chipRunning.isChecked -> "running"
                chipStopped.isChecked -> "stopped"
                else -> null
            }
            var filtered = baseForNode
            filtered = when (sel) {
                "running" -> filtered.filter { (it.State ?: "").equals("running", ignoreCase = true) }
                "stopped" -> filtered.filter { !(it.State ?: "").equals("running", ignoreCase = true) }
                else -> filtered
            }
            adapter.submit(filtered)
        }

        fun load() {
            swipe.isRefreshing = true
            lifecycleScope.launch {
                try {
                    val all = api.listContainers(endpointId, true, agentTarget)
                    baseForNode = all.filter { it.Labels?.get("com.docker.swarm.node.id") == nodeId }
                    applyFilter()
                } catch (e: Exception) {
                    Snackbar.make(recycler, "Failed: ${e.message}", Snackbar.LENGTH_LONG).show()
                } finally {
                    swipe.isRefreshing = false
                }
            }
        }
        swipe.setOnRefreshListener { load() }
        // Initialize chips from incoming filter
        when (stateFilter?.lowercase()) {
            "running" -> chipRunning.isChecked = true
            "stopped" -> chipStopped.isChecked = true
            else -> chipAll.isChecked = true
        }
        chipAll.setOnClickListener { applyFilter() }
        chipRunning.setOnClickListener { applyFilter() }
        chipStopped.setOnClickListener { applyFilter() }
        load()
    }
}

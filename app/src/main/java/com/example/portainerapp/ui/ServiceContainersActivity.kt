package com.example.portainerapp.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.portainerapp.R
import com.example.portainerapp.network.PortainerApi
import com.example.portainerapp.ui.adapters.ContainerAdapter
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

class ServiceContainersActivity : AppCompatActivity() {
    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.menu_service_detail, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_update_service -> { showUpdateDialog(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_containers_list)

        val serviceId = intent.getStringExtra(EXTRA_SERVICE_ID)
        val serviceName = intent.getStringExtra(EXTRA_SERVICE_NAME) ?: "Service"
        val stateFilterInitial = intent.getStringExtra(EXTRA_STATE_FILTER)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_list)
        toolbar.title = serviceName
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
        val endpointId = prefs.endpointId()

        var nodeHostById: Map<String, String> = emptyMap()
        val adapter = ContainerAdapter({ c ->
            val i = Intent(this, ContainerDetailActivity::class.java)
            i.putExtra(ContainerDetailActivity.EXTRA_ENDPOINT_ID, endpointId)
            i.putExtra(ContainerDetailActivity.EXTRA_CONTAINER_ID, c.Id)
            startActivity(i)
        }) { c ->
            val nodeId = c.Labels?.get("com.docker.swarm.node.id")
            val host = if (!nodeId.isNullOrBlank()) nodeHostById[nodeId].orEmpty() else ""
            val rawImage = c.Image.orEmpty()
            val image = when {
                rawImage.isBlank() -> ""
                rawImage.startsWith("sha256:") -> ""
                rawImage.matches(Regex("[a-f0-9]{64}")) -> ""
                else -> rawImage.substringBefore('@')
            }
            when {
                host.isNotBlank() && image.isNotBlank() -> "$host • $image"
                host.isNotBlank() -> host
                image.isNotBlank() -> image
                else -> ""
            }
        }
        recycler.adapter = adapter

        var allContainers: List<com.example.portainerapp.network.ContainerSummary> = emptyList()

        fun applyFilter() {
            val sel = when {
                chipRunning.isChecked -> "running"
                chipStopped.isChecked -> "stopped"
                else -> null
            }
            val filtered = when (sel) {
                "running" -> allContainers.filter { (it.State ?: "").equals("running", ignoreCase = true) }
                "stopped" -> allContainers.filter { !(it.State ?: "").equals("running", ignoreCase = true) }
                else -> allContainers
            }
            adapter.submit(filtered)
        }

        fun load() {
            swipe.isRefreshing = true
            lifecycleScope.launch {
                try {
                    val triple = withContext(Dispatchers.IO) {
                        val list = api.listContainers(endpointId, true, null)
                        val nodes = api.listNodes(endpointId)
                        val inspect = serviceId?.let { api.serviceInspect(endpointId, it) }
                        Triple(list, nodes, inspect)
                    }
                    val list = triple.first
                    val nodes = triple.second
                    val inspect = triple.third
                    nodeHostById = nodes.associate { it.ID to (it.Description?.Hostname ?: it.ID) }
                    allContainers = list.filter { c ->
                        val sid = c.Labels?.get("com.docker.swarm.service.id")
                        val sname = c.Labels?.get("com.docker.swarm.service.name")
                        (serviceId != null && sid == serviceId) || (serviceId == null && sname == serviceName)
                    }
                    // Try to determine stack name
                    val stackNs = inspect?.Spec?.Labels?.get("com.docker.stack.namespace")
                        ?: allContainers.firstOrNull()?.Labels?.get("com.docker.stack.namespace")
                    if (!stackNs.isNullOrBlank()) {
                        toolbar.subtitle = "Stack: $stackNs"
                    } else {
                        toolbar.subtitle = null
                    }
                    applyFilter()
                } catch (e: Exception) {
                    Snackbar.make(recycler, "Failed: ${e.message}", Snackbar.LENGTH_LONG).show()
                } finally {
                    swipe.isRefreshing = false
                }
            }
        }

        // Ensure one selection at all times
        val chipGroup: com.google.android.material.chip.ChipGroup = findViewById(R.id.chip_group_state)
        var changing = false
        chipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            if (changing) return@setOnCheckedStateChangeListener
            if (checkedIds.isEmpty()) { changing = true; chipAll.isChecked = true; changing = false }
            applyFilter()
        }

        when (stateFilterInitial?.lowercase()) {
            "running" -> chipRunning.isChecked = true
            "stopped" -> chipStopped.isChecked = true
            else -> chipAll.isChecked = true
        }
        chipAll.setOnClickListener { applyFilter() }
        chipRunning.setOnClickListener { applyFilter() }
        chipStopped.setOnClickListener { applyFilter() }
        swipe.setOnRefreshListener { load() }
        load()
    }

    private fun showUpdateDialog() {
        val serviceId = intent.getStringExtra(EXTRA_SERVICE_ID) ?: return
        val ctx = this
        val layout = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad / 2)
        }
        val check = com.google.android.material.materialswitch.MaterialSwitch(ctx).apply {
            text = "Re-pull image"
            isChecked = false
        }
        layout.addView(check)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle("Update service")
            .setView(layout)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Update") { _, _ ->
                val repull = check.isChecked
                lifecycleScope.launch {
                    val prefs = com.example.portainerapp.util.Prefs(this@ServiceContainersActivity)
                    val api = PortainerApi.create(this@ServiceContainersActivity, prefs.baseUrl(), prefs.token())
                    val endpointId = prefs.endpointId()
                    val result = runCatching {
                        val inspect = api.serviceInspect(endpointId, serviceId)
                        val version = inspect.Version?.Index ?: 0L
                        val spec = inspect.Spec ?: com.example.portainerapp.network.ServiceSpecFull(null, null, null)
                        val task = spec.TaskTemplate
                        val forceVal = if (repull) ((task?.ForceUpdate ?: 0) + 1) else (task?.ForceUpdate ?: 0)
                        val newTask = com.example.portainerapp.network.TaskTemplate(forceVal, task?.ContainerSpec)
                        val newSpec = com.example.portainerapp.network.ServiceSpecFull(spec.Name, newTask, spec.Labels)
                        api.serviceUpdate(endpointId, serviceId, version, newSpec)
                    }
                    if (result.isSuccess) {
                        com.google.android.material.snackbar.Snackbar.make(findViewById(R.id.recycler_list), "Service updated", com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show()
                        // Trigger reload
                        findViewById<SwipeRefreshLayout>(R.id.swipe_list).isRefreshing = true
                        // Call load() again
                        // Using post to ensure UI thread
                        findViewById<SwipeRefreshLayout>(R.id.swipe_list).post { 
                            // Re-run initial load by recreating activity or call load via reflection? Define as member? For simplicity, recreate.
                            recreate()
                        }
                    } else {
                        android.widget.Toast.makeText(this@ServiceContainersActivity, "Failed to update service", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    companion object {
        const val EXTRA_SERVICE_ID = "service_id"
        const val EXTRA_SERVICE_NAME = "service_name"
        const val EXTRA_STATE_FILTER = "state_filter"
    }
}

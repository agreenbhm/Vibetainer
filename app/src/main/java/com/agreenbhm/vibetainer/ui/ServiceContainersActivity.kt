package com.agreenbhm.vibetainer.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.agreenbhm.vibetainer.R
import com.agreenbhm.vibetainer.network.PortainerApi
import com.agreenbhm.vibetainer.ui.adapters.ContainerAdapter
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

class ServiceContainersActivity : AppCompatActivity() {
    private var nodeHostById: Map<String, String> = emptyMap()
    private var containersForService: List<com.agreenbhm.vibetainer.network.ContainerSummary> = emptyList()
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
        val serviceStack = intent.getStringExtra(EXTRA_STACK_NAME)
        val stateFilterInitial = intent.getStringExtra(EXTRA_STATE_FILTER)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_list)
        toolbar.title = serviceName
        if (!serviceStack.isNullOrBlank()) toolbar.subtitle = "Stack: $serviceStack"
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        com.agreenbhm.vibetainer.ui.EdgeToEdge.apply(this, toolbar, findViewById(R.id.swipe_list))

        val recycler: RecyclerView = findViewById(R.id.recycler_list)
        val swipe: SwipeRefreshLayout = findViewById(R.id.swipe_list)
        val chipAll: Chip = findViewById(R.id.chip_filter_all)
        val chipRunning: Chip = findViewById(R.id.chip_filter_running)
        val chipStopped: Chip = findViewById(R.id.chip_filter_stopped)
        recycler.layoutManager = LinearLayoutManager(this)

        val prefs = com.agreenbhm.vibetainer.util.Prefs(this)
        val api = PortainerApi.create(this, prefs.baseUrl(), prefs.token())
        val endpointId = prefs.endpointId()

        var selectionActive = false
        lateinit var adapter: ContainerAdapter
        adapter = ContainerAdapter({ c ->
            val i = Intent(this, ContainerDetailActivity::class.java)
            i.putExtra(ContainerDetailActivity.EXTRA_ENDPOINT_ID, endpointId)
            i.putExtra(ContainerDetailActivity.EXTRA_CONTAINER_ID, c.Id)
            c.Labels?.get("com.docker.swarm.service.name")?.let { i.putExtra(ContainerDetailActivity.EXTRA_SERVICE_NAME, it) }
            c.Labels?.get("com.docker.stack.namespace")?.let { i.putExtra(ContainerDetailActivity.EXTRA_STACK_NAME, it) }
            startActivity(i)
        }, { c ->
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
        }, onEnterSelection = {
            selectionActive = true
            toolbar.menu.clear()
            toolbar.inflateMenu(R.menu.menu_service_containers_selection)
            toolbar.subtitle = "Tap to select containers"
            toolbar.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_select_all_stopped -> { adapter.selectAll { (it.State ?: "").lowercase() != "running" }; true }
                    R.id.action_select_none -> { adapter.clearSelection(); selectionActive = false; toolbar.menu.clear(); toolbar.subtitle = null; true }
                    R.id.action_remove_containers -> { confirmRemoveSelected(adapter); true }
                    else -> false
                }
            }
        }, onSelectionChanged = { count ->
            toolbar.title = if (count > 0) "$count selected" else serviceName
            if (count == 0 && selectionActive) {
                selectionActive = false
                toolbar.menu.clear()
                toolbar.subtitle = null
                toolbar.title = serviceName
            }
        })
        recycler.adapter = adapter

        // Swipe-to-delete for stopped containers
        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean = false
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                val pos = vh.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return
                val item = adapter.getItem(pos)
                val state = (item.State ?: "").lowercase()
                if (state == "running") {
                    com.google.android.material.snackbar.Snackbar.make(recycler, "Can't remove a running container", com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show()
                    adapter.notifyItemUnswiped(pos)
                    return
                }
                // Confirm removal
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this@ServiceContainersActivity)
                    .setTitle("Remove container?")
                    .setMessage("This will remove the stopped container.")
                    .setNegativeButton("Cancel") { _, _ -> adapter.notifyItemUnswiped(pos) }
                    .setPositiveButton("Remove") { _, _ ->
                        lifecycleScope.launch {
                            val prefs = com.agreenbhm.vibetainer.util.Prefs(this@ServiceContainersActivity)
                            val api = PortainerApi.create(this@ServiceContainersActivity, prefs.baseUrl(), prefs.token())
                            val endpointId = prefs.endpointId()
                            val nodeId = item.Labels?.get("com.docker.swarm.node.id")
                            val agentTarget = nodeId?.let { nodeHostById[it] }
                            val resp = runCatching { api.containerRemove(endpointId, item.Id, force = 0, v = 1, agentTarget = agentTarget) }.getOrNull()
                            if (resp != null && resp.isSuccessful) {
                                com.google.android.material.snackbar.Snackbar.make(recycler, "Removed", com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show()
                                // Reload to reflect changes
                                findViewById<SwipeRefreshLayout>(R.id.swipe_list).isRefreshing = true
                                recreate()
                            } else {
                                com.google.android.material.snackbar.Snackbar.make(recycler, "Failed to remove", com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show()
                                adapter.notifyItemUnswiped(pos)
                            }
                        }
                    }
                    .setOnDismissListener { adapter.notifyItemUnswiped(pos) }
                    .show()
            }
        })
        touchHelper.attachToRecyclerView(recycler)

        fun displayName(c: com.agreenbhm.vibetainer.network.ContainerSummary): String =
            c.Names?.firstOrNull() ?: c.Id.take(12)

        fun applyFilter() {
            val sel = when {
                chipRunning.isChecked -> "running"
                chipStopped.isChecked -> "stopped"
                else -> null
            }
            val base = containersForService
            val filtered = when (sel) {
                "running" -> base.filter { (it.State ?: "").equals("running", ignoreCase = true) }
                "stopped" -> base.filter { !(it.State ?: "").equals("running", ignoreCase = true) }
                else -> base
            }.sortedBy { displayName(it).lowercase() }
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
                    containersForService = list.filter { c ->
                        val sid = c.Labels?.get("com.docker.swarm.service.id")
                        val sname = c.Labels?.get("com.docker.swarm.service.name")
                        (serviceId != null && sid == serviceId) || (serviceId == null && sname == serviceName)
                    }
                    // Try to determine stack name
                    val stackNs = inspect?.Spec?.Labels?.get("com.docker.stack.namespace")
                        ?: containersForService.firstOrNull()?.Labels?.get("com.docker.stack.namespace")
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

    private fun confirmRemoveSelected(adapter: ContainerAdapter) {
        val ids = adapter.selectedIds()
        if (ids.isEmpty()) return
        val ctx = this
        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle("Remove ${ids.size} container(s)?")
            .setMessage("Only stopped containers will be removed. Running ones will be skipped.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remove") { _, _ -> removeSelectedContainers(adapter) }
            .show()
    }

    private fun removeSelectedContainers(adapter: ContainerAdapter) {
        val prefs = com.agreenbhm.vibetainer.util.Prefs(this)
        val api = PortainerApi.create(this, prefs.baseUrl(), prefs.token())
        val endpointId = prefs.endpointId()
        val selected = adapter.selectedIds()
        lifecycleScope.launch {
            var removed = 0
            var skipped = 0
            for (id in selected) {
                val cont = containersForService.firstOrNull { it.Id == id }
                val agentTarget = cont?.Labels?.get("com.docker.swarm.node.id")?.let { nodeHostById[it] }
                val resp = runCatching { api.containerRemove(endpointId, id, force = 0, v = 1, agentTarget = agentTarget) }.getOrNull()
                if (resp != null && resp.isSuccessful) removed++ else skipped++
            }
            com.google.android.material.snackbar.Snackbar.make(findViewById(R.id.recycler_list), "Removed $removed; $skipped skipped", com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show()
            adapter.clearSelection()
            findViewById<SwipeRefreshLayout>(R.id.swipe_list).isRefreshing = true
            recreate()
        }
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
                    val prefs = com.agreenbhm.vibetainer.util.Prefs(this@ServiceContainersActivity)
                    val api = PortainerApi.create(this@ServiceContainersActivity, prefs.baseUrl(), prefs.token())
                    val endpointId = prefs.endpointId()
                    val result = runCatching {
                        val inspect = api.serviceInspect(endpointId, serviceId)
                        val version = inspect.Version?.Index ?: 0L
                        val spec = inspect.Spec ?: com.agreenbhm.vibetainer.network.ServiceSpecFull(null, null, null)
                        val task = spec.TaskTemplate
                        val forceVal = if (repull) ((task?.ForceUpdate ?: 0) + 1) else (task?.ForceUpdate ?: 0)
                        val newTask = com.agreenbhm.vibetainer.network.TaskTemplate(forceVal, task?.ContainerSpec)
                        val newSpec = com.agreenbhm.vibetainer.network.ServiceSpecFull(spec.Name, newTask, spec.Labels)
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
        const val EXTRA_STACK_NAME = "stack_name"
    }
}

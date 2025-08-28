package com.example.portainerapp.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.portainerapp.R
import com.example.portainerapp.network.PortainerApi
import com.example.portainerapp.ui.adapters.ContainerAdapter
import com.example.portainerapp.ui.adapters.ServiceAdapter
import com.example.portainerapp.ui.adapters.ServiceWithCounts
import com.example.portainerapp.network.PortainerService
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StackDetailActivity : AppCompatActivity() {
    private lateinit var currentStackName: String
    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.menu_stack_detail, menu)
        return true
    }
    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_view_yaml -> { fetchYamlAndOpen(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun fetchYamlAndOpen() {
        val recycler = findViewById<RecyclerView>(R.id.recycler_stack_containers)
        val prefs = com.example.portainerapp.util.Prefs(this)
        val api = PortainerApi.create(this, prefs.baseUrl(), prefs.token())
        val endpointId = prefs.endpointId()
        val stackName = currentStackName
        lifecycleScope.launch {
            try {
                val stacks = withContext(Dispatchers.IO) { api.listStacks() }
                val id = stacks.firstOrNull { (it.Name ?: "") == stackName && (it.EndpointId ?: -1) == endpointId }?.Id
                if (id == null) {
                    Snackbar.make(recycler, "Stack not found", Snackbar.LENGTH_LONG).show()
                    return@launch
                }
                val resp = withContext(Dispatchers.IO) { api.getStackFile(id) }
                val content = resp.StackFileContent ?: ""
                val i = Intent(this@StackDetailActivity, YamlViewerActivity::class.java)
                i.putExtra(YamlViewerActivity.EXTRA_TITLE, "$stackName • YAML")
                i.putExtra(YamlViewerActivity.EXTRA_CONTENT, content)
                i.putExtra(YamlViewerActivity.EXTRA_STACK_ID, id)
                i.putExtra(YamlViewerActivity.EXTRA_ENDPOINT_ID, endpointId)
                startActivity(i)
            } catch (e: Exception) {
                Snackbar.make(recycler, "Failed to load YAML: ${e.message}", Snackbar.LENGTH_LONG).show()
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stack_detail)

        val stackName = intent.getStringExtra(EXTRA_STACK_NAME) ?: return finish()
        currentStackName = stackName
        val initialFilter = intent.getStringExtra(EXTRA_STATE_FILTER)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_stack)
        toolbar.title = stackName
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        com.example.portainerapp.ui.EdgeToEdge.apply(this, toolbar, findViewById(R.id.swipe_stack))

        val swipe = findViewById<SwipeRefreshLayout>(R.id.swipe_stack)
        val chipAll: Chip = findViewById(R.id.chip_filter_all)
        val chipRunning: Chip = findViewById(R.id.chip_filter_running)
        val chipStopped: Chip = findViewById(R.id.chip_filter_stopped)
        val servicesRecycler = findViewById<RecyclerView>(R.id.recycler_stack_services)
        val recycler = findViewById<RecyclerView>(R.id.recycler_stack_containers)
        // Disable nested scrolling so the whole page scrolls as one
        servicesRecycler.isNestedScrollingEnabled = false
        recycler.isNestedScrollingEnabled = false
        recycler.layoutManager = LinearLayoutManager(this)

        val prefs = com.example.portainerapp.util.Prefs(this)
        val api = PortainerApi.create(this, prefs.baseUrl(), prefs.token())
        val endpointId = prefs.endpointId()
        var containers: List<com.example.portainerapp.network.ContainerSummary> = emptyList()

        var selectionActive = false
        lateinit var adapter: ContainerAdapter
        adapter = ContainerAdapter({ c ->
            val i = Intent(this, ContainerDetailActivity::class.java)
            i.putExtra(ContainerDetailActivity.EXTRA_ENDPOINT_ID, endpointId)
            i.putExtra(ContainerDetailActivity.EXTRA_CONTAINER_ID, c.Id)
            startActivity(i)
        }, { c ->
            c.Image?.substringBefore('@') ?: ""
        }, onEnterSelection = {
            selectionActive = true
            toolbar.menu.clear()
            toolbar.inflateMenu(R.menu.menu_service_containers_selection)
            toolbar.subtitle = "Tap to select containers"
            toolbar.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_select_all_stopped -> { adapter.selectAll { (it.State ?: "").lowercase() != "running" }; true }
                    R.id.action_select_none -> { adapter.clearSelection(); selectionActive = false; toolbar.menu.clear(); toolbar.subtitle = null; true }
                    R.id.action_remove_containers -> { confirmRemoveSelected(adapter, api, endpointId, containers); true }
                    else -> false
                }
            }
        }, onSelectionChanged = { count ->
            toolbar.title = if (count > 0) "$count selected" else stackName
            if (count == 0 && selectionActive) {
                selectionActive = false
                toolbar.menu.clear()
                toolbar.subtitle = null
                toolbar.title = stackName
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
                    Snackbar.make(recycler, "Can't remove a running container", Snackbar.LENGTH_SHORT).show()
                    adapter.notifyItemUnswiped(pos)
                    return
                }
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this@StackDetailActivity)
                    .setTitle("Remove container?")
                    .setMessage("This will remove the stopped container.")
                    .setNegativeButton("Cancel") { _, _ -> adapter.notifyItemUnswiped(pos) }
                    .setPositiveButton("Remove") { _, _ ->
                        lifecycleScope.launch {
                            val prefs = com.example.portainerapp.util.Prefs(this@StackDetailActivity)
                            val api2 = PortainerApi.create(this@StackDetailActivity, prefs.baseUrl(), prefs.token())
                            val endpointId2 = prefs.endpointId()
                            val nodes = withContext(Dispatchers.IO) { api2.listNodes(endpointId2) }
                            val nodeMap = nodes.associate { it.ID to (it.Description?.Hostname ?: it.ID) }
                            val nodeId = item.Labels?.get("com.docker.swarm.node.id")
                            val agentTarget = nodeId?.let { nodeMap[it] }
                            val resp = runCatching { api2.containerRemove(endpointId2, item.Id, force = 0, v = 1, agentTarget = agentTarget) }.getOrNull()
                            if (resp != null && resp.isSuccessful) {
                                Snackbar.make(recycler, "Removed", Snackbar.LENGTH_SHORT).show()
                                swipe.isRefreshing = true
                                recreate()
                            } else {
                                Snackbar.make(recycler, "Failed to remove", Snackbar.LENGTH_LONG).show()
                                adapter.notifyItemUnswiped(pos)
                            }
                        }
                    }
                    .setOnDismissListener { adapter.notifyItemUnswiped(pos) }
                    .show()
            }
        })
        touchHelper.attachToRecyclerView(recycler)

        val servicesAdapter = ServiceAdapter(
            onOpen = { svc ->
                val i = Intent(this, ServiceContainersActivity::class.java)
                i.putExtra(ServiceContainersActivity.EXTRA_SERVICE_ID, svc.id)
                i.putExtra(ServiceContainersActivity.EXTRA_SERVICE_NAME, svc.name)
                startActivity(i)
            },
            onOpenFiltered = { svc, state ->
                val i = Intent(this, ServiceContainersActivity::class.java)
                i.putExtra(ServiceContainersActivity.EXTRA_SERVICE_ID, svc.id)
                i.putExtra(ServiceContainersActivity.EXTRA_SERVICE_NAME, svc.name)
                i.putExtra(ServiceContainersActivity.EXTRA_STATE_FILTER, state)
                startActivity(i)
            },
            onEnterSelection = {},
            onSelectionChanged = {}
        )
        servicesRecycler.layoutManager = LinearLayoutManager(this)
        servicesRecycler.adapter = servicesAdapter

        fun applyFilter() {
            val sel = when {
                chipRunning.isChecked -> "running"
                chipStopped.isChecked -> "stopped"
                else -> null
            }
            val base = containers
            val filtered = when (sel) {
                "running" -> base.filter { (it.State ?: "").equals("running", ignoreCase = true) }
                "stopped" -> base.filter { !(it.State ?: "").equals("running", ignoreCase = true) }
                else -> base
            }.sortedBy { (it.Names?.firstOrNull() ?: it.Id.take(12)).lowercase() }
            adapter.submit(filtered)
        }

        fun buildServicesList() {
            val groups = containers.groupBy { it.Labels?.get("com.docker.swarm.service.id") to it.Labels?.get("com.docker.swarm.service.name") }
            val rows = groups.map { (key, list) ->
                val sid = key.first
                val sname = key.second ?: (sid ?: "")
                val total = list.size
                val running = list.count { (it.State ?: "").equals("running", ignoreCase = true) }
                val stopped = total - running
                ServiceWithCounts(sid, sname, total, running, stopped)
            }.sortedBy { it.name.lowercase() }
            servicesAdapter.submit(rows)
        }

        fun load() {
            swipe.isRefreshing = true
            lifecycleScope.launch {
                try {
                    val list = withContext(Dispatchers.IO) { api.listContainers(endpointId, true, null) }
                    containers = list.filter { it.Labels?.get("com.docker.stack.namespace") == stackName }
                    buildServicesList()
                    applyFilter()
                } catch (e: Exception) {
                    Snackbar.make(recycler, "Failed: ${e.message}", Snackbar.LENGTH_LONG).show()
                } finally {
                    swipe.isRefreshing = false
                }
            }
        }

        // YAML handling implemented via onOptionsItemSelected

        when (initialFilter?.lowercase()) {
            "running" -> chipRunning.isChecked = true
            "stopped" -> chipStopped.isChecked = true
            else -> chipAll.isChecked = true
        }
        chipAll.setOnClickListener { applyFilter() }
        chipRunning.setOnClickListener { applyFilter() }
        chipStopped.setOnClickListener { applyFilter() }
        findViewById<ChipGroup>(R.id.chip_group_state).setOnCheckedStateChangeListener { _, ids -> if (ids.isEmpty()) chipAll.isChecked = true; applyFilter() }
        swipe.setOnRefreshListener { load() }
        load()
    }

    private fun confirmRemoveSelected(
        adapter: com.example.portainerapp.ui.adapters.ContainerAdapter,
        api: PortainerService,
        endpointId: Int,
        currentContainers: List<com.example.portainerapp.network.ContainerSummary>
    ) {
        val ids = adapter.selectedIds()
        if (ids.isEmpty()) return
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Remove ${ids.size} container(s)?")
            .setMessage("Only stopped containers will be removed. Running ones will be skipped.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remove") { _, _ ->
                lifecycleScope.launch {
                    var removed = 0
                    var skipped = 0
                    val nodes = withContext(Dispatchers.IO) { api.listNodes(endpointId) }
                    val nodeMap = nodes.associate { it.ID to (it.Description?.Hostname ?: it.ID) }
                    ids.forEach { id ->
                        val cont = currentContainers.firstOrNull { it.Id == id }
                        val agentTarget = cont?.Labels?.get("com.docker.swarm.node.id")?.let { nodeMap[it] }
                        val resp = runCatching { api.containerRemove(endpointId, id, force = 0, v = 1, agentTarget = agentTarget) }.getOrNull()
                        if (resp != null && resp.isSuccessful) removed++ else skipped++
                    }
                    com.google.android.material.snackbar.Snackbar.make(findViewById(R.id.recycler_stack_containers), "Removed $removed; $skipped skipped", com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show()
                    adapter.clearSelection()
                    findViewById<SwipeRefreshLayout>(R.id.swipe_stack).isRefreshing = true
                    recreate()
                }
            }
            .show()
    }

    companion object {
        const val EXTRA_STACK_NAME = "stack_name"
        const val EXTRA_STATE_FILTER = "state_filter"
    }
}

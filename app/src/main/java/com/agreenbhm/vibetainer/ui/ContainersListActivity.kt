package com.agreenbhm.vibetainer.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.chip.Chip
import com.agreenbhm.vibetainer.R
import com.agreenbhm.vibetainer.network.PortainerApi
import com.agreenbhm.vibetainer.network.PortainerService
import com.agreenbhm.vibetainer.ui.adapters.ContainerAdapter
import com.agreenbhm.vibetainer.util.Prefs
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class ContainersListActivity : AppCompatActivity() {
    // No overflow menu on this screen


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_containers_list)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_list)
        toolbar.title = "Containers"
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        EdgeToEdge.apply(this, toolbar, findViewById(R.id.swipe_list))

        val recycler: RecyclerView = findViewById(R.id.recycler_list)
        val swipe: SwipeRefreshLayout = findViewById(R.id.swipe_list)
        val chipAll: Chip = findViewById(R.id.chip_filter_all)
        val chipRunning: Chip = findViewById(R.id.chip_filter_running)
        val chipStopped: Chip = findViewById(R.id.chip_filter_stopped)
        recycler.layoutManager = LinearLayoutManager(this)

        val prefs = Prefs(this)
        val api = PortainerApi.create(this, prefs.baseUrl(), prefs.token())
        val endpointId = prefs.endpointId()
        val nodeIdExtra: String? = intent.getStringExtra("node_id")
        // Set initial subtitle quickly; refine after node list loads
        toolbar.subtitle = nodeIdExtra?.let { "Loading node…" } ?: prefs.endpointName()
        var nodeHostById: Map<String, String> = emptyMap()
        var allContainers: List<com.agreenbhm.vibetainer.network.ContainerSummary> = emptyList()

        var selectionActive = false
        lateinit var adapter: ContainerAdapter
        adapter = ContainerAdapter(onClick = { c ->
            val i = Intent(this, ContainerDetailActivity::class.java)
            i.putExtra(ContainerDetailActivity.EXTRA_ENDPOINT_ID, endpointId)
            i.putExtra(ContainerDetailActivity.EXTRA_CONTAINER_ID, c.Id)
            val imageName = when {
                c.Image.orEmpty().isBlank() -> ""
                c.Image.orEmpty().startsWith("sha256:") -> ""
                c.Image.orEmpty().matches(Regex("[a-f0-9]{64}")) -> ""
                else -> c.Image.orEmpty().substringBefore('@')
            }
            i.putExtra(ContainerDetailActivity.EXTRA_IMAGE_NAME, imageName)
            c.Labels?.get("com.docker.swarm.service.name")?.let { i.putExtra(ContainerDetailActivity.EXTRA_SERVICE_NAME, it) }
            c.Labels?.get("com.docker.stack.namespace")?.let { i.putExtra(ContainerDetailActivity.EXTRA_STACK_NAME, it) }
            startActivity(i)
        }, subtitleProvider = { c ->
            // Subtitle: cleaned image:tag only (no sha digest)
            val raw = c.Image.orEmpty()
            when {
                raw.isBlank() -> ""
                raw.startsWith("sha256:") -> ""
                raw.matches(Regex("[a-f0-9]{64}")) -> ""
                else -> raw.substringBefore('@')
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
                    R.id.action_remove_containers -> { confirmRemoveSelected(adapter, api, endpointId, nodeHostById, allContainers); true }
                    else -> false
                }
            }
        }, onSelectionChanged = { count ->
            toolbar.title = if (count > 0) "$count selected" else "Containers"
            if (count == 0 && selectionActive) {
                selectionActive = false
                toolbar.menu.clear()
                toolbar.subtitle = null
                toolbar.title = "Containers"
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
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this@ContainersListActivity)
                    .setTitle("Remove container?")
                    .setMessage("This will remove the stopped container.")
                    .setNegativeButton("Cancel") { _, _ -> adapter.notifyItemUnswiped(pos) }
                    .setPositiveButton("Remove") { _, _ ->
                        lifecycleScope.launch {
                            val nodeId = item.Labels?.get("com.docker.swarm.node.id")
                            val agentTarget = nodeId?.let { nodeHostById[it] }
                            val resp = runCatching { api.containerRemove(endpointId, item.Id, force = 0, v = 1, agentTarget = agentTarget) }.getOrNull()
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

        fun displayName(c: com.agreenbhm.vibetainer.network.ContainerSummary): String =
            c.Names?.firstOrNull()?.removePrefix("/") ?: c.Id.take(12)

        fun applyFilter() {
            val sel = when {
                chipRunning.isChecked -> "running"
                chipStopped.isChecked -> "stopped"
                else -> null
            }
            val base = allContainers
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
                    val list = api.listContainers(endpointId, true, null)
                    val nodes = api.listNodes(endpointId)
                    nodeHostById = nodes.associate { it.ID to (it.Description?.Hostname ?: it.ID) }
                    // Update subtitle based on context (endpoint vs specific node)
                    toolbar.subtitle = nodeIdExtra?.let { nodeHostById[it] ?: it } ?: prefs.endpointName()
                    allContainers = list
                    applyFilter()
                } catch (e: Exception) {
                    Snackbar.make(recycler, "Failed: ${'$'}{e.message}", Snackbar.LENGTH_LONG).show()
                } finally {
                    swipe.isRefreshing = false
                }
            }
        }

        swipe.setOnRefreshListener { load() }
        val incoming = intent.getStringExtra("state_filter")?.lowercase()
        when (incoming) {
            "running" -> chipRunning.isChecked = true
            "stopped" -> chipStopped.isChecked = true
            else -> chipAll.isChecked = true
        }
        chipAll.setOnClickListener { applyFilter() }
        chipRunning.setOnClickListener { applyFilter() }
        chipStopped.setOnClickListener { applyFilter() }

        // Enforce that at least one chip is always selected; default to All
        val chipGroup: com.google.android.material.chip.ChipGroup = findViewById(R.id.chip_group_state)
        var changing = false
        chipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            if (changing) return@setOnCheckedStateChangeListener
            if (checkedIds.isEmpty()) {
                changing = true
                chipAll.isChecked = true
                changing = false
            }
            applyFilter()
        }
        load()
    }

    private fun confirmRemoveSelected(
        adapter: ContainerAdapter,
        api: PortainerService,
        endpointId: Int,
        nodeHostById: Map<String, String>,
        currentContainers: List<com.agreenbhm.vibetainer.network.ContainerSummary>
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
                    ids.forEach { id ->
                        val item = currentContainers.firstOrNull { it.Id == id }
                        val agentTarget = item?.Labels?.get("com.docker.swarm.node.id")?.let { nodeHostById[it] }
                        val resp = runCatching { api.containerRemove(endpointId, id, force = 0, v = 1, agentTarget = agentTarget) }.getOrNull()
                        if (resp != null && resp.isSuccessful) removed++ else skipped++
                    }
                    Snackbar.make(findViewById(R.id.recycler_list), "Removed $removed; $skipped skipped", Snackbar.LENGTH_LONG).show()
                    adapter.clearSelection()
                    findViewById<SwipeRefreshLayout>(R.id.swipe_list).isRefreshing = true
                    recreate()
                }
            }
            .show()
    }
}

package com.agreenbhm.vibetainer.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.agreenbhm.vibetainer.R
import com.agreenbhm.vibetainer.network.PortainerApi
import com.agreenbhm.vibetainer.util.Prefs
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

class ServicesListActivity : AppCompatActivity() {
    // No overflow menu on this screen


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list_generic)
        val prefs = Prefs(this)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_list)
        toolbar.title = "Services"
        toolbar.subtitle = prefs.endpointName()
        setSupportActionBar(toolbar)
        EdgeToEdge.apply(this, toolbar, findViewById(R.id.swipe_list))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        val recycler = findViewById<RecyclerView>(R.id.recycler_list)
        val swipe = findViewById<SwipeRefreshLayout>(R.id.swipe_list)
        recycler.layoutManager = LinearLayoutManager(this)
        var selectionActive = false

        val api = PortainerApi.create(this, prefs.baseUrl(), prefs.token())
        val endpointId = prefs.endpointId()
        var serviceNameById: Map<String, String> = emptyMap()

        lateinit var adapter: com.agreenbhm.vibetainer.ui.adapters.ServiceAdapter
        adapter = com.agreenbhm.vibetainer.ui.adapters.ServiceAdapter(
            onOpen = { svc ->
                val i = Intent(this, ServiceContainersActivity::class.java)
                i.putExtra(ServiceContainersActivity.EXTRA_SERVICE_ID, svc.id)
                i.putExtra(ServiceContainersActivity.EXTRA_SERVICE_NAME, svc.name)
                if (!svc.stackName.isNullOrBlank()) i.putExtra(ServiceContainersActivity.EXTRA_STACK_NAME, svc.stackName)
                startActivity(i)
            },
            onOpenFiltered = { svc, state ->
                val i = Intent(this, ServiceContainersActivity::class.java)
                i.putExtra(ServiceContainersActivity.EXTRA_SERVICE_ID, svc.id)
                i.putExtra(ServiceContainersActivity.EXTRA_SERVICE_NAME, svc.name)
                i.putExtra(ServiceContainersActivity.EXTRA_STATE_FILTER, state)
                if (!svc.stackName.isNullOrBlank()) i.putExtra(ServiceContainersActivity.EXTRA_STACK_NAME, svc.stackName)
                startActivity(i)
            },
            onEnterSelection = {
                selectionActive = true
                toolbar.menu.clear()
                toolbar.inflateMenu(R.menu.menu_services_selection)
                toolbar.subtitle = "Tap items to select"
                toolbar.setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        R.id.action_select_all -> { adapter.selectAll(); true }
                        R.id.action_select_none -> { adapter.clearSelection(); selectionActive = false; toolbar.menu.clear(); toolbar.subtitle = null; true }
                        R.id.action_update_services -> {
                            showUpdateDialog(adapter.selectedIds(), recycler, serviceNameById) {
                                adapter.clearSelection(); selectionActive = false; toolbar.menu.clear(); toolbar.subtitle = null; toolbar.title = "Services"
                            }
                            true
                        }
                        else -> false
                    }
                }
            },
            onSelectionChanged = { count ->
                toolbar.title = if (count > 0) "$count selected" else "Services"
                if (count == 0 && selectionActive) {
                    selectionActive = false
                    toolbar.menu.clear()
                    toolbar.subtitle = null
                    toolbar.title = "Services"
                }
            }
        )
        recycler.adapter = adapter

        // prefs/api/endpointId/serviceNameById declared above

        fun load() {
            swipe.isRefreshing = true
            lifecycleScope.launch {
                try {
                    val services = withContext(Dispatchers.IO) { api.listServices(endpointId) }
                    val containers = withContext(Dispatchers.IO) { api.listContainers(endpointId, true, null) }
                    serviceNameById = services.associate { (it.ID ?: "") to (it.Spec?.Name ?: (it.ID ?: "")) }
                    val list = services.map { s ->
                        val id = s.ID
                        val name = s.Spec?.Name ?: (id ?: "")
                        val belongs = containers.filter { c ->
                            val sid = c.Labels?.get("com.docker.swarm.service.id")
                            val sname = c.Labels?.get("com.docker.swarm.service.name")
                            (sid != null && sid == id) || (sname != null && sname == s.Spec?.Name)
                        }
                        val total = belongs.size
                        val running = belongs.count { (it.State ?: "").equals("running", ignoreCase = true) }
                        val stopped = total - running
                        val stack = belongs.firstOrNull()?.Labels?.get("com.docker.stack.namespace")
                        com.agreenbhm.vibetainer.ui.adapters.ServiceWithCounts(id, name, total, running, stopped, stack)
                    }.sortedBy { it.name.lowercase() }
                    adapter.submit(list)
                } catch (e: Exception) {
                    Snackbar.make(recycler, "Failed: ${e.message}", Snackbar.LENGTH_LONG).show()
                } finally {
                    swipe.isRefreshing = false
                }
            }
        }
        swipe.setOnRefreshListener { load() }
        load()

        // showUpdateDialog moved to a private method to avoid forward reference issues
    }
}

private fun ServicesListActivity.showUpdateDialog(
    ids: List<String>,
    anchor: android.view.View,
    nameById: Map<String, String>,
    onDone: () -> Unit
) {
    if (ids.isEmpty()) return
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
        .setTitle("Update ${ids.size} service(s)")
        .setView(layout)
        .setNegativeButton("Cancel", null)
        .setPositiveButton("Update") { _, _ ->
            val repull = check.isChecked
            lifecycleScope.launch {
                val prefs = Prefs(this@showUpdateDialog)
                val api = PortainerApi.create(this@showUpdateDialog, prefs.baseUrl(), prefs.token())
                val endpointId = prefs.endpointId()
                var ok = 0
                var fail = 0
                val failedIds = mutableListOf<String>()
                for (id in ids) {
                    runCatching {
                        val inspect = api.serviceInspect(endpointId, id)
                        val version = inspect.Version?.Index ?: 0L
                        val spec = inspect.Spec
                        if (spec != null) {
                            val task = spec.TaskTemplate
                            val forceVal = if (repull) ((task?.ForceUpdate ?: 0) + 1) else (task?.ForceUpdate ?: 0)
                            val newTask = com.agreenbhm.vibetainer.network.TaskTemplate(forceVal, task?.ContainerSpec)
                            val newSpec = com.agreenbhm.vibetainer.network.ServiceSpecFull(spec.Name, newTask)
                            api.serviceUpdate(endpointId, id, version, newSpec)
                        } else error("Missing spec")
                    }.onSuccess { ok++ }.onFailure { fail++; failedIds.add(id) }
                }
                val msg = "Updated $ok service(s)" + if (fail > 0) ", $fail failed" else ""
                com.google.android.material.snackbar.Snackbar.make(anchor, msg, com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show()
                failedIds.forEach { fid ->
                    val nm = nameById[fid] ?: fid
                    android.widget.Toast.makeText(this@showUpdateDialog, "Failed to update $nm", android.widget.Toast.LENGTH_SHORT).show()
                }
                onDone()
            }
        }
        .show()
}

class SimpleTextAdapter : RecyclerView.Adapter<TextVH>() {
    private val items = mutableListOf<Pair<String, String?>>()
    fun submit(list: List<String>) { items.clear(); items.addAll(list.map { it to null }); notifyDataSetChanged() }
    fun submitWithSubtitle(list: List<Pair<String, String?>>) { items.clear(); items.addAll(list); notifyDataSetChanged() }
    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): TextVH {
        val view = android.view.LayoutInflater.from(parent.context).inflate(R.layout.item_simple_text, parent, false)
        return TextVH(view)
    }
    override fun getItemCount(): Int = items.size
    override fun onBindViewHolder(holder: TextVH, position: Int) = holder.bind(items[position])
}

class TextVH(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
    private val title = itemView.findViewById<android.widget.TextView>(R.id.text_title)
    private val subtitle = itemView.findViewById<android.widget.TextView>(R.id.text_subtitle)
    fun bind(text: Pair<String, String?>) {
        title.text = text.first
        if (!text.second.isNullOrBlank()) {
            subtitle.visibility = android.view.View.VISIBLE
            subtitle.text = text.second
        } else {
            subtitle.visibility = android.view.View.GONE
        }
    }
}

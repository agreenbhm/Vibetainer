package com.example.portainerapp

import android.content.Intent
import android.view.Menu
import android.view.MenuItem
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.chip.Chip
import com.example.portainerapp.network.PortainerApi
import com.example.portainerapp.network.ContainerSummary
import com.example.portainerapp.network.DockerNode
import com.example.portainerapp.ui.NodeDetailActivity
import com.example.portainerapp.util.Prefs
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nodes)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        com.example.portainerapp.ui.EdgeToEdge.apply(this, toolbar, findViewById(R.id.swipe_refresh))

        val recycler = findViewById<RecyclerView>(R.id.recycler_nodes)
        recycler.layoutManager = LinearLayoutManager(this)
        val adapter = NodesAdapter { node ->
            val intent = Intent(this, com.example.portainerapp.ui.NodeDetailActivity::class.java)
            intent.putExtra("node_id", node.ID)
            intent.putExtra("endpoint_id", Prefs(this).endpointId())
            startActivity(intent)
        }
        recycler.adapter = adapter

        val prefs = Prefs(this)
        val endpointId = prefs.endpointId()
        if (endpointId <= 0) {
            startActivity(Intent(this, com.example.portainerapp.ui.EndpointListActivity::class.java))
            finish()
            return
        }
        val api = PortainerApi.create(this, prefs.baseUrl(), prefs.token())

        supportActionBar?.title = "Nodes"
        supportActionBar?.subtitle = prefs.endpointName()

        val swipe = findViewById<SwipeRefreshLayout>(R.id.swipe_refresh)
        val emptyView = findViewById<android.widget.TextView>(R.id.empty_view)
        val totalChip = findViewById<Chip>(R.id.chip_total_running)
        fun loadNodes() {
            swipe.isRefreshing = true
            lifecycleScope.launch {
                try {
                    val nodes = api.listNodes(endpointId)
                    val sorted = nodes.sortedBy { (it.Description?.Hostname ?: it.ID).lowercase() }
                    adapter.submit(sorted)
                    emptyView.visibility = if (nodes.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
                    // After nodes are loaded, fetch running containers and map to nodes (Swarm label)
                    runCatching {
                        val containers = api.listContainers(endpointId, true)
                        val running = containers.filter { (it.State ?: "").equals("running", ignoreCase = true) }
                        val counts = mutableMapOf<String, Int>()
                        running.forEach { c: ContainerSummary ->
                            val nodeId = c.Labels?.get("com.docker.swarm.node.id")
                            if (nodeId != null) counts[nodeId] = (counts[nodeId] ?: 0) + 1
                        }
                        adapter.submitRunningCounts(counts)
                        val total = running.size
                        val sumCounts = counts.values.sum()
                        if ((counts.isEmpty() || sumCounts == 0) && Prefs(this@MainActivity).showHeaderTotal()) {
                            totalChip.text = "$total running"
                            totalChip.visibility = if (total > 0) android.view.View.VISIBLE else android.view.View.GONE
                        } else {
                            totalChip.visibility = android.view.View.GONE
                        }
                    }
                } catch (_: Exception) {
                    Snackbar.make(recycler, "Failed to load nodes", Snackbar.LENGTH_LONG)
                        .setAction("Retry") { loadNodes() }
                        .setAction("Settings") {
                            startActivity(Intent(this@MainActivity, com.example.portainerapp.ui.EndpointListActivity::class.java))
                        }
                        .show()
                } finally {
                    swipe.isRefreshing = false
                }
            }
        }

        swipe.setOnRefreshListener { loadNodes() }
        loadNodes()
    }

    // No overflow menu on this screen
}

class NodesAdapter(private val onClick: (DockerNode) -> Unit) : RecyclerView.Adapter<NodeVH>() {
    private val items = mutableListOf<DockerNode>()
    private val runningCounts = mutableMapOf<String, Int>()
    fun submit(list: List<DockerNode>) { items.clear(); items.addAll(list); notifyDataSetChanged() }
    fun submitRunningCounts(map: Map<String, Int>) { runningCounts.clear(); runningCounts.putAll(map); notifyDataSetChanged() }
    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): NodeVH {
        val view = android.view.LayoutInflater.from(parent.context).inflate(R.layout.item_node, parent, false)
        return NodeVH(view, onClick)
    }
    override fun getItemCount(): Int = items.size
    override fun onBindViewHolder(holder: NodeVH, position: Int) = holder.bind(items[position], runningCounts[items[position].ID] ?: 0)
}

class NodeVH(itemView: android.view.View, private val onClick: (DockerNode) -> Unit) : RecyclerView.ViewHolder(itemView) {
    private val title = itemView.findViewById<android.widget.TextView>(R.id.text_node)
    private val chip = itemView.findViewById<com.google.android.material.chip.Chip>(R.id.chip_status)
    fun bind(item: DockerNode, running: Int) {
        title.text = item.Description?.Hostname ?: item.ID
        chip.text = "$running running"
        itemView.setOnClickListener { onClick(item) }
    }
}

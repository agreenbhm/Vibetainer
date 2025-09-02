package com.agreenbhm.vibetainer.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.agreenbhm.vibetainer.R
import com.agreenbhm.vibetainer.network.PortainerApi
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.view.Menu
import android.view.MenuItem
import android.view.LayoutInflater
import android.view.View
import android.widget.ProgressBar
import com.agreenbhm.vibetainer.network.VolumePruneRequest
import com.agreenbhm.vibetainer.network.VolumePruneResponse
import kotlinx.coroutines.launch

class NodeVolumesActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list_generic)

        val endpointId = intent.getIntExtra("endpoint_id", -1)
        val agentTarget = intent.getStringExtra("agent_target")

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_list)
        toolbar.title = "Volumes"
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        com.agreenbhm.vibetainer.ui.EdgeToEdge.apply(this, toolbar, findViewById(R.id.swipe_list))

        val recycler = findViewById<RecyclerView>(R.id.recycler_list)
        val swipe = findViewById<SwipeRefreshLayout>(R.id.swipe_list)
        recycler.layoutManager = LinearLayoutManager(this)
        val adapter = SimpleTextAdapter()
        recycler.adapter = adapter

        val prefs = com.agreenbhm.vibetainer.util.Prefs(this)
        val api = PortainerApi.create(this, prefs.baseUrl(), prefs.token())

        fun load() {
            swipe.isRefreshing = true
            lifecycleScope.launch {
                try {
                    val resp = api.listVolumes(endpointId, agentTarget)
                    adapter.submit((resp.Volumes ?: emptyList()).map { it.Name ?: "<none>" }.sortedBy { it.lowercase() })
                } catch (e: Exception) {
                    Snackbar.make(recycler, "Failed: ${e.message}", Snackbar.LENGTH_LONG).show()
                } finally {
                    swipe.isRefreshing = false
                }
            }
        }
        swipe.setOnRefreshListener { load() }
        load()

        // Menu handled via onCreateOptionsMenu / onOptionsItemSelected
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_node_volumes, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_prune_volumes -> {
                confirmAndPrune()
                return true
            }
            android.R.id.home -> {
                finish()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun confirmAndPrune() {
        val recycler = findViewById<RecyclerView>(R.id.recycler_list)
        val prefs = com.agreenbhm.vibetainer.util.Prefs(this)
        val api = PortainerApi.create(this, prefs.baseUrl(), prefs.token())
        val endpointId = intent.getIntExtra("endpoint_id", -1)
        val agentTarget = intent.getStringExtra("agent_target")

        MaterialAlertDialogBuilder(this)
            .setTitle("Prune unused volumes")
            .setMessage("This will remove unused Docker volumes on the endpoint. This action cannot be undone. Continue?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Prune") { _, _ ->
                val progressBar = ProgressBar(this)
                val dlg = MaterialAlertDialogBuilder(this)
                    .setTitle("Pruning volumes")
                    .setView(progressBar)
                    .setCancelable(false)
                    .create()
                dlg.show()

                lifecycleScope.launch {
                    try {
                        val req = VolumePruneRequest(mapOf("dangling" to listOf("true")))
                        val resp: VolumePruneResponse = api.pruneVolumes(endpointId, req, agentTarget)
                        val deleted = resp.VolumesDeleted?.size ?: 0
                        val reclaimed = resp.SpaceReclaimed ?: 0L
                        dlg.dismiss()
                        Snackbar.make(recycler, "Pruned $deleted volumes, reclaimed ${formatBytes(reclaimed)}", Snackbar.LENGTH_LONG).show()
                        // Refresh list
                        findViewById<SwipeRefreshLayout>(R.id.swipe_list).isRefreshing = true
                        try {
                            val newResp = api.listVolumes(endpointId, agentTarget)
                            val adapter = findViewById<RecyclerView>(R.id.recycler_list).adapter as? SimpleTextAdapter
                            adapter?.submit((newResp.Volumes ?: emptyList()).map { it.Name ?: "<none>" }.sortedBy { it.lowercase() })
                        } catch (_: Exception) { }
                        findViewById<SwipeRefreshLayout>(R.id.swipe_list).isRefreshing = false
                    } catch (e: Exception) {
                        dlg.dismiss()
                        Snackbar.make(recycler, "Prune failed: ${e.message}", Snackbar.LENGTH_LONG).show()
                    }
                }
            }
            .show()
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var b = bytes.toDouble()
        var idx = 0
        while (b >= 1024 && idx < units.size - 1) {
            b /= 1024.0
            idx++
        }
        return String.format("%.1f %s", b, units[idx])
    }
}

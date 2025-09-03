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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.view.Menu
import android.view.MenuItem
import android.widget.ProgressBar
import com.agreenbhm.vibetainer.network.ImagePruneRequest
import com.agreenbhm.vibetainer.network.ImagePruneResponse
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import com.agreenbhm.vibetainer.ui.ImageItemAdapter
import com.agreenbhm.vibetainer.network.EnvironmentImage

class NodeImagesActivity : AppCompatActivity() {
    private lateinit var adapter: ImageItemAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list_generic)

        val endpointId = intent.getIntExtra("endpoint_id", -1)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_list)
        toolbar.title = "Images"
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        com.agreenbhm.vibetainer.ui.EdgeToEdge.apply(this, toolbar, findViewById(R.id.swipe_list))

        val recycler = findViewById<RecyclerView>(R.id.recycler_list)
        val swipe = findViewById<SwipeRefreshLayout>(R.id.swipe_list)
        recycler.layoutManager = LinearLayoutManager(this)
        adapter = ImageItemAdapter({ selectionCount ->
            supportActionBar?.subtitle = if (selectionCount > 0) "Selected: $selectionCount" else null
        }, { item ->
            // start selection mode and select this item
            adapter.enterSelectionModeAndSelect(item)
        })
        recycler.adapter = adapter

        swipe.setOnRefreshListener { load() }
        load()
    }

    private fun load() {
        val endpointId = intent.getIntExtra("endpoint_id", -1)
        val recycler = findViewById<RecyclerView>(R.id.recycler_list)
        val swipe = findViewById<SwipeRefreshLayout>(R.id.swipe_list)
        swipe.isRefreshing = true
        val prefs = com.agreenbhm.vibetainer.util.Prefs(this)
        val api = PortainerApi.create(this, prefs.baseUrl(), prefs.token())
        lifecycleScope.launch {
            try {
                // Single call that returns used flag for each image
                val resp = runCatching { api.listEnvironmentImages(endpointId, true) }.getOrDefault(emptyList())
                val agentTarget = intent.getStringExtra("agent_target")
                val filtered = if (!agentTarget.isNullOrBlank()) resp.filter { it.nodeName == agentTarget } else resp
                adapter.submitList(filtered.map { ImageListItem(it) })
            } catch (e: Exception) {
                Snackbar.make(recycler, "Failed: ${e.message}", Snackbar.LENGTH_LONG).show()
            } finally {
                swipe.isRefreshing = false
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_node_images, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_prune_images -> { confirmAndPruneImages(); return true }
            R.id.action_delete_selected -> { deleteSelected(); return true }
            android.R.id.home -> { finish(); return true }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun confirmAndPruneImages() {
        val recycler = findViewById<RecyclerView>(R.id.recycler_list)
        val prefs = com.agreenbhm.vibetainer.util.Prefs(this)
        val api = PortainerApi.create(this, prefs.baseUrl(), prefs.token())
        val endpointId = intent.getIntExtra("endpoint_id", -1)

        MaterialAlertDialogBuilder(this)
            .setTitle("Prune unused images")
            .setMessage("This will remove unused Docker images on the endpoint. This action cannot be undone. Continue?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Prune") { _, _ ->
                val progressBar = ProgressBar(this)
                val dlg = MaterialAlertDialogBuilder(this)
                    .setTitle("Pruning images")
                    .setView(progressBar)
                    .setCancelable(false)
                    .create()
                dlg.show()

                lifecycleScope.launch {
                    try {
                        val req = ImagePruneRequest(mapOf("dangling" to listOf("true")))
                        val resp: ImagePruneResponse = api.pruneImages(endpointId, req, null)
                        val deleted = resp.ImagesDeleted?.size ?: 0
                        val reclaimed = resp.SpaceReclaimed ?: 0L
                        dlg.dismiss()
                        Snackbar.make(recycler, "Pruned $deleted images, reclaimed ${formatBytes(reclaimed)}", Snackbar.LENGTH_LONG).show()
                        load()
                    } catch (e: Exception) {
                        dlg.dismiss()
                        Snackbar.make(recycler, "Prune failed: ${e.message}", Snackbar.LENGTH_LONG).show()
                    }
                }
            }
            .show()
    }

    private fun deleteSelected() {
        val prefs = com.agreenbhm.vibetainer.util.Prefs(this)
        val api = PortainerApi.create(this, prefs.baseUrl(), prefs.token())
        val endpointId = intent.getIntExtra("endpoint_id", -1)
        val recycler = findViewById<RecyclerView>(R.id.recycler_list)
        val selected = adapter.getSelectedItems()
        if (selected.isEmpty()) {
            Snackbar.make(recycler, "No images selected", Snackbar.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete selected images")
            .setMessage("Delete ${selected.size} selected images? This cannot be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                val dlg = MaterialAlertDialogBuilder(this).setView(ProgressBar(this)).setCancelable(false).create()
                dlg.show()
                lifecycleScope.launch {
                    var success = 0
                    for (it in selected) {
                        try {
                            val node = it.image.nodeName
                            val id = it.image.id ?: continue
                            api.deleteImage(endpointId, id, 1, node)
                            success++
                        } catch (_: Exception) { }
                    }
                    dlg.dismiss()
                    Snackbar.make(recycler, "Deleted $success images", Snackbar.LENGTH_LONG).show()
                    adapter.clearSelection()
                    load()
                }
            }
            .show()
    }

    private fun deleteAllUnused() {
        val prefs = com.agreenbhm.vibetainer.util.Prefs(this)
        val api = PortainerApi.create(this, prefs.baseUrl(), prefs.token())
        val endpointId = intent.getIntExtra("endpoint_id", -1)
        val recycler = findViewById<RecyclerView>(R.id.recycler_list)
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete all unused images")
            .setMessage("Delete all unused images across the environment? This cannot be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                val dlg = MaterialAlertDialogBuilder(this).setView(ProgressBar(this)).setCancelable(false).create()
                dlg.show()
                lifecycleScope.launch {
                    try {
                        // get usage info and delete those marked unused
                        val all = api.listEnvironmentImages(endpointId, true)
                        val unused = all.filter { it.used == false }
                        var success = 0
                        for (it in unused) {
                            try {
                                val node = it.nodeName
                                val id = it.id ?: continue
                                api.deleteImage(endpointId, id, 1, node)
                                success++
                            } catch (_: Exception) { }
                        }
                        dlg.dismiss()
                        Snackbar.make(recycler, "Deleted $success unused images", Snackbar.LENGTH_LONG).show()
                        load()
                    } catch (e: Exception) {
                        dlg.dismiss()
                        Snackbar.make(recycler, "Failed: ${e.message}", Snackbar.LENGTH_LONG).show()
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

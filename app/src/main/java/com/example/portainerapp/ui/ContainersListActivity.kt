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
import com.example.portainerapp.util.Prefs
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
        com.example.portainerapp.ui.EdgeToEdge.apply(this, toolbar, findViewById(R.id.swipe_list))

        val recycler: RecyclerView = findViewById(R.id.recycler_list)
        val swipe: SwipeRefreshLayout = findViewById(R.id.swipe_list)
        val chipAll: Chip = findViewById(R.id.chip_filter_all)
        val chipRunning: Chip = findViewById(R.id.chip_filter_running)
        val chipStopped: Chip = findViewById(R.id.chip_filter_stopped)
        recycler.layoutManager = LinearLayoutManager(this)

        val prefs = Prefs(this)
        val api = PortainerApi.create(this, prefs.baseUrl(), prefs.token())
        val endpointId = prefs.endpointId()

        val adapter = ContainerAdapter({ c ->
            val i = Intent(this, ContainerDetailActivity::class.java)
            i.putExtra(ContainerDetailActivity.EXTRA_ENDPOINT_ID, endpointId)
            i.putExtra(ContainerDetailActivity.EXTRA_CONTAINER_ID, c.Id)
            startActivity(i)
        }) { c ->
            // Subtitle: cleaned image:tag only (no sha digest)
            val raw = c.Image.orEmpty()
            when {
                raw.isBlank() -> ""
                raw.startsWith("sha256:") -> ""
                raw.matches(Regex("[a-f0-9]{64}")) -> ""
                else -> raw.substringBefore('@')
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
                    allContainers = api.listContainers(endpointId, true, null)
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
}

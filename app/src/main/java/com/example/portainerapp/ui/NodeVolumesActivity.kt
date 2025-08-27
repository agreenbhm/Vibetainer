package com.example.portainerapp.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.portainerapp.R
import com.example.portainerapp.network.PortainerApi
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.snackbar.Snackbar
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
        com.example.portainerapp.ui.EdgeToEdge.apply(this, toolbar, findViewById(R.id.swipe_list))

        val recycler = findViewById<RecyclerView>(R.id.recycler_list)
        val swipe = findViewById<SwipeRefreshLayout>(R.id.swipe_list)
        recycler.layoutManager = LinearLayoutManager(this)
        val adapter = SimpleTextAdapter()
        recycler.adapter = adapter

        val prefs = com.example.portainerapp.util.Prefs(this)
        val api = PortainerApi.create(this, prefs.baseUrl(), prefs.token())

        fun load() {
            swipe.isRefreshing = true
            lifecycleScope.launch {
                try {
                    val resp = api.listVolumes(endpointId, agentTarget)
                    adapter.submit((resp.Volumes ?: emptyList()).map { it.Name ?: "<none>" })
                } catch (e: Exception) {
                    Snackbar.make(recycler, "Failed: ${e.message}", Snackbar.LENGTH_LONG).show()
                } finally {
                    swipe.isRefreshing = false
                }
            }
        }
        swipe.setOnRefreshListener { load() }
        load()
    }
}

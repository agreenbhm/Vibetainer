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
import com.agreenbhm.vibetainer.ui.adapters.StacksAdapter
import com.agreenbhm.vibetainer.ui.adapters.StackRow
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class StacksListActivity : AppCompatActivity() {
    // No overflow menu on this screen


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list_generic)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_list)
        toolbar.title = "Stacks"
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        val recycler = findViewById<RecyclerView>(R.id.recycler_list)
        val swipe = findViewById<SwipeRefreshLayout>(R.id.swipe_list)
        recycler.layoutManager = LinearLayoutManager(this)
        val adapter = StacksAdapter(
            onOpen = { stackName, filter ->
                val i = Intent(this, StackDetailActivity::class.java)
                i.putExtra(StackDetailActivity.EXTRA_STACK_NAME, stackName)
                if (filter != null) i.putExtra(StackDetailActivity.EXTRA_STATE_FILTER, filter)
                startActivity(i)
            }
        )
        recycler.adapter = adapter

        val prefs = Prefs(this)
        // Show endpoint name as subtitle
        toolbar.subtitle = prefs.endpointName()
        val api = PortainerApi.create(this, prefs.baseUrl(), prefs.token())
        val endpointId = prefs.endpointId()

        fun load() {
            swipe.isRefreshing = true
            lifecycleScope.launch {
                try {
                    val stacks = api.listStacks().filter { (it.EndpointId ?: -1) == endpointId }
                    val containers = api.listContainers(endpointId, true, null)
                    val rows = stacks.map { s ->
                        val name = s.Name ?: (s.Id?.toString() ?: "")
                        val stackContainers = containers.filter { it.Labels?.get("com.docker.stack.namespace") == name }
                        val servicesCount = stackContainers.mapNotNull { it.Labels?.get("com.docker.swarm.service.name") }.toSet().size
                        val running = stackContainers.count { (it.State ?: "").equals("running", ignoreCase = true) }
                        val stopped = stackContainers.size - running
                        StackRow(name, servicesCount, running, stopped)
                    }.sortedBy { it.name.lowercase() }
                    adapter.submit(rows)
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

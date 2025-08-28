package com.agreenbhm.vibetainer.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.agreenbhm.vibetainer.MainActivity
import com.agreenbhm.vibetainer.R
import com.agreenbhm.vibetainer.network.Endpoint
import com.agreenbhm.vibetainer.network.PortainerApi
import com.agreenbhm.vibetainer.util.Prefs
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.launch

class EndpointListActivity : AppCompatActivity() {
    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        // Hide switch endpoint on environments list; keep settings/logout
        menu.findItem(R.id.action_switch_endpoint)?.isVisible = false
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
            R.id.action_logout -> { com.agreenbhm.vibetainer.util.Prefs(this).clearAll(); startActivity(Intent(this, LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)); true }
            else -> super.onOptionsItemSelected(item)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_endpoints)

        val prefs = Prefs(this)
        val api = PortainerApi.create(this, prefs.baseUrl(), prefs.token())

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_endpoints)
        setSupportActionBar(toolbar)
        // No overflow menu on this screen
        supportActionBar?.title = "Environments"
        com.agreenbhm.vibetainer.ui.EdgeToEdge.apply(this, toolbar, findViewById(R.id.recycler_endpoints))

        val recycler = findViewById<RecyclerView>(R.id.recycler_endpoints)
        recycler.layoutManager = LinearLayoutManager(this)
        val adapter = EndpointAdapter { ep ->
            prefs.saveEndpoint(ep.Id, ep.Name)
            startActivity(Intent(this, DashboardActivity::class.java))
            finish()
        }
        recycler.adapter = adapter

        val emptyView = findViewById<android.widget.TextView>(R.id.empty_view)
        lifecycleScope.launch {
            try {
                val endpoints = api.listEndpoints().sortedBy { it.Name.lowercase() }
                adapter.submit(endpoints)
                emptyView.visibility = if (endpoints.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            } catch (e: Exception) {
                com.google.android.material.snackbar.Snackbar.make(recycler, "Failed to load endpoints", com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                    .setAction("Retry") {
                        recreate()
                    }
                    .setAction("Settings") {
                        startActivity(android.content.Intent(this@EndpointListActivity, com.agreenbhm.vibetainer.ui.LoginActivity::class.java))
                    }
                    .show()
                emptyView.visibility = android.view.View.VISIBLE
            }
        }
    }
}

class EndpointAdapter(private val onClick: (Endpoint) -> Unit) : RecyclerView.Adapter<EndpointVH>() {
    private val items = mutableListOf<Endpoint>()
    fun submit(list: List<Endpoint>) { items.clear(); items.addAll(list); notifyDataSetChanged() }
    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): EndpointVH {
        val view = android.view.LayoutInflater.from(parent.context).inflate(R.layout.item_endpoint, parent, false)
        return EndpointVH(view, onClick)
    }
    override fun getItemCount(): Int = items.size
    override fun onBindViewHolder(holder: EndpointVH, position: Int) = holder.bind(items[position])
}

class EndpointVH(itemView: android.view.View, private val onClick: (Endpoint) -> Unit) : RecyclerView.ViewHolder(itemView) {
    private val title = itemView.findViewById<android.widget.TextView>(R.id.text_endpoint)
    fun bind(item: Endpoint) {
        title.text = item.Name
        itemView.setOnClickListener { onClick(item) }
    }
}

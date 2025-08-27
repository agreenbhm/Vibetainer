package com.example.portainerapp.ui

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.portainerapp.MainActivity
import com.example.portainerapp.R
import com.example.portainerapp.network.PortainerApi
import com.example.portainerapp.util.Prefs
import com.google.android.material.appbar.MaterialToolbar
import android.view.View
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

class DashboardActivity : AppCompatActivity() {
    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }
    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
            R.id.action_switch_endpoint -> { startActivity(Intent(this, EndpointListActivity::class.java)); true }
            R.id.action_logout -> { com.example.portainerapp.util.Prefs(this).clearAll(); startActivity(Intent(this, LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)); true }
            else -> super.onOptionsItemSelected(item)
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_dashboard)
        setSupportActionBar(toolbar)

        val prefs = Prefs(this)
        val endpointId = prefs.endpointId()
        val api = PortainerApi.create(this, prefs.baseUrl(), prefs.token())

        // Set initial title to endpoint while loading
        supportActionBar?.title = prefs.endpointName() + " • Updating..."

        val cardNodes = findViewById<View>(R.id.card_nodes)
        val cardContainers = findViewById<View>(R.id.card_containers)
        val cardServices = findViewById<View>(R.id.card_services)
        val cardStacks = findViewById<View>(R.id.card_stacks)
        val swipe = findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout>(R.id.swipe_dashboard)

        val nodesCount = findViewById<TextView>(R.id.text_nodes_count)
        val containersCount = findViewById<TextView>(R.id.text_containers_count)
        val servicesCount = findViewById<TextView>(R.id.text_services_count)
        val stacksCount = findViewById<TextView>(R.id.text_stacks_count)

        fun loadCounts() {
            swipe.isRefreshing = true
            lifecycleScope.launch {
            try {
                val results = listOf(
                    async { runCatching { api.listNodes(endpointId).size }.getOrDefault(0) },
                    async { runCatching { api.listContainers(endpointId, false, null).size }.getOrDefault(0) },
                    async { runCatching { api.listServices(endpointId).size }.getOrDefault(0) },
                    async { runCatching { api.listStacks().count { (it.EndpointId ?: -1) == endpointId } }.getOrDefault(0) }
                ).awaitAll()
                nodesCount.text = results[0].toString()
                containersCount.text = results[1].toString()
                servicesCount.text = results[2].toString()
                stacksCount.text = results[3].toString()
                val whenTxt = java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())
                val ep = prefs.endpointName()
                supportActionBar?.title = ep + " • Updated " + whenTxt
            } catch (_: Exception) {
                Snackbar.make(cardNodes, "Failed to load counts", Snackbar.LENGTH_LONG).show()
            } finally { swipe.isRefreshing = false }
            }
        }

        swipe.setOnRefreshListener { loadCounts() }
        loadCounts()

        cardNodes.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
        cardContainers.setOnClickListener {
            startActivity(Intent(this, ContainersListActivity::class.java))
        }
        cardServices.setOnClickListener {
            startActivity(Intent(this, ServicesListActivity::class.java))
        }
        cardStacks.setOnClickListener {
            startActivity(Intent(this, StacksListActivity::class.java))
        }
    }
}

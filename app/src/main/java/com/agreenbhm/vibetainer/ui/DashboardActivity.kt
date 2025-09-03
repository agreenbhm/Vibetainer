package com.agreenbhm.vibetainer.ui

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.agreenbhm.vibetainer.MainActivity
import com.agreenbhm.vibetainer.R
import com.agreenbhm.vibetainer.network.PortainerApi
import com.agreenbhm.vibetainer.util.Prefs
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
            R.id.action_logout -> { com.agreenbhm.vibetainer.util.Prefs(this).clearAll(); startActivity(Intent(this, LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)); true }
            else -> super.onOptionsItemSelected(item)
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_dashboard)
        setSupportActionBar(toolbar)
        com.agreenbhm.vibetainer.ui.EdgeToEdge.apply(this, toolbar, findViewById(R.id.swipe_dashboard))

        val prefs = Prefs(this)
        val endpointId = prefs.endpointId()
        val api = PortainerApi.create(this, prefs.baseUrl(), prefs.token())

        // Set initial title to endpoint while loading
        supportActionBar?.title = prefs.endpointName() + " • Updating..."
        supportActionBar?.subtitle = prefs.baseUrl()

        val cardNodes = findViewById<View>(R.id.card_nodes)
        val cardContainers = findViewById<View>(R.id.card_containers)
        val cardServices = findViewById<View>(R.id.card_services)
        val cardStacks = findViewById<View>(R.id.card_stacks)
        val swipe = findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout>(R.id.swipe_dashboard)

        val nodesCount = findViewById<TextView>(R.id.text_nodes_count)
        val containersCount = findViewById<TextView>(R.id.text_containers_count)
        val servicesCount = findViewById<TextView>(R.id.text_services_count)
        val imagesCount = findViewById<TextView>(R.id.text_images_count)
        val volumesCount = findViewById<TextView>(R.id.text_volumes_count)
        val configsCount = findViewById<TextView>(R.id.text_configs_count)
        val stacksCount = findViewById<TextView>(R.id.text_stacks_count)
        val networksCount = findViewById<TextView>(R.id.text_networks_count)
        val chipRunning = findViewById<com.google.android.material.chip.Chip>(R.id.chip_running)
        val chipStopped = findViewById<com.google.android.material.chip.Chip>(R.id.chip_stopped)

        fun loadCounts() {
            swipe.isRefreshing = true
            lifecycleScope.launch {
            try {
                val nodesDeferred = async { runCatching { api.listNodes(endpointId).size }.getOrDefault(0) }
                val containersDeferred = async { runCatching { api.listContainers(endpointId, true, null) }.getOrDefault(emptyList()) }
                val servicesDeferred = async { runCatching { api.listServices(endpointId).size }.getOrDefault(0) }
                val stacksDeferred = async { runCatching { api.listStacks().count { (it.EndpointId ?: -1) == endpointId } }.getOrDefault(0) }
                val imagesDeferred = async { runCatching { api.listImages(endpointId, null).size }.getOrDefault(0) }
                val volumesDeferred = async { runCatching { api.listVolumes(endpointId, null).Volumes?.size ?: 0 }.getOrDefault(0) }
                val configsDeferred = async { runCatching { api.listConfigs(endpointId).size }.getOrDefault(0) }
                val networksDeferred = async { runCatching { api.listNetworks(endpointId, null).size }.getOrDefault(0) }

                val totalNodes = nodesDeferred.await()
                val containersList = containersDeferred.await()
                val totalServices = servicesDeferred.await()
                val totalStacks = stacksDeferred.await()

                nodesCount.text = totalNodes.toString()
                containersCount.text = containersList.size.toString()
               servicesCount.text = totalServices.toString()
                imagesCount.text = imagesDeferred.await().toString()
                volumesCount.text = volumesDeferred.await().toString()
                configsCount.text = configsDeferred.await().toString()
                stacksCount.text = totalStacks.toString()
                networksCount.text = networksDeferred.await().toString()

                val runningCount = containersList.count { (it.State ?: "").equals("running", ignoreCase = true) }
                val stoppedCount = containersList.size - runningCount
                chipRunning.text = "Running $runningCount"
                chipStopped.text = "Stopped $stoppedCount"
                supportActionBar?.title = prefs.endpointName()
                supportActionBar?.subtitle = prefs.baseUrl()
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
        findViewById<View>(R.id.card_images).setOnClickListener {
            val i = Intent(this, NodeImagesActivity::class.java)
            i.putExtra("endpoint_id", endpointId)
            startActivity(i)
        }
        findViewById<View>(R.id.card_volumes).setOnClickListener {
            val i = Intent(this, NodeVolumesActivity::class.java)
            i.putExtra("endpoint_id", endpointId)
            startActivity(i)
        }
        cardContainers.setOnClickListener { startActivity(Intent(this, ContainersListActivity::class.java)) }
        chipRunning.setOnClickListener {
            val i = Intent(this, ContainersListActivity::class.java)
            i.putExtra("state_filter", "running")
            startActivity(i)
        }
        chipStopped.setOnClickListener {
            val i = Intent(this, ContainersListActivity::class.java)
            i.putExtra("state_filter", "stopped")
            startActivity(i)
        }
        cardServices.setOnClickListener {
            startActivity(Intent(this, ServicesListActivity::class.java))
        }
        cardStacks.setOnClickListener {
            startActivity(Intent(this, StacksListActivity::class.java))
        }
        findViewById<View>(R.id.card_configs).setOnClickListener {
            val i = Intent(this, ConfigsListActivity::class.java)
            i.putExtra("endpoint_id", endpointId)
            startActivity(i)
        }
        findViewById<View>(R.id.card_networks).setOnClickListener {
            startActivity(Intent(this, NetworksListActivity::class.java))
        }
    }
}

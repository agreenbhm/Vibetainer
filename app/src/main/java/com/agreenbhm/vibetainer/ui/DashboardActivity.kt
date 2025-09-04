package com.agreenbhm.vibetainer.ui

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.agreenbhm.vibetainer.MainActivity
import com.agreenbhm.vibetainer.R
import com.agreenbhm.vibetainer.network.PortainerApi
import com.agreenbhm.vibetainer.network.ContainerSummary
import com.agreenbhm.vibetainer.util.Prefs
import com.google.android.material.appbar.MaterialToolbar
import android.view.View
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

class DashboardActivity : AppCompatActivity() {
    
    data class CountResult(val count: Int, val isOffline: Boolean = false) {
        companion object {
            fun success(count: Int) = CountResult(count, false)
            fun offline() = CountResult(0, true)
        }
    }
    
    data class ContainersResult(val containers: List<ContainerSummary>, val isOffline: Boolean = false) {
        companion object {
            fun success(containers: List<ContainerSummary>) = ContainersResult(containers, false)
            fun offline() = ContainersResult(emptyList(), true)
        }
    }
    
    private fun displayCount(result: CountResult): String {
        return if (result.isOffline) "Offline" else result.count.toString()
    }
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
                val nodesDeferred = async { 
                    runCatching { CountResult.success(api.listNodes(endpointId).size) }.getOrElse { CountResult.offline() }
                }
                val containersDeferred = async { 
                    runCatching { ContainersResult.success(api.listContainers(endpointId, true, null)) }.getOrElse { ContainersResult.offline() }
                }
                val servicesDeferred = async { 
                    runCatching { CountResult.success(api.listServices(endpointId).size) }.getOrElse { CountResult.offline() }
                }
                val stacksDeferred = async { 
                    runCatching { CountResult.success(api.listStacks().count { (it.EndpointId ?: -1) == endpointId }) }.getOrElse { CountResult.offline() }
                }
                val imagesDeferred = async { 
                    runCatching { CountResult.success(api.listImages(endpointId, null).size) }.getOrElse { CountResult.offline() }
                }
                val volumesDeferred = async { 
                    runCatching { CountResult.success(api.listVolumes(endpointId, null).Volumes?.size ?: 0) }.getOrElse { CountResult.offline() }
                }
                val configsDeferred = async { 
                    runCatching { CountResult.success(api.listConfigs(endpointId).size) }.getOrElse { CountResult.offline() }
                }
                val networksDeferred = async { 
                    runCatching { CountResult.success(api.listNetworks(endpointId, null).size) }.getOrElse { CountResult.offline() }
                }

                val totalNodes = nodesDeferred.await()
                val containersResult = containersDeferred.await()
                val totalServices = servicesDeferred.await()
                val totalStacks = stacksDeferred.await()
                val totalImages = imagesDeferred.await()
                val totalVolumes = volumesDeferred.await()
                val totalConfigs = configsDeferred.await()
                val totalNetworks = networksDeferred.await()

                nodesCount.text = displayCount(totalNodes)
                servicesCount.text = displayCount(totalServices)
                stacksCount.text = displayCount(totalStacks)
                imagesCount.text = displayCount(totalImages)
                volumesCount.text = displayCount(totalVolumes)
                configsCount.text = displayCount(totalConfigs)
                networksCount.text = displayCount(totalNetworks)

                // Handle containers specially since we need running/stopped counts
                if (containersResult.isOffline) {
                    containersCount.text = "Offline"
                    chipRunning.text = "Running: Offline"
                    chipStopped.text = "Stopped: Offline"
                } else {
                    containersCount.text = containersResult.containers.size.toString()
                    val runningCount = containersResult.containers.count { (it.State ?: "").equals("running", ignoreCase = true) }
                    val stoppedCount = containersResult.containers.size - runningCount
                    chipRunning.text = "Running $runningCount"
                    chipStopped.text = "Stopped $stoppedCount"
                }
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

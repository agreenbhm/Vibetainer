package com.agreenbhm.vibetainer.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.agreenbhm.vibetainer.R
import com.agreenbhm.vibetainer.network.NetworkSummary
import com.agreenbhm.vibetainer.network.PortainerApi
import com.agreenbhm.vibetainer.network.PortainerService
import com.agreenbhm.vibetainer.util.Prefs
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class NetworkDetailActivity : AppCompatActivity() {
    
    private lateinit var api: PortainerService
    private var endpointId: Int = -1
    private var networkId: String? = null
    private var networkName: String? = null
    private var currentNetwork: NetworkSummary? = null
    private var canDelete = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_network_detail)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_network_detail)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        EdgeToEdge.apply(this, toolbar, findViewById(R.id.swipe_network_detail))

        val prefs = Prefs(this)
        api = PortainerApi.create(this, prefs.baseUrl(), prefs.token())
        endpointId = intent.getIntExtra("endpoint_id", prefs.endpointId())
        networkId = intent.getStringExtra("network_id")
        networkName = intent.getStringExtra("network_name")

        val swipe = findViewById<SwipeRefreshLayout>(R.id.swipe_network_detail)
        swipe.setOnRefreshListener { loadNetworkDetails() }

        loadNetworkDetails()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_network_detail, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val deleteItem = menu.findItem(R.id.action_delete_network)
        deleteItem.isEnabled = canDelete
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_delete_network -> {
                if (canDelete) showDeleteConfirmation()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun loadNetworkDetails() {
        val swipe = findViewById<SwipeRefreshLayout>(R.id.swipe_network_detail)
        swipe.isRefreshing = true
        
        lifecycleScope.launch {
            try {
                val networkIdToUse = networkId ?: networkName ?: run {
                    Snackbar.make(findViewById(R.id.swipe_network_detail), "No network ID or name provided", Snackbar.LENGTH_LONG).show()
                    finish()
                    return@launch
                }
                
                val network = api.inspectNetwork(endpointId, networkIdToUse, null)
                currentNetwork = network
                displayNetworkInfo(network)
                updateActionBarTitles(network)
                
            } catch (e: Exception) {
                Snackbar.make(findViewById(R.id.swipe_network_detail), "Failed to load network details: ${e.message}", Snackbar.LENGTH_LONG).show()
            } finally {
                swipe.isRefreshing = false
            }
        }
    }

    private fun updateActionBarTitles(network: NetworkSummary) {
        val prefs = Prefs(this)
        
        // Set title to network name
        supportActionBar?.title = network.Name ?: "Unknown Network"
        
        // Set subtitle based on network scope and type
        val subtitle = when {
            network.Scope == "swarm" || network.Driver == "overlay" -> {
                // For swarm/overlay networks, show environment name
                prefs.endpointName()
            }
            network.Scope == "local" -> {
                // For local networks, try to get node name
                network.nodeName ?: "Local Network"
            }
            else -> {
                network.Scope ?: "Unknown Scope"
            }
        }
        
        supportActionBar?.subtitle = subtitle
    }

    private fun displayNetworkInfo(network: NetworkSummary) {
        val nameText = findViewById<TextView>(R.id.text_network_name)
        val idText = findViewById<TextView>(R.id.text_network_id)
        val propertiesLayout = findViewById<LinearLayout>(R.id.layout_network_properties)
        val containerList = findViewById<LinearLayout>(R.id.container_list)
        val containersHeader = findViewById<TextView>(R.id.text_containers_header)
        val noContainersText = findViewById<TextView>(R.id.text_no_containers)

        nameText.text = network.Name ?: "Unknown"
        idText.text = "ID: ${network.Id?.take(12) ?: "Unknown"}"

        // Clear existing properties
        propertiesLayout.removeAllViews()

        // Add property text views
        addPropertyText(propertiesLayout, "Driver", network.Driver ?: "unknown")
        addPropertyText(propertiesLayout, "Scope", network.Scope ?: "local")
        
        if (network.Internal == true) {
            addPropertyText(propertiesLayout, "Internal", "Yes")
        }
        
        if (network.Attachable == true) {
            addPropertyText(propertiesLayout, "Attachable", "Yes")
        }
        
        if (network.EnableIPv6 == true) {
            addPropertyText(propertiesLayout, "IPv6", "Enabled")
        }
        
        if (network.Ingress == true) {
            addPropertyText(propertiesLayout, "Ingress", "Yes")
        }

        // Add IPAM information if available
        network.IPAM?.Config?.forEach { config ->
            if (!config.Subnet.isNullOrBlank()) {
                addPropertyText(propertiesLayout, "Subnet", config.Subnet)
            }
            if (!config.Gateway.isNullOrBlank()) {
                addPropertyText(propertiesLayout, "Gateway", config.Gateway)
            }
        }

        // Display connected containers
        containerList.removeAllViews()
        val containers = network.Containers
        
        // Filter out load balancer endpoint containers
        val filteredContainers = containers?.filterNot { (containerId, containerInfo) ->
            containerId.startsWith("lb-") && containerInfo.Name?.endsWith("-endpoint") == true
        }
        
        canDelete = filteredContainers.isNullOrEmpty()
        invalidateOptionsMenu() // Update delete button state
        
        if (filteredContainers.isNullOrEmpty()) {
            containersHeader.visibility = View.GONE
            noContainersText.visibility = View.VISIBLE
        } else {
            containersHeader.visibility = View.VISIBLE
            noContainersText.visibility = View.GONE
            
            filteredContainers.forEach { (containerId, containerInfo) ->
                val containerCard = layoutInflater.inflate(R.layout.item_network_container, containerList, false)
                val containerName = containerCard.findViewById<TextView>(R.id.text_container_name)
                val containerIp = containerCard.findViewById<TextView>(R.id.text_container_ip)
                
                containerName.text = containerInfo.Name ?: containerId.take(12)
                containerIp.text = "IPv4: ${containerInfo.IPv4Address ?: "N/A"}"
                
                // Add click handler to navigate to container details
                containerCard.setOnClickListener {
                    navigateToContainerDetail(containerId, containerInfo.Name)
                }
                
                containerList.addView(containerCard)
            }
        }
    }

    private fun navigateToContainerDetail(containerId: String, containerName: String?) {
        val intent = android.content.Intent(this, ContainerDetailActivity::class.java)
        intent.putExtra("container_id", containerId)
        if (!containerName.isNullOrBlank()) {
            intent.putExtra("container_name", containerName)
        }
        intent.putExtra("endpoint_id", endpointId)
        startActivity(intent)
    }

    private fun addPropertyText(parent: LinearLayout, label: String, value: String) {
        val textView = TextView(this)
        textView.text = "$label: $value"
        textView.textSize = 14f
        textView.setPadding(0, 8, 0, 8)
        parent.addView(textView)
    }

    private fun showDeleteConfirmation() {
        val network = currentNetwork ?: return
        
        AlertDialog.Builder(this)
            .setTitle("Delete Network")
            .setMessage("Are you sure you want to delete the network '${network.Name}'? This action cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                deleteNetwork()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteNetwork() {
        val network = currentNetwork ?: return
        val networkIdToDelete = network.Id ?: return
        
        lifecycleScope.launch {
            try {
                val response = api.deleteNetwork(endpointId, networkIdToDelete, null)
                if (response.isSuccessful) {
                    Snackbar.make(findViewById(R.id.swipe_network_detail), "Network '${network.Name}' deleted successfully", Snackbar.LENGTH_LONG).show()
                    finish()
                } else {
                    Snackbar.make(findViewById(R.id.swipe_network_detail), "Failed to delete network: ${response.message()}", Snackbar.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Snackbar.make(findViewById(R.id.swipe_network_detail), "Error deleting network: ${e.message}", Snackbar.LENGTH_LONG).show()
            }
        }
    }
}
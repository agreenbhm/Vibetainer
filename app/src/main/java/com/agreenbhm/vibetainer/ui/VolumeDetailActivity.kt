package com.agreenbhm.vibetainer.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.agreenbhm.vibetainer.R
import com.agreenbhm.vibetainer.network.PortainerApi
import com.agreenbhm.vibetainer.network.Volume
import com.agreenbhm.vibetainer.util.Prefs
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import androidx.core.view.isEmpty

class VolumeDetailActivity : AppCompatActivity() {

    private lateinit var api: com.agreenbhm.vibetainer.network.PortainerService
    private var endpointId: Int = -1
    private var volumeName: String? = null
    private var agentTarget: String? = null
    private var currentVolume: Volume? = null
    companion object {
        const val EXTRA_ENDPOINT_ID = "endpoint_id"
        const val EXTRA_VOLUME_NAME = "volume_name"
        const val EXTRA_AGENT_TARGET = "agent_target"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_volume_detail)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_volume_detail)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        EdgeToEdge.apply(this, toolbar, findViewById(R.id.swipe_volume_detail))

        val prefs = Prefs(this)
        api = PortainerApi.create(this, prefs.baseUrl(), prefs.token())
        endpointId = intent.getIntExtra("endpoint_id", prefs.endpointId())
        volumeName = intent.getStringExtra("volume_name")
        agentTarget = intent.getStringExtra("agent_target")

        val swipe = findViewById<SwipeRefreshLayout>(R.id.swipe_volume_detail)
        swipe.setOnRefreshListener { loadVolumeDetails() }

        loadVolumeDetails()
    }

    private fun loadVolumeDetails() {
        val swipe = findViewById<SwipeRefreshLayout>(R.id.swipe_volume_detail)
        swipe.isRefreshing = true

        lifecycleScope.launch {
            try {
                // Since there's no direct volume inspect endpoint, we'll fetch all volumes and find ours
                val filterAll = "{}"
                val volumesResponse = api.listVolumesFiltered(endpointId, filterAll, agentTarget)
                val volume = volumesResponse.Volumes?.find { it.Name == volumeName }

                if (volume != null) {
                    currentVolume = volume
                    displayVolumeDetails(volume)
                } else {
                    throw Exception("Volume not found")
                }
            } catch (e: Exception) {
                Snackbar.make(
                    findViewById(R.id.content_volume),
                    "Failed to load volume details: ${e.message}",
                    Snackbar.LENGTH_LONG
                ).show()
            } finally {
                swipe.isRefreshing = false
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun displayVolumeDetails(volume: Volume) {
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_volume_detail)
        toolbar.title = volume.Name ?: "Unknown Volume"
        toolbar.subtitle = volume.nodeName ?: agentTarget

        findViewById<TextView>(R.id.text_volume_name).text = volume.Name ?: "Unknown Volume"

        // Display node information
        val nodeText = volume.nodeName?.let { "Node: $it" } ?: "Node: Unknown"
        findViewById<TextView>(R.id.text_volume_node).text = nodeText

        // Display usage status
        val usageText = if (volume.isUnused) "Status: Unused" else "Status: In Use"
        findViewById<TextView>(R.id.text_volume_usage).text = usageText

        // Display additional properties
        val propertiesContainer = findViewById<LinearLayout>(R.id.layout_volume_properties)
        propertiesContainer.removeAllViews()

        // First, try to extract driver and options from portainer metadata
        var driverName: String? = null
        var driverOptions: Map<String, String>? = null

        volume?.let { volumeData ->
            // Look for driver information in various possible locations
            val driver = volume.driver
            val options = volume.options

            // Display driver information if available
            if (!driver.isNullOrBlank()) {
                val driverView = layoutInflater.inflate(
                    android.R.layout.simple_list_item_1,
                    propertiesContainer,
                    false
                ) as TextView
                driverView.text = "Driver: $driver"
                propertiesContainer.addView(driverView)
            }

            // Display driver options if available
            if (!options.isNullOrEmpty()) {
                val optionsView = layoutInflater.inflate(
                    android.R.layout.simple_list_item_1,
                    propertiesContainer,
                    false
                ) as TextView
                val optionsList = options.entries.joinToString("") { "\n\t${it.key}: ${it.value}" }
                optionsView.text = "Options: $optionsList"
                propertiesContainer.addView(optionsView)
            }

            // If no additional properties, show a message
            if (propertiesContainer.isEmpty()) {
                val noPropsView = TextView(this)
                noPropsView.text = "No additional properties available"
                noPropsView.setTextColor(resources.getColor(android.R.color.darker_gray, theme))
                propertiesContainer.addView(noPropsView)
            }
        }
    }
}
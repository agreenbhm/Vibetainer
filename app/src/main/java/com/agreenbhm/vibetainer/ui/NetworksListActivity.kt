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
import com.agreenbhm.vibetainer.network.PortainerService
import com.agreenbhm.vibetainer.util.Prefs
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import com.agreenbhm.vibetainer.ui.adapters.NetworkAdapter

class NetworksListActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list_generic)
        val prefs = Prefs(this)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_list)
        toolbar.title = "Networks"
        toolbar.subtitle = prefs.endpointName()
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        EdgeToEdge.apply(this, toolbar, findViewById(R.id.swipe_list))

        val recycler = findViewById<RecyclerView>(R.id.recycler_list)
        val swipe = findViewById<SwipeRefreshLayout>(R.id.swipe_list)
        recycler.layoutManager = LinearLayoutManager(this)

        val api: PortainerService = PortainerApi.create(this, prefs.baseUrl(), prefs.token())
        val endpointId = prefs.endpointId()

        val adapter = NetworkAdapter { network ->
            val intent = Intent(this, NetworkDetailActivity::class.java)
            intent.putExtra("network_id", network.Id)
            intent.putExtra("network_name", network.Name)
            intent.putExtra("endpoint_id", endpointId)
            startActivity(intent)
        }
        
        recycler.adapter = adapter

        fun loadNetworks() {
            swipe.isRefreshing = true
            lifecycleScope.launch {
                try {
                    val networks = api.listNetworks(endpointId, null)
                    adapter.submit(networks)
                } catch (e: Exception) {
                    Snackbar.make(recycler, "Failed to load networks: ${e.message}", Snackbar.LENGTH_LONG).show()
                } finally {
                    swipe.isRefreshing = false
                }
            }
        }

        swipe.setOnRefreshListener { loadNetworks() }
        loadNetworks()
    }
}
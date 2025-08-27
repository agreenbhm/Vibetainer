package com.example.portainerapp.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.portainerapp.R
import com.example.portainerapp.network.PortainerApi
import com.example.portainerapp.ui.adapters.ContainerAdapter
import com.example.portainerapp.util.Prefs
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class ContainersListActivity : AppCompatActivity() {
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
        setContentView(R.layout.activity_list_generic)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_list)
        toolbar.title = "Containers"
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        val recycler = findViewById<RecyclerView>(R.id.recycler_list)
        val swipe = findViewById<SwipeRefreshLayout>(R.id.swipe_list)
        recycler.layoutManager = LinearLayoutManager(this)

        val prefs = Prefs(this)
        val api = PortainerApi.create(this, prefs.baseUrl(), prefs.token())
        val endpointId = prefs.endpointId()

        val adapter = ContainerAdapter { c ->
            val i = Intent(this, ContainerDetailActivity::class.java)
            i.putExtra(ContainerDetailActivity.EXTRA_ENDPOINT_ID, endpointId)
            i.putExtra(ContainerDetailActivity.EXTRA_CONTAINER_ID, c.Id)
                        startActivity(i)
        }
        recycler.adapter = adapter

        fun load() {
            swipe.isRefreshing = true
            lifecycleScope.launch {
                try {
                    val list = api.listContainers(endpointId, false, null)
                    adapter.submit(list)
                } catch (e: Exception) {
                    Snackbar.make(recycler, "Failed: ${'$'}{e.message}", Snackbar.LENGTH_LONG).show()
                } finally {
                    swipe.isRefreshing = false
                }
            }
        }

        swipe.setOnRefreshListener { load() }
        load()
    }
}


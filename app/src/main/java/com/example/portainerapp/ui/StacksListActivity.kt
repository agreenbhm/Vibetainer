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
import com.example.portainerapp.util.Prefs
import com.google.android.material.appbar.MaterialToolbar
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
        val adapter = SimpleTextAdapter()
        recycler.adapter = adapter

        val prefs = Prefs(this)
        val api = PortainerApi.create(this, prefs.baseUrl(), prefs.token())
        val endpointId = prefs.endpointId()

        fun load() {
            swipe.isRefreshing = true
            lifecycleScope.launch {
                try {
                    val stacks = api.listStacks().filter { (it.EndpointId ?: -1) == endpointId }
                    adapter.submit(stacks.map { it.Name ?: (it.Id?.toString() ?: "") }.sortedBy { it.lowercase() })
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

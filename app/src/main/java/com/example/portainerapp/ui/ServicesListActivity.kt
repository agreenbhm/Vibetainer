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

class ServicesListActivity : AppCompatActivity() {
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
        toolbar.title = "Services"
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
                    val svcs = api.listServices(endpointId)
                    adapter.submit(svcs.map { it.Spec?.Name ?: (it.ID ?: "") })
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

class SimpleTextAdapter : RecyclerView.Adapter<TextVH>() {
    private val items = mutableListOf<String>()
    fun submit(list: List<String>) { items.clear(); items.addAll(list); notifyDataSetChanged() }
    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): TextVH {
        val view = android.view.LayoutInflater.from(parent.context).inflate(R.layout.item_simple_text, parent, false)
        return TextVH(view)
    }
    override fun getItemCount(): Int = items.size
    override fun onBindViewHolder(holder: TextVH, position: Int) = holder.bind(items[position])
}

class TextVH(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
    private val title = itemView.findViewById<android.widget.TextView>(R.id.text_title)
    fun bind(text: String) { title.text = text }
}

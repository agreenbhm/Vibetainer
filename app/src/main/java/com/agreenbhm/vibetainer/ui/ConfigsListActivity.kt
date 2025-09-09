package com.agreenbhm.vibetainer.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.view.Menu
import android.view.MenuItem
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.agreenbhm.vibetainer.R
import com.agreenbhm.vibetainer.network.PortainerApi
import com.agreenbhm.vibetainer.network.ConfigSummary
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class ConfigsListActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list_generic)

        val endpointId = intent.getIntExtra("endpoint_id", -1)
        val prefs = com.agreenbhm.vibetainer.util.Prefs(this)
        val api = PortainerApi.create(this, prefs.baseUrl(), prefs.token())
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_list)
        toolbar.title = "Configs"
        toolbar.subtitle = prefs.endpointName()
        setSupportActionBar(toolbar)
        EdgeToEdge.apply(this, toolbar, findViewById(R.id.swipe_list))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        val recycler = findViewById<RecyclerView>(R.id.recycler_list)
        val swipe = findViewById<SwipeRefreshLayout>(R.id.swipe_list)
        recycler.layoutManager = LinearLayoutManager(this)
        val adapter = SimpleTextAdapter()
        recycler.adapter = adapter



        fun load() {
            swipe.isRefreshing = true
            lifecycleScope.launch {
                try {
                    val list = api.listConfigs(endpointId)
                    adapter.submit(list.map { it.Spec?.Name ?: (it.ID ?: "<none>") })
                } catch (e: Exception) {
                    Snackbar.make(recycler, "Failed: ${e.message}", Snackbar.LENGTH_LONG).show()
                } finally {
                    swipe.isRefreshing = false
                }
            }
        }

        swipe.setOnRefreshListener { load() }
        load()

        recycler.addOnItemTouchListener(RecyclerItemClickListener(this, recycler) { view, position ->
            val name = (recycler.adapter as SimpleTextAdapter).let { it -> it }
            val prefs = com.agreenbhm.vibetainer.util.Prefs(this)
            lifecycleScope.launch {
                try {
                    val list = api.listConfigs(endpointId)
                    val cfg = list.getOrNull(position)
                    if (cfg != null) {
                        val i = Intent(this@ConfigsListActivity, ConfigDetailActivity::class.java)
                        i.putExtra("endpoint_id", endpointId)
                        i.putExtra("config_id", cfg.ID)
                        startActivity(i)
                    }
                } catch (_: Exception) { }
            }
        })
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_configs_list, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_new_config -> {
                val endpointId = intent.getIntExtra("endpoint_id", -1)
                val i = Intent(this, ConfigDetailActivity::class.java)
                i.putExtra("endpoint_id", endpointId)
                startActivity(i)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}

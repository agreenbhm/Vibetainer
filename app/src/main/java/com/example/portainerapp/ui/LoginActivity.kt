package com.example.portainerapp.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import androidx.appcompat.app.AppCompatActivity
import com.example.portainerapp.MainActivity
import com.example.portainerapp.R
import com.example.portainerapp.util.Prefs
import com.google.android.material.appbar.MaterialToolbar

class LoginActivity : AppCompatActivity() {
    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        // Hide items not applicable on login
        menu.findItem(R.id.action_switch_endpoint)?.isVisible = false
        menu.findItem(R.id.action_logout)?.isVisible = false
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
            R.id.action_switch_endpoint -> { startActivity(Intent(this, EndpointListActivity::class.java)); true }
            R.id.action_logout -> { true }
            else -> super.onOptionsItemSelected(item)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If already configured, go straight to nodes list
        val prefs = Prefs(this)
        if (prefs.hasValidConfig()) {
            val next = if (prefs.endpointId() > 0) DashboardActivity::class.java else EndpointListActivity::class.java
            startActivity(Intent(this, next))
            finish()
            return
        }

        setContentView(R.layout.activity_login)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_login)
        setSupportActionBar(toolbar)
        com.example.portainerapp.ui.EdgeToEdge.apply(this, toolbar, findViewById(android.R.id.content))

        val domainLayout = findViewById<TextInputLayout>(R.id.input_domain_layout)
        // Removed port input; URL field includes optional port
        val tokenLayout = findViewById<TextInputLayout>(R.id.input_token_layout)

        val domain = findViewById<TextInputEditText>(R.id.input_domain)
        // no port field
        val token = findViewById<TextInputEditText>(R.id.input_token)

        findViewById<Button>(R.id.button_save).setOnClickListener {
            val d = domain.text?.toString()?.trim().orEmpty()
            val p = "" // not used
            val t = token.text?.toString()?.trim().orEmpty()

            var valid = true
            domainLayout.error = null
            // no port validation
            tokenLayout.error = null

            if (d.isEmpty()) { domainLayout.error = "Required"; valid = false }
            // port not required; included in URL if needed
            if (t.isEmpty()) { tokenLayout.error = "Required"; valid = false }

            if (!valid) return@setOnClickListener

            // Build normalized base URL from full input
            prefs.saveBaseUrl(d, t)

            startActivity(Intent(this, EndpointListActivity::class.java))
            finish()
        }
    }
}

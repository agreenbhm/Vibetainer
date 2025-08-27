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
        val portLayout = findViewById<TextInputLayout>(R.id.input_port_layout)
        val tokenLayout = findViewById<TextInputLayout>(R.id.input_token_layout)

        val domain = findViewById<TextInputEditText>(R.id.input_domain)
        val port = findViewById<TextInputEditText>(R.id.input_port)
        val token = findViewById<TextInputEditText>(R.id.input_token)

        findViewById<Button>(R.id.button_save).setOnClickListener {
            val d = domain.text?.toString()?.trim().orEmpty()
            val p = port.text?.toString()?.trim().orEmpty()
            val t = token.text?.toString()?.trim().orEmpty()

            var valid = true
            domainLayout.error = null
            portLayout.error = null
            tokenLayout.error = null

            if (d.isEmpty()) { domainLayout.error = "Required"; valid = false }
            if (p.isEmpty()) { portLayout.error = "Required"; valid = false }
            if (t.isEmpty()) { tokenLayout.error = "Required"; valid = false }

            if (!valid) return@setOnClickListener

            prefs.saveConfig(domain = d, port = p, token = t)

            startActivity(Intent(this, EndpointListActivity::class.java))
            finish()
        }
    }
}

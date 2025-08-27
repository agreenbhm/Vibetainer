package com.example.portainerapp

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.portainerapp.network.AuthRequest
import com.example.portainerapp.network.PortainerApi
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val textView: TextView = findViewById(R.id.textView)
        val api = PortainerApi.create("http://localhost:9000/")

        lifecycleScope.launch {
            try {
                val auth = api.authenticate(AuthRequest("admin", "password"))
                val nodes = api.listNodes(1, "Bearer ${'$'}{auth.jwt}")
                textView.text = nodes.joinToString("\n") { it.Description?.Hostname ?: it.ID }
            } catch (e: Exception) {
                textView.text = "Error: ${'$'}{e.message}"
            }
        }
    }
}

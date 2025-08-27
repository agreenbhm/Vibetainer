package com.example.portainerapp.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.portainerapp.R
import com.google.android.material.chip.Chip

data class StackRow(
    val name: String,
    val servicesCount: Int,
    val running: Int,
    val stopped: Int
)

class StacksAdapter(
    private val onOpen: (String, String?) -> Unit
) : RecyclerView.Adapter<StackVH>() {
    private val items = mutableListOf<StackRow>()
    fun submit(list: List<StackRow>) { items.clear(); items.addAll(list); notifyDataSetChanged() }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StackVH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_stack, parent, false)
        return StackVH(v, onOpen)
    }
    override fun getItemCount(): Int = items.size
    override fun onBindViewHolder(holder: StackVH, position: Int) = holder.bind(items[position])
}

class StackVH(itemView: View, private val onOpen: (String, String?) -> Unit) : RecyclerView.ViewHolder(itemView) {
    private val name = itemView.findViewById<TextView>(R.id.text_stack_name)
    private val services = itemView.findViewById<TextView>(R.id.text_services_count)
    private val chipRunning = itemView.findViewById<Chip>(R.id.chip_running)
    private val chipStopped = itemView.findViewById<Chip>(R.id.chip_stopped)
    fun bind(item: StackRow) {
        name.text = item.name
        services.text = item.servicesCount.toString()
        chipRunning.text = "Running ${item.running}"
        chipStopped.text = "Stopped ${item.stopped}"
        itemView.setOnClickListener { onOpen(item.name, null) }
        chipRunning.setOnClickListener { onOpen(item.name, "running") }
        chipStopped.setOnClickListener { onOpen(item.name, "stopped") }
    }
}


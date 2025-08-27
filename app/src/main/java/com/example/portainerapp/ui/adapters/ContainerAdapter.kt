package com.example.portainerapp.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.portainerapp.R
import com.example.portainerapp.network.ContainerSummary
import com.google.android.material.chip.Chip

class ContainerAdapter(
    private val onClick: (ContainerSummary) -> Unit
) : RecyclerView.Adapter<ContainerVH>() {
    private val items = mutableListOf<ContainerSummary>()
    fun submit(list: List<ContainerSummary>) { items.clear(); items.addAll(list); notifyDataSetChanged() }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContainerVH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_container, parent, false)
        return ContainerVH(v, onClick)
    }
    override fun getItemCount(): Int = items.size
    override fun onBindViewHolder(holder: ContainerVH, position: Int) = holder.bind(items[position])
}

class ContainerVH(itemView: View, private val onClick: (ContainerSummary) -> Unit) : RecyclerView.ViewHolder(itemView) {
    private val name = itemView.findViewById<TextView>(R.id.text_container_name)
    private val image = itemView.findViewById<TextView>(R.id.text_container_image)
    private val stateChip = itemView.findViewById<Chip>(R.id.chip_container_state)
    fun bind(item: ContainerSummary) {
        val firstName = item.Names?.firstOrNull() ?: item.Id.take(12)
        name.text = firstName
        image.text = item.Image ?: ""
        val st = (item.State ?: "").lowercase()
        stateChip.text = st.ifEmpty { "unknown" }
        val color = when (st) {
            "running" -> android.graphics.Color.parseColor("#00C853")
            "paused" -> android.graphics.Color.parseColor("#FFC107")
            "exited", "dead" -> android.graphics.Color.parseColor("#D32F2F")
            else -> android.graphics.Color.GRAY
        }
        stateChip.chipBackgroundColor = android.content.res.ColorStateList.valueOf(color)
        stateChip.setTextColor(android.graphics.Color.BLACK)
        itemView.setOnClickListener { onClick(item) }
    }
}

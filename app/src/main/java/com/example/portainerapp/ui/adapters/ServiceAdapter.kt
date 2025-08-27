package com.example.portainerapp.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.portainerapp.R
import com.google.android.material.chip.Chip

data class ServiceWithCounts(
    val id: String?,
    val name: String,
    val total: Int,
    val running: Int,
    val stopped: Int
)

class ServiceAdapter(
    private val onOpen: (ServiceWithCounts) -> Unit,
    private val onOpenFiltered: (ServiceWithCounts, String) -> Unit,
    private val onEnterSelection: () -> Unit,
    private val onSelectionChanged: (Int) -> Unit
) : RecyclerView.Adapter<ServiceVH>() {
    private val items = mutableListOf<ServiceWithCounts>()
    private val selected = mutableSetOf<String>()
    var selectionEnabled: Boolean = false
    fun submit(list: List<ServiceWithCounts>) { items.clear(); items.addAll(list); notifyDataSetChanged() }
    fun toggleSelection(id: String) {
        if (selected.contains(id)) selected.remove(id) else selected.add(id)
        onSelectionChanged(selected.size)
        notifyDataSetChanged()
    }
    fun clearSelection() { selected.clear(); selectionEnabled = false; onSelectionChanged(0); notifyDataSetChanged() }
    fun selectAll() { selected.clear(); selected.addAll(items.mapNotNull { it.id }); onSelectionChanged(selected.size); notifyDataSetChanged() }
    fun selectedIds(): List<String> = selected.toList()
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ServiceVH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_service, parent, false)
        return ServiceVH(v, this, onOpen, onOpenFiltered, onEnterSelection)
    }
    override fun getItemCount(): Int = items.size
    override fun onBindViewHolder(holder: ServiceVH, position: Int) = holder.bind(items[position], selectionEnabled, selected.contains(items[position].id))
}

class ServiceVH(
    itemView: View,
    private val adapter: ServiceAdapter,
    private val onOpen: (ServiceWithCounts) -> Unit,
    private val onOpenFiltered: (ServiceWithCounts, String) -> Unit,
    private val onEnterSelection: () -> Unit
) : RecyclerView.ViewHolder(itemView) {
    private val name = itemView.findViewById<TextView>(R.id.text_service_name)
    private val total = itemView.findViewById<TextView>(R.id.text_total)
    private val chipRunning = itemView.findViewById<Chip>(R.id.chip_running)
    private val chipStopped = itemView.findViewById<Chip>(R.id.chip_stopped)
    private val checkbox = itemView.findViewById<android.widget.CheckBox>(R.id.check_select)

    fun bind(item: ServiceWithCounts, selectionEnabled: Boolean, isSelected: Boolean) {
        name.text = item.name
        total.text = item.total.toString()
        chipRunning.text = "Running ${item.running}"
        chipStopped.text = "Stopped ${item.stopped}"

        // Selection UI
        checkbox.visibility = if (selectionEnabled) View.VISIBLE else View.GONE
        checkbox.isChecked = isSelected

        itemView.setOnLongClickListener {
            if (!adapter.selectionEnabled) {
                adapter.selectionEnabled = true
                onEnterSelection()
            }
            adapter.toggleSelection(item.id ?: item.name)
            true
        }
        itemView.setOnClickListener {
            if (adapter.selectionEnabled) {
                adapter.toggleSelection(item.id ?: item.name)
            } else {
                onOpen(item)
            }
        }
        chipRunning.setOnClickListener { onOpenFiltered(item, "running") }
        chipStopped.setOnClickListener { onOpenFiltered(item, "stopped") }
    }
}

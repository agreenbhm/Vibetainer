package com.agreenbhm.vibetainer.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.agreenbhm.vibetainer.R
import com.agreenbhm.vibetainer.network.ContainerSummary
import com.google.android.material.chip.Chip

class ContainerAdapter(
    private val onClick: (ContainerSummary) -> Unit,
    val subtitleProvider: ((ContainerSummary) -> String?)? = null,
    private val onEnterSelection: (() -> Unit)? = null,
    private val onSelectionChanged: ((Int) -> Unit)? = null
) : RecyclerView.Adapter<ContainerVH>() {
    private val items = mutableListOf<ContainerSummary>()
    private val selected = mutableSetOf<String>()
    var selectionEnabled: Boolean = false
    fun submit(list: List<ContainerSummary>) { items.clear(); items.addAll(list); notifyDataSetChanged() }
    fun toggleSelection(id: String) {
        if (selected.contains(id)) selected.remove(id) else selected.add(id)
        onSelectionChanged?.invoke(selected.size)
        notifyDataSetChanged()
    }
    fun clearSelection() { selected.clear(); selectionEnabled = false; onSelectionChanged?.invoke(0); notifyDataSetChanged() }
    fun selectAll(predicate: (ContainerSummary) -> Boolean) {
        selected.clear(); selected.addAll(items.filter(predicate).map { it.Id }); onSelectionChanged?.invoke(selected.size); notifyDataSetChanged()
    }
    fun selectedIds(): List<String> = selected.toList()
    fun getItem(position: Int): ContainerSummary = items[position]
    fun notifyItemUnswiped(position: Int) { notifyItemChanged(position) }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContainerVH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_container, parent, false)
        return ContainerVH(v, this, onClick, subtitleProvider, onEnterSelection)
    }
    override fun getItemCount(): Int = items.size
    override fun onBindViewHolder(holder: ContainerVH, position: Int) = holder.bind(items[position], selectionEnabled, selected.contains(items[position].Id))
}

class ContainerVH(
    itemView: View,
    private val adapter: ContainerAdapter,
    private val onClick: (ContainerSummary) -> Unit,
    private val subtitleProvider: ((ContainerSummary) -> String?)? = null,
    private val onEnterSelection: (() -> Unit)? = null
) : RecyclerView.ViewHolder(itemView) {
    private val name = itemView.findViewById<TextView>(R.id.text_container_name)
    private val affinity = itemView.findViewById<TextView>(R.id.text_container_affinity)
    private val stateChip = itemView.findViewById<Chip>(R.id.chip_container_state)
    private val checkbox = itemView.findViewById<android.widget.CheckBox>(R.id.check_select_container)
    fun bind(item: ContainerSummary, selectionEnabled: Boolean, isSelected: Boolean) {
        val firstName = item.Names?.firstOrNull() ?: item.Id.take(12)
        name.text = firstName.removePrefix("/")
        val subtitle = subtitleProvider?.invoke(item)
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
        checkbox.visibility = if (selectionEnabled) View.VISIBLE else View.GONE
        checkbox.isChecked = isSelected
        // Show service/stack if present
        val svc = item.Labels?.get("com.docker.swarm.service.name").orEmpty()
        val stack = item.Labels?.get("com.docker.stack.namespace").orEmpty()
        affinity.text = buildString {
            if ((subtitle ?: prettyImage(item.Image)).isNotBlank()) append("Image: ").append(subtitle ?: prettyImage(item.Image))
            if ((subtitle ?: prettyImage(item.Image)).isNotBlank() && stack.isNotBlank()) append(" • ")
            if (svc.isNotBlank()) append("Service: ").append(svc)
            if (svc.isNotBlank() && stack.isNotBlank()) append(" • ")
            if (stack.isNotBlank()) append("Stack: ").append(stack)
        }
        affinity.visibility = if (affinity.text.isNullOrBlank()) View.GONE else View.VISIBLE
        itemView.setOnLongClickListener {
            if (!adapter.selectionEnabled) {
                adapter.selectionEnabled = true
                onEnterSelection?.invoke()
            }
            adapter.toggleSelection(item.Id)
            true
        }
        itemView.setOnClickListener {
            if (adapter.selectionEnabled) {
                adapter.toggleSelection(item.Id)
            } else {
                onClick(item)
            }
        }
    }
}

private fun prettyImage(img: String?): String {
    if (img.isNullOrBlank()) return ""
    // Hide SHA256-only identifiers
    if (img.startsWith("sha256:") || img.matches(Regex("[a-f0-9]{64}"))) return ""
    // Trim digest suffix if present (name@sha256:...)
    val at = img.indexOf('@')
    return if (at > 0) img.substring(0, at) else img
}

package com.agreenbhm.vibetainer.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.agreenbhm.vibetainer.R
import com.agreenbhm.vibetainer.network.Volume
import com.google.android.material.chip.Chip

data class VolumeListItem(val volume: Volume, val unused: Boolean? = null)

class VolumeItemAdapter(
    private val onSelectionChanged: (Int) -> Unit,
    private val onStartSelectionMode: (VolumeListItem) -> Unit,
    private val onItemClick: ((VolumeListItem) -> Unit)? = null
) : ListAdapter<VolumeListItem, VolumeItemAdapter.VH>(DIFF) {
    private val selected = mutableSetOf<String>()
    private var selectionMode = false

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<VolumeListItem>() {
            override fun areItemsTheSame(oldItem: VolumeListItem, newItem: VolumeListItem): Boolean {
                return oldItem.volume.Name == newItem.volume.Name && oldItem.volume.nodeName == newItem.volume.nodeName
            }

            override fun areContentsTheSame(oldItem: VolumeListItem, newItem: VolumeListItem): Boolean {
                return oldItem == newItem
            }
        }
    }

    private fun keyOf(v: Volume): String = (v.Name ?: "") + "@" + (v.nodeName ?: "")

    fun getSelectedItems(): List<VolumeListItem> = currentList.filter { selected.contains(keyOf(it.volume)) }

    fun clearSelection() {
        selected.clear()
        selectionMode = false
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    fun enterSelectionModeAndSelect(item: VolumeListItem) {
        selectionMode = true
        selected.add(keyOf(item.volume))
        notifyDataSetChanged()
        onSelectionChanged(selected.size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_volume_entry, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val vol = item.volume
        holder.title.text = vol.Name ?: "<none>"
        holder.node.text = vol.nodeName?.let { "node: $it" } ?: "node: -"
        val isUnused = item.unused ?: vol.isUnused
        holder.unusedChip.visibility = if (isUnused) View.VISIBLE else View.GONE

        holder.checkbox.visibility = if (selectionMode) View.VISIBLE else View.GONE
        val key = keyOf(vol)
        holder.checkbox.setOnCheckedChangeListener(null)
        holder.checkbox.isChecked = selected.contains(key)
        holder.checkbox.setOnCheckedChangeListener { _, checked ->
            if (checked) selected.add(key) else selected.remove(key)
            onSelectionChanged(selected.size)
            if (selected.isEmpty()) {
                selectionMode = false
                notifyDataSetChanged()
            }
        }

        holder.itemView.setOnClickListener {
            if (selectionMode) {
                holder.checkbox.isChecked = !holder.checkbox.isChecked
            } else {
                onItemClick?.invoke(item)
            }
        }

        holder.itemView.setOnLongClickListener { _ ->
            if (!selectionMode) onStartSelectionMode(VolumeListItem(vol))
            true
        }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val checkbox: CheckBox = v.findViewById(R.id.check_select)
        val title: TextView = v.findViewById(R.id.text_title)
        val node: TextView = v.findViewById(R.id.text_node)
        val unusedChip: Chip = v.findViewById(R.id.chip_unused)
    }
}

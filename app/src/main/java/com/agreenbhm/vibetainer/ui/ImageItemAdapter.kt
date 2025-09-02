package com.agreenbhm.vibetainer.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import com.google.android.material.chip.Chip
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.agreenbhm.vibetainer.R
import com.agreenbhm.vibetainer.network.EnvironmentImage

data class ImageListItem(
    val image: EnvironmentImage
)

class ImageItemAdapter(
    private val onSelectionChanged: (Int) -> Unit,
    private val onStartSelectionMode: (ImageListItem) -> Unit
) : ListAdapter<ImageListItem, ImageItemAdapter.VH>(DIFF) {
    private val selected = mutableSetOf<String>() // key = id@node
    private var selectionMode: Boolean = false

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<ImageListItem>() {
            override fun areItemsTheSame(oldItem: ImageListItem, newItem: ImageListItem): Boolean {
                return (oldItem.image.id == newItem.image.id) && (oldItem.image.nodeName == newItem.image.nodeName)
            }

            override fun areContentsTheSame(oldItem: ImageListItem, newItem: ImageListItem): Boolean {
                return oldItem == newItem
            }
        }
    }

    fun getSelectedItems(): List<ImageListItem> = currentList.filter { selected.contains(keyOf(it.image)) }

    fun clearSelection() {
        selected.clear()
        selectionMode = false
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    fun enterSelectionModeAndSelect(item: ImageListItem) {
        selectionMode = true
        selected.add(keyOf(item.image))
        notifyDataSetChanged()
        onSelectionChanged(selected.size)
    }

    fun exitSelectionMode() {
        selectionMode = false
        selected.clear()
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    private fun keyOf(img: EnvironmentImage): String = (img.id ?: "") + "@" + (img.nodeName ?: "")

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_image_entry, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val img = getItem(position).image
        holder.tag.text = img.tags?.firstOrNull() ?: (img.id ?: "<none>")
        holder.node.text = "node: ${img.nodeName ?: "-"}"
        holder.size.text = formatBytes(img.size ?: 0L)
        // Show "unused" chip when image is unused
        holder.unusedChip.visibility = if (img.used == false) View.VISIBLE else View.GONE

        // Checkbox visible only in selection mode
        holder.checkbox.visibility = if (selectionMode) View.VISIBLE else View.GONE

        val key = keyOf(img)
        // Avoid triggering listener during bind
        holder.checkbox.setOnCheckedChangeListener(null)
        holder.checkbox.isChecked = selected.contains(key)
        holder.checkbox.setOnCheckedChangeListener { _, checked ->
            if (checked) selected.add(key) else selected.remove(key)
            onSelectionChanged(selected.size)
            if (selected.isEmpty()) {
                // exit selection mode if nothing selected
                exitSelectionMode()
            }
        }

        holder.itemView.setOnClickListener {
            if (selectionMode) {
                holder.checkbox.isChecked = !holder.checkbox.isChecked
            } else {
                // no-op when not in selection mode
            }
        }

        holder.itemView.setOnLongClickListener { _ ->
            if (!selectionMode) {
                onStartSelectionMode(ImageListItem(img))
            }
            true
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var b = bytes.toDouble()
        var idx = 0
        while (b >= 1024 && idx < units.size - 1) {
            b /= 1024.0
            idx++
        }
        return String.format("%.1f %s", b, units[idx])
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val checkbox: CheckBox = v.findViewById(R.id.check_select)
        val tag: TextView = v.findViewById(R.id.text_tag)
        val node: TextView = v.findViewById(R.id.text_node)
        val size: TextView = v.findViewById(R.id.text_size)
        val unusedChip: Chip = v.findViewById(R.id.chip_unused)
    }
}

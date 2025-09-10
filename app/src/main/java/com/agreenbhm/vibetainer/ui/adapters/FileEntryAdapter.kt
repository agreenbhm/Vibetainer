package com.agreenbhm.vibetainer.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.agreenbhm.vibetainer.R

data class FileEntry(
    val name: String,
    val isDir: Boolean,
    val meta: String? = null
)

class FileEntryAdapter(
    private val onClick: (FileEntry) -> Unit
) : RecyclerView.Adapter<FileEntryVH>() {
    private val items = mutableListOf<FileEntry>()
    fun submit(list: List<FileEntry>) { items.clear(); items.addAll(list); notifyDataSetChanged() }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileEntryVH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_file_entry, parent, false)
        return FileEntryVH(v, onClick)
    }
    override fun getItemCount(): Int = items.size
    override fun onBindViewHolder(holder: FileEntryVH, position: Int) = holder.bind(items[position])
}

class FileEntryVH(
    itemView: View,
    private val onClick: (FileEntry) -> Unit
) : RecyclerView.ViewHolder(itemView) {
    private val icon = itemView.findViewById<ImageView>(R.id.icon)
    private val name = itemView.findViewById<TextView>(R.id.name)
    private val meta = itemView.findViewById<TextView>(R.id.meta)
    fun bind(item: FileEntry) {
        icon.setImageResource(if (item.isDir) R.drawable.ic_folder_24 else R.drawable.ic_file_24)
        name.text = item.name
        meta.text = item.meta.orEmpty()
        meta.visibility = if (item.meta.isNullOrBlank()) View.GONE else View.VISIBLE
        itemView.setOnClickListener { onClick(item) }
    }
}


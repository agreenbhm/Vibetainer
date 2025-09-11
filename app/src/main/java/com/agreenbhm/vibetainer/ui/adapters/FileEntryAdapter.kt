package com.agreenbhm.vibetainer.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.RecyclerView
import com.agreenbhm.vibetainer.R

data class FileEntry(
    val name: String,
    val isDir: Boolean,
    val meta: String? = null
)

class FileEntryAdapter(
    private val onClick: (FileEntry) -> Unit,
    private val onDownload: (FileEntry) -> Unit = {},
    private val onDetails: (FileEntry) -> Unit = {}
) : RecyclerView.Adapter<FileEntryVH>() {
    private val items = mutableListOf<FileEntry>()
    fun submit(list: List<FileEntry>) { items.clear(); items.addAll(list); notifyDataSetChanged() }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileEntryVH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_file_entry, parent, false)
        return FileEntryVH(v, onClick, onDownload, onDetails)
    }
    override fun getItemCount(): Int = items.size
    override fun onBindViewHolder(holder: FileEntryVH, position: Int) = holder.bind(items[position])
}

class FileEntryVH(
    itemView: View,
    private val onClick: (FileEntry) -> Unit,
    private val onDownload: (FileEntry) -> Unit,
    private val onDetails: (FileEntry) -> Unit
) : RecyclerView.ViewHolder(itemView) {
    private val icon = itemView.findViewById<ImageView>(R.id.icon)
    private val name = itemView.findViewById<TextView>(R.id.name)
    private val meta = itemView.findViewById<TextView>(R.id.meta)
    private val menu = itemView.findViewById<ImageView?>(R.id.menu)
    fun bind(item: FileEntry) {
        icon.setImageResource(if (item.isDir) R.drawable.ic_folder_24 else R.drawable.ic_file_24)
        name.text = item.name
        meta.text = item.meta.orEmpty()
        meta.visibility = if (item.meta.isNullOrBlank()) View.GONE else View.VISIBLE
        itemView.setOnClickListener { onClick(item) }
        itemView.setOnLongClickListener {
            if (item.name == "..") {
                onDetails(item)
            } else {
                onDownload(item)
            }
            true
        }
        menu?.apply {
            visibility = View.VISIBLE
            setOnClickListener {
                val pm = PopupMenu(itemView.context, this)
                if (item.name != "..") {
                    pm.menu.add(0, 1, 0, itemView.context.getString(R.string.action_download))
                }
                pm.menu.add(0, 2, 1, itemView.context.getString(R.string.action_details))
                pm.setOnMenuItemClickListener {
                    when (it.itemId) {
                        1 -> { onDownload(item); true }
                        2 -> { onDetails(item); true }
                        else -> false
                    }
                }
                pm.show()
            }
        }
    }
}

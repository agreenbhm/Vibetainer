package com.agreenbhm.vibetainer.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.agreenbhm.vibetainer.R
import com.agreenbhm.vibetainer.network.NetworkSummary
import com.google.android.material.chip.Chip

class NetworkAdapter(
    private val onClick: (NetworkSummary) -> Unit
) : RecyclerView.Adapter<NetworkVH>() {
    private val items = mutableListOf<NetworkSummary>()
    
    fun submit(list: List<NetworkSummary>) { 
        items.clear() 
        items.addAll(list) 
        notifyDataSetChanged() 
    }
    
    fun getItem(position: Int): NetworkSummary = items[position]
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NetworkVH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_network, parent, false)
        return NetworkVH(v, onClick)
    }
    
    override fun getItemCount(): Int = items.size
    
    override fun onBindViewHolder(holder: NetworkVH, position: Int) = holder.bind(items[position])
}

class NetworkVH(
    itemView: View,
    private val onClick: (NetworkSummary) -> Unit
) : RecyclerView.ViewHolder(itemView) {
    private val name = itemView.findViewById<TextView>(R.id.text_network_name)
    private val driver = itemView.findViewById<TextView>(R.id.text_network_driver)
    private val scopeChip = itemView.findViewById<Chip>(R.id.chip_scope)
    private val internalChip = itemView.findViewById<Chip>(R.id.chip_internal)
    private val attachableChip = itemView.findViewById<Chip>(R.id.chip_attachable)
    
    fun bind(item: NetworkSummary) {
        name.text = item.Name ?: "Unknown"
        driver.text = item.Driver ?: "unknown"
        
        // Show scope
        scopeChip.text = item.Scope ?: "local"
        
        // Show internal flag if true
        if (item.Internal == true) {
            internalChip.visibility = View.VISIBLE
            internalChip.text = "Internal"
        } else {
            internalChip.visibility = View.GONE
        }
        
        // Show attachable flag if true
        if (item.Attachable == true) {
            attachableChip.visibility = View.VISIBLE
            attachableChip.text = "Attachable"
        } else {
            attachableChip.visibility = View.GONE
        }
        
        itemView.setOnClickListener {
            onClick(item)
        }
    }
}
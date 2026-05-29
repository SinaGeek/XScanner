package com.example.xscanner.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.xscanner.databinding.ItemResultRowBinding
import com.example.xscanner.scanning.ResultItem

class ResultAdapter(
    private val items: List<ResultItem>,
    private val onCopy: (List<String>) -> Unit
) : RecyclerView.Adapter<ResultAdapter.ViewHolder>() {

    private val selectedPositions = mutableSetOf<Int>()

    inner class ViewHolder(val binding: ItemResultRowBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemResultRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.binding.tvIp.text = item.ip
        holder.binding.tvPing.text = "Ping: ${item.ping}ms"
        holder.binding.tvJitter.text = "Jitter: ${item.jitter}ms"
        holder.binding.tvLatency.text = "Latency: ${item.latency}ms"
        holder.binding.tvUpload.text = "Up: %.2f".format(item.uploadMbps)
        holder.binding.tvDownload.text = "Down: %.2f".format(item.downloadMbps)

        holder.binding.checkBox.isChecked = selectedPositions.contains(position)
        holder.binding.checkBox.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) selectedPositions.add(position) else selectedPositions.remove(position)
        }
        holder.binding.root.setOnLongClickListener {
            onCopy(getSelectedItems())
            true
        }
    }

    override fun getItemCount() = items.size

    fun getSelectedItems(): List<String> {
        return selectedPositions.mapNotNull { pos ->
            if (pos < items.size) items[pos].ip else null
        }
    }
}
package com.example.usbcam.viewmodel

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.usbcam.R

class TimeSlotAdapter : RecyclerView.Adapter<TimeSlotAdapter.ViewHolder>() {

    private val items = mutableListOf<TimeSlotItem>()

    fun submitList(data: List<TimeSlotItem>) {
        items.clear()
        items.addAll(data)
        notifyDataSetChanged()
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvIndex: TextView = view.findViewById(R.id.tv_index_recycler)
        val tvFrameTime: TextView = view.findViewById(R.id.tv_frame_time_recycler)
        val tvTarget: TextView = view.findViewById(R.id.tv_target_recycler)
        val tvQuantity: TextView = view.findViewById(R.id.tv_quantity_recycler)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view =
                LayoutInflater.from(parent.context).inflate(R.layout.item_time_slot, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvIndex.text = item.index.toString()
        holder.tvFrameTime.text = item.frameTime
        holder.tvTarget.text = item.target.toString()
        holder.tvQuantity.text = item.quantity.toString()

        // đổi màu nếu đạt target
        holder.tvQuantity.setTextColor(if (item.quantity >= item.target) Color.GREEN else Color.RED)
    }
}

// RecordAdapter.kt
package com.example.platefinderapp

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class RecordAdapter(private val records: MutableList<AppRecord>) : RecyclerView.Adapter<RecordAdapter.RecordViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecordViewHolder {
        val itemView = LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_1, parent, false)
        return RecordViewHolder(itemView)
    }

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(holder: RecordViewHolder, position: Int) {
        holder.textView.text = "${records[position].plate} ${records[position].isReported}"
    }

    override fun getItemCount(): Int = records.size

    class RecordViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val textView: TextView = itemView.findViewById(android.R.id.text1)
    }
}

package com.example.platefinderapp

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class RecordAdapter(private val records: MutableList<AppRecord>) : RecyclerView.Adapter<RecordAdapter.RecordViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecordViewHolder {
        // Inflate your custom layout (itemRecord.xml)
        val itemView = LayoutInflater.from(parent.context).inflate(R.layout.item_record, parent, false)
        return RecordViewHolder(itemView)
    }

    override fun onBindViewHolder(holder: RecordViewHolder, position: Int) {
        val record = records[position]
        holder.textView.text = "${record.plate} ${record.isReported} ${record.location}"

        if (record.image != null) {
            // Set the image if available
            holder.itemImageView.setImageBitmap(record.image)
        } else {
            // Log and show a placeholder image if the Bitmap is null
            Log.e("RecordAdapter", "Bitmap is null for record: ${record.plate}")
            //holder.itemImageView.setImageResource(R.drawable.placeholder_image) // Use a placeholder drawable
        }
    }

    override fun getItemCount(): Int = records.size

    // Update the ViewHolder to use the correct itemView
    class RecordViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val textView: TextView = itemView.findViewById(R.id.textView)
        val itemImageView: ImageView = itemView.findViewById(R.id.itemImageView)
    }
}
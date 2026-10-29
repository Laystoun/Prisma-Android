package com.example.prisma.data

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.prisma.R
import com.example.prisma.data.db.ChatDao
import com.example.prisma.data.db.MessageEntity
import com.example.prisma.data.db.MessageStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatAdapter() : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private var messages: List<MessageEntity> = emptyList()
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == 0) {
            InViewHolder(inflater.inflate(R.layout.item_message_in, parent, false))
        } else {
            OutViewHolder(inflater.inflate(R.layout.item_message_out, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val msg = messages[position]
        val time = timeFormat.format(Date(msg.createdAt))

        if (holder is InViewHolder) {
            holder.text.text = msg.body
            holder.time.text = time
        } else if (holder is OutViewHolder) {
            holder.text.text = msg.body
            holder.time.text = time
            if (msg.status == MessageStatus.SENT) {
                holder.status.visibility = View.VISIBLE
            } else {
                holder.status.visibility = View.GONE
            }
        }
    }

    override fun getItemCount(): Int {
        return messages.size
    }

    override fun getItemViewType(position: Int): Int {
        return messages[position].direction
    }

    fun submit(newMessages: List<MessageEntity>) {
        messages = newMessages
        notifyDataSetChanged()
    }

    class InViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val text = view.findViewById<TextView>(R.id.tv_msgIn_text)
        val time = view.findViewById<TextView>(R.id.tv_msgIn_time)
    }

    class OutViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val text = view.findViewById<TextView>(R.id.tv_msgOut_text)
        val time = view.findViewById<TextView>(R.id.tv_msgOut_time)
        val status = view.findViewById<ImageView>(R.id.iv_msgOut_status)
    }
}
package com.example.prisma

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.prisma.data.db.ChatRow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatsAdapter(
    private val onClick: (ChatRow) -> Unit
) : RecyclerView.Adapter<ChatsAdapter.ChatViewHolder>() {
    private var chats: List<ChatRow> = emptyList()
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun submit(newList: List<ChatRow>) {
        chats = newList
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat, parent, false)

        return ChatViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        holder.name.text = chats[position].name ?: chats[position].pubKey.take(8)
        holder.lastMessage.text = chats[position].lastBody ?: ""

        if (chats[position].unread == 0) {
            holder.unread.visibility = View.GONE
        } else {
            holder.unread.visibility = View.VISIBLE
            holder.unread.text = chats[position].unread.toString()
        }

        if (chats[position].lastBody != null) {
            holder.lastMessage.text = chats[position].lastBody
        } else {
            holder.lastMessage.text = ""
        }

        val lastTime = chats[position].lastTime
        if (lastTime != null) {
            holder.time.text = timeFormat.format(Date(lastTime))
            holder.time.visibility = View.VISIBLE
        } else {
            holder.time.visibility = View.GONE
        }

        holder.itemView.setOnClickListener {
            onClick(chats[position])
        }
    }

    override fun getItemCount(): Int {
        return chats.size
    }

    class ChatViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name = view.findViewById<TextView>(R.id.tv_itemChat_publicName)
        val lastMessage = view.findViewById<TextView>(R.id.tv_itemChat_lastMessage)
        val time = view.findViewById<TextView>(R.id.tv_itemChat_lastMessageTime)
        val unread = view.findViewById<TextView>(R.id.tv_itemChat_unread)
    }
}
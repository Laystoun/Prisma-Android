package com.example.prisma.data

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.example.prisma.R
import com.example.prisma.data.db.ChatDao
import com.example.prisma.data.db.MessageDirection
import com.example.prisma.data.db.MessageEntity
import com.example.prisma.data.db.MessageStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.core.graphics.drawable.toDrawable

class ChatAdapter(
    private val sendBtn: ImageView,
    private val onSelectionChanged: (Int) -> Unit,
    private val onDeleteMessage: (MessageEntity) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var messages: List<MessageEntity> = emptyList()
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val pendingAnimationIds = mutableSetOf<Long>()

    private val selectedMessages = mutableSetOf<Long>()

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
        val isSelected = msg.id in selectedMessages
        val time = timeFormat.format(Date(msg.createdAt))

        val bubble = when (holder) {
            is InViewHolder -> holder.bubble
            is OutViewHolder -> holder.bubble
            else -> holder.itemView
        }

        val background = when {
            isSelected -> R.drawable.bg_message_popup_selected

            holder is InViewHolder -> R.drawable.bg_bubble_in

            holder is OutViewHolder -> R.drawable.bg_bubble_out

            else -> R.drawable.bg_message_popup
        }

        bubble.setBackgroundResource(background)

        holder.itemView.setOnLongClickListener {
            toggleSelection(msg.id)
            true
        }

        holder.itemView.setOnClickListener {
            if (selectedMessages.isNotEmpty()) {
                toggleSelection(msg.id)
            }
        }

        bubble.setOnLongClickListener {
            toggleSelection(msg.id)
            true
        }

        bubble.setOnClickListener {
            if (selectedMessages.isNotEmpty()) {
                toggleSelection(msg.id)
            } else {
                showMessageMenu(bubble, msg)
            }
        }

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

            if (pendingAnimationIds.remove(msg.id)) {
                animateBubble(holder.bubble)
            }
        }
    }

    override fun getItemCount(): Int {
        return messages.size
    }

    override fun getItemViewType(position: Int): Int {
        return messages[position].direction
    }

    private fun toggleSelection(messageId: Long) {
        if (!selectedMessages.add(messageId)) {
            selectedMessages.remove(messageId)
        }

        notifyDataSetChanged()
        onSelectionChanged(selectedMessages.size)
    }

    fun submit(newMessages: List<MessageEntity>) {

        val oldMessages = messages

        val oldIds = oldMessages.map { it.id }.toSet()

        newMessages.forEach { msg ->
            if (msg.direction != MessageDirection.IN && msg.id !in oldIds) {
                pendingAnimationIds.add(msg.id)
            }
        }

        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {

            override fun getOldListSize() = oldMessages.size

            override fun getNewListSize() = newMessages.size

            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
                return oldMessages[oldPos].id == newMessages[newPos].id
            }

            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
                return oldMessages[oldPos] == newMessages[newPos]
            }
        })

        messages = newMessages
        diff.dispatchUpdatesTo(this)
    }

    class InViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val text = view.findViewById<TextView>(R.id.tv_msgIn_text)
        val time = view.findViewById<TextView>(R.id.tv_msgIn_time)
        val bubble = view.findViewById<View>(R.id.ll_msgIn_bubble)
    }

    class OutViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val bubble = view.findViewById<LinearLayout>(R.id.ll_msgOut_bubble)

        val text = view.findViewById<TextView>(R.id.tv_msgOut_text)
        val time = view.findViewById<TextView>(R.id.tv_msgOut_time)
        val status = view.findViewById<ImageView>(R.id.iv_msgOut_status)
    }

    private fun animateBubble(bubble: View) {
        val buttonLocation = IntArray(2)
        val bubbleLocation = IntArray(2)

        sendBtn.getLocationOnScreen(buttonLocation)
        bubble.getLocationOnScreen(bubbleLocation)

        val buttonCenterX = buttonLocation[0] + sendBtn.width / 2f
        val buttonCenterY = buttonLocation[1] + sendBtn.height / 2f
        val bubbleCenterX = bubbleLocation[0] + bubble.width / 2f
        val bubbleCenterY = bubbleLocation[1] + bubble.height / 2f

        val startX = buttonCenterX - bubbleCenterX
        val startY = buttonCenterY - bubbleCenterY

        bubble.alpha = 0f
        bubble.translationX = startX
        bubble.translationY = startY
        bubble.scaleX = 0.85f
        bubble.scaleY = 0.85f

        bubble.animate()
            .translationX(0f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(100)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    fun getSelectedMessages(): List<MessageEntity> {
        return messages.filter { it.id in selectedMessages }
    }

    fun clearSelection() {
        selectedMessages.clear()
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    private fun showMessageMenu(anchor: View, msg: MessageEntity) {
        val context = anchor.context

        val popupView = LayoutInflater.from(context)
            .inflate(R.layout.popup_message_menu, null)



        val popup = PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            false
        )

        popup.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        popup.isOutsideTouchable = true
        popup.elevation = 12f * context.resources.displayMetrics.density

        popupView.findViewById<View>(R.id.ll_popupMessage_containerDelete).setOnClickListener {
            popup.dismiss()
            onDeleteMessage(msg)
        }

        popup.showAsDropDown(anchor)
    }
}
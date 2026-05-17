package com.example.scorereader

import android.text.format.DateUtils
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class RecentsAdapter(
    private var items: List<RecentScore>,
    private val onOpen: (RecentScore) -> Unit,
    private val onToggleFavorite: (RecentScore) -> Unit
) : RecyclerView.Adapter<RecentsAdapter.VH>() {

    fun submit(list: List<RecentScore>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recent, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.title.text = item.displayName
        holder.subtitle.text = item.uri.toString()
        holder.favoriteIcon.text = if (item.isFavorite) "♥" else "♡"
        holder.favoriteIcon.alpha = if (item.isFavorite) 1.0f else 0.75f
        holder.timestamp.text = if (item.openedAtMs > 0) {
            DateUtils.getRelativeTimeSpanString(
                item.openedAtMs,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS
            ).toString()
        } else ""
        holder.itemView.setOnClickListener { onOpen(item) }
        holder.itemView.setOnLongClickListener {
            onToggleFavorite(item)
            true
        }
        holder.itemView.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER &&
                event.action == KeyEvent.ACTION_DOWN &&
                event.repeatCount > 0
            ) {
                onToggleFavorite(item)
                true
            } else {
                false
            }
        }
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val favoriteIcon: TextView = view.findViewById(R.id.favoriteIcon)
        val title: TextView = view.findViewById(R.id.recentTitle)
        val subtitle: TextView = view.findViewById(R.id.recentSubtitle)
        val timestamp: TextView = view.findViewById(R.id.recentTime)
    }
}

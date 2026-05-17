package com.example.scorereader

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * One row in the Online tab. `isCached`/`isFavorite` are derived from the
 * local cache + recents repo on every refresh.
 */
data class OnlineRow(
    val item: OnlineItem,
    val isCached: Boolean,
    val isFavorite: Boolean
)

/**
 * RecyclerView adapter for `OnlineRow`s shown under the "Online" tab.
 *
 *  - Click / center-key  -> `onOpen`
 *  - Long-press / center-key repeat -> `onToggleFavorite`
 *    (HomeActivity is in charge of deciding what to do when the file
 *    hasn't been cached yet).
 */
class OnlineAdapter(
    private var rows: List<OnlineRow>,
    private val onOpen: (OnlineItem) -> Unit,
    private val onToggleFavorite: (OnlineRow) -> Unit
) : RecyclerView.Adapter<OnlineAdapter.VH>() {

    fun submit(list: List<OnlineRow>) {
        rows = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recent, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = rows[position]
        val item = row.item
        val ctx = holder.itemView.context

        holder.title.text = item.title
        holder.subtitle.text = if (row.isCached) {
            ctx.getString(R.string.online_card_subtitle_cached, item.filename)
        } else {
            item.filename
        }

        // Favorite indicator: only meaningful for cached files.
        if (row.isCached) {
            holder.favoriteIcon.visibility = View.VISIBLE
            holder.favoriteIcon.text = if (row.isFavorite) "♥" else "♡"
            holder.favoriteIcon.alpha = if (row.isFavorite) 1.0f else 0.75f
        } else {
            holder.favoriteIcon.visibility = View.GONE
        }

        holder.timestamp.text = if (row.isCached) {
            ctx.getString(R.string.online_card_badge_cached)
        } else {
            formatSize(item.sizeBytes)
        }

        holder.itemView.setOnClickListener { onOpen(item) }
        holder.itemView.setOnLongClickListener {
            onToggleFavorite(row)
            true
        }
        holder.itemView.setOnKeyListener { _, keyCode, event ->
            when {
                keyCode == KeyEvent.KEYCODE_DPAD_CENTER &&
                    event.action == KeyEvent.ACTION_DOWN &&
                    event.repeatCount > 0 -> {
                    onToggleFavorite(row)
                    true
                }
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                    keyCode == KeyEvent.KEYCODE_ENTER) &&
                    event.action == KeyEvent.ACTION_UP -> {
                    onOpen(item)
                    true
                }
                else -> false
            }
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes <= 0L -> ""
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.2f MB".format(bytes / 1024.0 / 1024.0)
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val favoriteIcon: TextView = view.findViewById(R.id.favoriteIcon)
        val title: TextView = view.findViewById(R.id.recentTitle)
        val subtitle: TextView = view.findViewById(R.id.recentSubtitle)
        val timestamp: TextView = view.findViewById(R.id.recentTime)
    }
}


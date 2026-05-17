package com.example.scorereader

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * RecyclerView adapter for the top-level Online tab — one card per group.
 *
 * Reuses `item_recent.xml` so the visual treatment stays consistent with
 * the Recent / Favorite / scores-in-group lists:
 *  - **title**  = group title (server-supplied or user-entered)
 *  - **subtitle** = description if any, else the host portion of the URL
 *  - **timestamp** = "Local" / "Server" tag (and score count if known)
 *  - **favorite icon** = repurposed as a tiny indicator showing whether
 *    this entry is server-provided (◆) or user-added (★) so the user can
 *    tell at a glance which ones can be removed.
 *
 * Click / center-key → drill into the group (load its `library.json`).
 * Long-press / center-key repeat → ask `onLongPress` (HomeActivity uses
 * this to offer "remove" for local groups and a hint for server groups).
 */
class OnlineGroupAdapter(
    private var groups: List<OnlineGroup>,
    private val onOpen: (OnlineGroup) -> Unit,
    private val onLongPress: (OnlineGroup) -> Unit
) : RecyclerView.Adapter<OnlineGroupAdapter.VH>() {

    fun submit(list: List<OnlineGroup>) {
        groups = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recent, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = groups.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val g = groups[position]
        val ctx = holder.itemView.context

        holder.title.text = g.title

        val subtitle = when {
            !g.description.isNullOrBlank() -> g.description
            else -> g.manifestUrl
        }
        holder.subtitle.text = subtitle

        holder.favoriteIcon.visibility = View.VISIBLE
        holder.favoriteIcon.text = if (g.isLocal) "★" else "◆"
        holder.favoriteIcon.alpha = if (g.isLocal) 1.0f else 0.7f

        val countLabel = g.count?.let {
            ctx.getString(R.string.online_group_score_count, it)
        }
        val tag = if (g.isLocal) {
            ctx.getString(R.string.online_group_badge_local)
        } else {
            ctx.getString(R.string.online_group_badge_server)
        }
        holder.timestamp.text = if (countLabel != null) "$tag · $countLabel" else tag

        holder.itemView.setOnClickListener { onOpen(g) }
        holder.itemView.setOnLongClickListener {
            onLongPress(g)
            true
        }
        // TV remotes don't always synthesise long-press for the d-pad
        // center key; mirror RecentsAdapter's heuristic and treat a key
        // repeat as a long press.
        holder.itemView.setOnKeyListener { _, keyCode, event ->
            when {
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) &&
                    event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0 -> {
                    onLongPress(g); true
                }
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) &&
                    event.action == KeyEvent.ACTION_UP && event.repeatCount == 0 -> {
                    onOpen(g); true
                }
                else -> false
            }
        }
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.recentTitle)
        val subtitle: TextView = view.findViewById(R.id.recentSubtitle)
        val timestamp: TextView = view.findViewById(R.id.recentTime)
        val favoriteIcon: TextView = view.findViewById(R.id.favoriteIcon)
    }
}

package org.wordpress.android.ui.stats.refresh.lists.sections.viewholders

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import org.wordpress.android.R
import org.wordpress.android.R.id
import org.wordpress.android.ui.stats.refresh.lists.sections.BlockListItem
import org.wordpress.android.ui.stats.refresh.lists.sections.BlockListItem.TitleWithMore

class TitleWithMoreViewHolder(parent: ViewGroup) : BlockListItemViewHolder(
        parent,
        R.layout.stats_block_title_with_more_item
) {
    private val title = itemView.findViewById<TextView>(id.text)
    private val viewMore = itemView.findViewById<MaterialButton>(id.view_more_button)

    fun bind(item: TitleWithMore) {
        title.multiLineText(item)
        if (item.navigationAction != null) {
            viewMore.isVisible = true
            viewMore.setOnClickListener { item.navigationAction.click() }
        } else {
            viewMore.visibility = View.GONE
        }
    }

    private fun TextView.multiLineText(item: TitleWithMore) {
        this.visibility = View.VISIBLE
        if (item.navigationAction == null) {
            this.ellipsize = null
            this.maxLines = Int.MAX_VALUE
        }
        this.setTextOrHide(item.textResource, item.text)
    }
}

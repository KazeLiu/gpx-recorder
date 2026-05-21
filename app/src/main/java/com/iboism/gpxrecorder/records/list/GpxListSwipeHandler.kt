package com.iboism.gpxrecorder.records.list

import android.graphics.Canvas
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView

class GpxListSwipeHandler(private val onCellDismissed: ((position: Int) -> Unit)): ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.RIGHT or ItemTouchHelper.LEFT) {
    override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
        return false
    }

    override fun getSwipeDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
        return when(viewHolder) {
            is RouteRowViewHolder -> super.getSwipeDirs(recyclerView, viewHolder)
            else -> 0
        }
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        (viewHolder as? RouteRowViewHolder)?.let { onCellDismissed(it.adapterPosition) }
    }

    override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
        if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && viewHolder is RouteRowViewHolder) {
            viewHolder.itemView.elevation = 0f
            viewHolder.itemView.translationZ = 0f
            return
        }

        super.onSelectedChanged(viewHolder, actionState)
    }

    override fun onChildDraw(
        c: Canvas,
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        dX: Float,
        dY: Float,
        actionState: Int,
        isCurrentlyActive: Boolean
    ) {
        if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && viewHolder is RouteRowViewHolder) {
            viewHolder.itemView.translationX = dX
            viewHolder.itemView.translationY = dY
            viewHolder.itemView.elevation = 0f
            viewHolder.itemView.translationZ = 0f
            return
        }

        super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
    }

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)
        viewHolder.itemView.elevation = 0f
        viewHolder.itemView.translationZ = 0f
    }
}

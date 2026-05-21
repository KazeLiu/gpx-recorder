package com.iboism.gpxrecorder.recording.configurator

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.iboism.gpxrecorder.R
import kotlin.math.roundToInt

class Md3DurationWheelPicker @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {
    private val hoursWheel = WheelColumn(context, 0..12, context.getString(R.string.hour_initial))
    private val minutesWheel = WheelColumn(context, 0..59, context.getString(R.string.minute_initial))
    private val secondsWheel = WheelColumn(context, 0..59, context.getString(R.string.second_initial))

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(8), dp(12), dp(8))
        addWheel(hoursWheel)
        addWheel(minutesWheel)
        addWheel(secondsWheel)
    }

    fun getIntervalMillis(): Long {
        return hoursWheel.value * 3_600_000L +
            minutesWheel.value * 60_000L +
            secondsWheel.value * 1_000L
    }

    fun setIntervalMillis(intervalMillis: Long) {
        val hours = intervalMillis / 3_600_000L
        val minutes = (intervalMillis - hours * 3_600_000L) / 60_000L
        val seconds = (intervalMillis - hours * 3_600_000L - minutes * 60_000L) / 1_000L

        hoursWheel.setValue(hours.toInt())
        minutesWheel.setValue(minutes.toInt())
        secondsWheel.setValue(seconds.toInt())
    }

    private fun addWheel(wheel: WheelColumn) {
        addView(
            wheel,
            LayoutParams(0, dp(156), 1f).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            }
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private class WheelColumn(
        context: Context,
        values: IntRange,
        private val unitLabel: String,
    ) : MaterialCardView(context) {
        private val itemHeight = dp(44)
        private val recyclerView = RecyclerView(context)
        private val adapter = WheelAdapter(values.toList(), unitLabel)
        private val layoutManager = LinearLayoutManager(context)
        private val snapHelper = LinearSnapHelper()

        var value: Int = values.first
            private set

        init {
            radius = dp(22).toFloat()
            cardElevation = 0f
            strokeWidth = 0
            setCardBackgroundColor(ContextCompat.getColor(context, R.color.md_surfaceContainer))

            val frame = FrameLayout(context)
            addView(frame, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

            val highlight = View(context).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(ContextCompat.getColor(context, R.color.md_primaryContainer))
                }
            }
            frame.addView(
                highlight,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    itemHeight,
                    Gravity.CENTER
                ).apply {
                    leftMargin = dp(8)
                    rightMargin = dp(8)
                }
            )

            recyclerView.apply {
                overScrollMode = View.OVER_SCROLL_NEVER
                clipToPadding = false
                this.layoutManager = this@WheelColumn.layoutManager
                adapter = this@WheelColumn.adapter
            }
            frame.addView(recyclerView, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            snapHelper.attachToRecyclerView(recyclerView)

            recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) updateSelectedValue()
                }
            })
        }

        fun setValue(newValue: Int) {
            val position = adapter.positionOf(newValue)
            if (position < 0) return

            value = newValue
            adapter.selectedPosition = position
            recyclerView.post {
                alignSelectedItem(position)
            }
        }

        private fun alignSelectedItem(position: Int) {
            if (recyclerView.height <= 0) {
                recyclerView.post { alignSelectedItem(position) }
                return
            }

            val centerPadding = ((recyclerView.height - itemHeight) / 2).coerceAtLeast(0)
            if (recyclerView.paddingTop != centerPadding || recyclerView.paddingBottom != centerPadding) {
                recyclerView.setPadding(0, centerPadding, 0, centerPadding)
            }
            layoutManager.scrollToPositionWithOffset(position, 0)
        }

        private fun updateSelectedValue() {
            val snappedView = snapHelper.findSnapView(layoutManager) ?: return
            val position = layoutManager.getPosition(snappedView)
            value = adapter.valueAt(position)
            adapter.selectedPosition = position
        }

        private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
    }

    private class WheelAdapter(
        private val values: List<Int>,
        private val unitLabel: String,
    ) : RecyclerView.Adapter<WheelAdapter.ViewHolder>() {
        var selectedPosition: Int = 0
            set(value) {
                val oldValue = field
                field = value
                notifyItemChanged(oldValue)
                notifyItemChanged(value)
            }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val textView = TextView(parent.context).apply {
                gravity = Gravity.CENTER
                includeFontPadding = false
            }
            return ViewHolder(textView)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val isSelected = position == selectedPosition
            holder.textView.apply {
                text = context.getString(R.string.interval_wheel_value, values[position], unitLabel)
                textSize = if (isSelected) 21f else 16f
                typeface = Typeface.DEFAULT_BOLD.takeIf { isSelected } ?: Typeface.DEFAULT
                alpha = if (isSelected) 1f else 0.48f
                setTextColor(
                    ContextCompat.getColor(
                        context,
                        if (isSelected) R.color.md_onPrimaryContainer else R.color.md_onSurfaceVariant
                    )
                )
            }
        }

        override fun getItemCount(): Int = values.size

        fun positionOf(value: Int): Int = values.indexOf(value.coerceIn(values.first(), values.last()))

        fun valueAt(position: Int): Int = values[position.coerceIn(values.indices)]

        class ViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView) {
            init {
                textView.layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    (44 * textView.resources.displayMetrics.density).roundToInt()
                )
            }
        }
    }
}

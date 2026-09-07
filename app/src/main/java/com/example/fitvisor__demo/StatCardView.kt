package com.example.fitvisor__demo

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * Reusable compact statistic card: a large value over a small label on a dark
 * elevated rounded surface. Used for the workout-summary metrics.
 */
class StatCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val valueView: TextView
    private val labelView: TextView

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        LayoutInflater.from(context).inflate(R.layout.view_stat_card, this, true)

        valueView = findViewById(R.id.statValue)
        labelView = findViewById(R.id.statLabel)

        background = ContextCompat.getDrawable(context, R.drawable.bg_stat_card)
        val pad = dp(16)
        setPadding(pad, pad, pad, pad)

        attrs?.let { readAttributes(it) }
    }

    private fun readAttributes(attrs: AttributeSet) {
        val a = context.obtainStyledAttributes(attrs, R.styleable.StatCardView)
        try {
            a.getString(R.styleable.StatCardView_statValue)?.let { valueView.text = it }
            a.getString(R.styleable.StatCardView_statLabel)?.let { labelView.text = it }
            if (a.hasValue(R.styleable.StatCardView_statValueColor)) {
                valueView.setTextColor(a.getColor(R.styleable.StatCardView_statValueColor, 0))
            }
        } finally {
            a.recycle()
        }
    }

    fun setValue(value: String) {
        valueView.text = value
    }

    fun setLabel(label: String) {
        labelView.text = label
    }

    fun setValueColor(color: Int) {
        valueView.setTextColor(color)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}

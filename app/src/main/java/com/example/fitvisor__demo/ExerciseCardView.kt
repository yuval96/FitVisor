package com.example.fitvisor__demo

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * Reusable exercise-selection card: icon + title + subtitle + chevron on a dark
 * rounded surface with a turquoise border and a pressed-state ripple.
 *
 * Configure via XML (app:cardTitle / app:cardSubtitle / app:cardIcon /
 * app:cardIconTint) or the [setContent] helper. The whole card is one clickable,
 * accessible element.
 */
class ExerciseCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val iconView: ImageView
    private val titleView: TextView
    private val subtitleView: TextView

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_exercise_card, this, true)

        iconView = findViewById(R.id.exerciseIcon)
        titleView = findViewById(R.id.exerciseTitle)
        subtitleView = findViewById(R.id.exerciseSubtitle)

        background = ContextCompat.getDrawable(context, R.drawable.bg_exercise_card)
        isClickable = true
        isFocusable = true
        minimumHeight = dp(88)
        val padH = dp(18)
        val padV = dp(14)
        setPadding(padH, padV, padH, padV)

        // Exercise artwork contains its own brand colours, so preserve it by
        // default. A monochrome icon can still opt into cardIconTint in XML.
        iconView.imageTintList = null

        attrs?.let { readAttributes(it) }
    }

    private fun readAttributes(attrs: AttributeSet) {
        val a = context.obtainStyledAttributes(attrs, R.styleable.ExerciseCardView)
        try {
            a.getString(R.styleable.ExerciseCardView_cardTitle)?.let { titleView.text = it }
            a.getString(R.styleable.ExerciseCardView_cardSubtitle)?.let { subtitleView.text = it }
            val iconRes = a.getResourceId(R.styleable.ExerciseCardView_cardIcon, 0)
            if (iconRes != 0) iconView.setImageResource(iconRes)
            if (a.hasValue(R.styleable.ExerciseCardView_cardIconTint)) {
                val tint = a.getColor(R.styleable.ExerciseCardView_cardIconTint, 0)
                iconView.imageTintList = ColorStateList.valueOf(tint)
            }
        } finally {
            a.recycle()
        }
        updateAccessibility()
    }

    /** Sets the card content programmatically. */
    fun setContent(title: String, subtitle: String) {
        titleView.text = title
        subtitleView.text = subtitle
        updateAccessibility()
    }

    private fun updateAccessibility() {
        contentDescription = "${titleView.text}. ${subtitleView.text}"
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}

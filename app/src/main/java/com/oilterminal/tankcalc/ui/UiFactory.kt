package com.oilterminal.tankcalc.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

object UiFactory {
    const val NAVY = 0xFF12304A.toInt()
    const val BLUE = 0xFF1769AA.toInt()
    const val BACKGROUND = 0xFFF3F6F8.toInt()
    const val TEXT = 0xFF17212B.toInt()
    const val MUTED = 0xFF5D6A75.toInt()
    const val BORDER = 0xFFD5DEE5.toInt()

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    fun vertical(context: Context, padding: Int = 16): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(context, padding),
                dp(context, padding),
                dp(context, padding),
                dp(context, padding)
            )
        }

    fun title(context: Context, text: String, size: Float = 25f): TextView =
        TextView(context).apply {
            this.text = text
            textSize = size
            setTextColor(TEXT)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(context, 6), 0, dp(context, 10))
        }

    fun section(context: Context, text: String): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 18f
            setTextColor(TEXT)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(context, 14), 0, dp(context, 8))
        }

    fun body(context: Context, text: String, size: Float = 15f): TextView =
        TextView(context).apply {
            this.text = text
            textSize = size
            setTextColor(TEXT)
            setLineSpacing(0f, 1.2f)
            setPadding(0, dp(context, 4), 0, dp(context, 6))
        }

    fun muted(context: Context, text: String): TextView =
        body(context, text, 13f).apply { setTextColor(MUTED) }

    fun button(context: Context, text: String, primary: Boolean = true): Button =
        Button(context).apply {
            this.text = text
            isAllCaps = false
            textSize = 16f
            minHeight = dp(context, 52)
            setTextColor(if (primary) Color.WHITE else BLUE)
            backgroundTintList = ColorStateList.valueOf(
                if (primary) BLUE else 0xFFE5EEF5.toInt()
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(context, 6)
                bottomMargin = dp(context, 6)
            }
        }

    fun editText(
        context: Context,
        hint: String,
        numeric: Boolean = false
    ): EditText =
        EditText(context).apply {
            this.hint = hint
            textSize = 17f
            setPadding(
                dp(context, 12),
                dp(context, 10),
                dp(context, 12),
                dp(context, 10)
            )
            inputType = if (numeric) {
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            } else {
                InputType.TYPE_CLASS_TEXT
            }
            background = roundedDrawable(Color.WHITE, BORDER, context, 8)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(context, 52)
            ).apply {
                topMargin = dp(context, 4)
                bottomMargin = dp(context, 8)
            }
        }

    fun card(context: Context): LinearLayout =
        vertical(context, 14).apply {
            background = roundedDrawable(Color.WHITE, BORDER, context, 10)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(context, 7)
                bottomMargin = dp(context, 7)
            }
        }

    fun topBar(
        context: Context,
        title: String,
        showBack: Boolean,
        onBack: () -> Unit
    ): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                dp(context, 8),
                dp(context, 8),
                dp(context, 14),
                dp(context, 8)
            )
            setBackgroundColor(NAVY)
            if (showBack) {
                addView(Button(context).apply {
                    text = "‹ 返回"
                    isAllCaps = false
                    setTextColor(Color.WHITE)
                    backgroundTintList = ColorStateList.valueOf(NAVY)
                    setOnClickListener { onBack() }
                })
            }
            addView(TextView(context).apply {
                text = title
                textSize = 20f
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    dp(context, 52),
                    1f
                )
            })
        }

    fun roundedDrawable(
        fill: Int,
        stroke: Int,
        context: Context,
        radiusDp: Int
    ): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            setStroke(dp(context, 1), stroke)
            cornerRadius = dp(context, radiusDp).toFloat()
        }
}

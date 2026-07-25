package com.yubegreen.luonnotar.ui.visual

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import kotlin.math.min

class AdaptiveMaxWidthLinearLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {
    var maximumWidthPx: Int = Int.MAX_VALUE
        set(value) {
            field = value.coerceAtLeast(1)
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = View.MeasureSpec.getMode(widthMeasureSpec)
        if (maximumWidthPx == Int.MAX_VALUE || widthMode == View.MeasureSpec.UNSPECIFIED) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val cappedWidth = min(View.MeasureSpec.getSize(widthMeasureSpec), maximumWidthPx)
        super.onMeasure(
            View.MeasureSpec.makeMeasureSpec(cappedWidth, widthMode),
            heightMeasureSpec
        )
    }
}

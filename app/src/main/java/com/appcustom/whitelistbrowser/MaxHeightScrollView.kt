package com.appcustom.whitelistbrowser

import android.content.Context
import android.graphics.Rect
import android.widget.ScrollView

/**
 * A dialog's scrolling content that never grows so tall that it pushes the dialog's buttons (Send,
 * Cancel) off the screen. Android lets a dialog's content do that on small screens, even when the
 * content can scroll. This measures the space actually visible (smaller while the keyboard is up)
 * and leaves [reservedDp] for the dialog's title, buttons and margins.
 */
class MaxHeightScrollView(context: Context, private val reservedDp: Int = 170) : ScrollView(context) {
    init { isFillViewport = true }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val frame = Rect()
        getWindowVisibleDisplayFrame(frame)
        val density = resources.displayMetrics.density
        val visible = if (frame.height() > 0) frame.height() else resources.displayMetrics.heightPixels
        val max = maxOf((visible - reservedDp * density).toInt(), (48 * density).toInt())
        val size = MeasureSpec.getSize(heightMeasureSpec)
        val limit = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) max else minOf(size, max)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST))
    }
}

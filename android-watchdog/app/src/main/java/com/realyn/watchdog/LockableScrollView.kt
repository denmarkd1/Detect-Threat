package com.realyn.watchdog

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.ScrollView

class LockableScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ScrollView(context, attrs) {

    var scrollEnabled: Boolean = false

    override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean {
        return if (scrollEnabled) {
            super.onInterceptTouchEvent(ev)
        } else {
            false
        }
    }

    override fun onTouchEvent(ev: MotionEvent?): Boolean {
        return if (scrollEnabled) {
            super.onTouchEvent(ev)
        } else {
            false
        }
    }
}

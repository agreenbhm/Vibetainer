package com.agreenbhm.vibetainer.ui.chart

import android.content.Context
import android.widget.TextView
import com.agreenbhm.vibetainer.R
import com.github.mikephil.charting.components.MarkerView
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.utils.MPPointF

class ValueMarker(context: Context) : MarkerView(context, R.layout.marker_view) {
    private val tv: TextView = findViewById(R.id.marker_text)
    private val offsetPt by lazy { MPPointF(-(width / 2f), -height.toFloat()) }

    override fun refreshContent(e: Entry?, highlight: Highlight?) {
        if (e != null) {
            tv.text = String.format("%.2f", e.y)
        }
        super.refreshContent(e, highlight)
    }

    override fun getOffset(): MPPointF = offsetPt
}

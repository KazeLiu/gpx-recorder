package com.iboism.gpxrecorder.rescue

import android.view.View
import android.widget.AdapterView

class SimpleSelectionListener(
    private val onSelected: (Int) -> Unit
) : AdapterView.OnItemSelectedListener {
    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
        onSelected(position)
    }

    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
}

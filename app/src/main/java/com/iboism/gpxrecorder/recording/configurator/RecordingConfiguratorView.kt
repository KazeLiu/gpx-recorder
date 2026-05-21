package com.iboism.gpxrecorder.recording.configurator

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import com.iboism.gpxrecorder.R

/**
 * Created by brad on 5/8/18.
 */

private const val DRAFT_INTERVAL_KEY = "RecordingConfiguratorView_draftInterval"
private const val DRAFT_TITLE_KEY = "RecordingConfiguratorView_draftTitle"

class RecordingConfiguratorView(
    root: View,
    initialIntervalMillis: Long,
    val titleEditText: EditText = root.findViewById(R.id.config_title_editText),
    val doneButton: TextView = root.findViewById(R.id.start_button),
    private val screenTitle: TextView = root.findViewById(R.id.title),
    private val intervalPicker: Md3DurationWheelPicker = root.findViewById(R.id.interval_picker),
    val routeTitle: String? = null,
    val isTitleEditable: Boolean = true,
    val isResumingRoute: Boolean = false,
    val screenTitleTextRes: Int? = null,
    val doneButtonTextRes: Int? = null
) {

    init {
        routeTitle?.let {
            titleEditText.setText(it)
        }

        if (screenTitleTextRes != null || doneButtonTextRes != null) {
            screenTitleTextRes?.let { screenTitle.text = root.context.getText(it) }
            doneButtonTextRes?.let { doneButton.text = root.context.getText(it) }
        } else if (isResumingRoute) {
            screenTitle.text = root.context.getText(R.string.resume_recording)
            doneButton.text = root.context.getText(R.string.resume_recording)
        } else {
            doneButton.text = root.context.getText(R.string.start)
            screenTitle.text = root.context.getText(R.string.new_recording)
        }

        titleEditText.isEnabled = isTitleEditable
        setInterval(initialIntervalMillis)
    }

    fun onSaveInstanceState(outState: Bundle) {
        outState.putLong(DRAFT_INTERVAL_KEY, getIntervalMillis())
        outState.putString(DRAFT_TITLE_KEY, titleEditText.text.toString())
    }

    fun restoreInstanceState(outState: Bundle?) {
        outState?.getLong(DRAFT_INTERVAL_KEY)?.let { draftInterval ->
            setInterval(draftInterval)
            outState.remove(DRAFT_INTERVAL_KEY)
        }

        outState?.getString(DRAFT_TITLE_KEY)?.let { draftTitle ->
            titleEditText.text.clear()
            titleEditText.text.append(draftTitle)
            outState.remove(DRAFT_TITLE_KEY)
        }
    }

    fun getIntervalMillis(): Long {
        return intervalPicker.getIntervalMillis()
    }

    private fun setInterval(intervalMillis: Long) {
        intervalPicker.setIntervalMillis(0L)
        intervalPicker.post {
            intervalPicker.setIntervalMillis(intervalMillis)
        }
    }
}

package com.iboism.gpxrecorder.records.list

import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.iboism.gpxrecorder.R
import com.iboism.gpxrecorder.databinding.ListRowActiveRouteBinding

class ActiveRouteRowViewHolder(binding: ListRowActiveRouteBinding): RecyclerView.ViewHolder(binding.root) {
    val rootView = binding.root
    val headerText: TextView = binding.currentRecHeader
    val routeTitle: TextView = binding.routeTitleTv
    val statsText: TextView = binding.recordingStatsTv
    val playPauseButton: MaterialButton = binding.playpauseBtn
    val stopButton: MaterialButton = binding.stopBtn

    fun setPaused(isPaused: Boolean) {
        playPauseButton.text = if (isPaused) rootView.context.getText(R.string.resume_recording) else rootView.context.getText(R.string.pause_recording)
        playPauseButton.setIconResource(if (isPaused) R.drawable.ic_play else R.drawable.ic_pause)
        headerText.text = rootView.context.getText(
            if (isPaused) R.string.notification_recording_paused else R.string.recording_in_progress
        )
    }
}

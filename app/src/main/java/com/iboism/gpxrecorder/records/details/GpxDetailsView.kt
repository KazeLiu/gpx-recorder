package com.iboism.gpxrecorder.records.details

import android.os.Bundle
import android.view.View
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.iboism.gpxrecorder.R
import com.iboism.gpxrecorder.databinding.FragmentRouteDetailsBinding
import com.iboism.gpxrecorder.extensions.getThemeColor
import io.reactivex.subjects.PublishSubject

private const val DRAFT_TITLE_KEY: String = "GpxDetailsView_titleDraft"
private const val DRAFT_DATE_AM_PM_KEY: String = "GpxDetailsView_dateAmPm"

class GpxDetailsView(
    val binding: FragmentRouteDetailsBinding,
    val titleText: String,
    val distanceText: String,
    val trackPointsText: String,
    val dateText24Hour: String,
    val dateTextAmPm: String
) {

    private var savedText = ""
    private var isEditingTitle = false
    private var isShowingAmPmDate = false
    private var isEditingTrackPoints = false

    var saveTouchObservable: PublishSubject<Unit> = PublishSubject.create()
    var shareTouchObservable: PublishSubject<Unit> = PublishSubject.create()
    var gpxTitleObservable: PublishSubject<String> = PublishSubject.create()
    var mapTypeToggleObservable: PublishSubject<Unit> = PublishSubject.create()
    var deleteRouteObservable: PublishSubject<Unit> = PublishSubject.create()
    var resumeRecordingObservable: PublishSubject<Unit> = PublishSubject.create()
    var trackPointEditToggleObservable: PublishSubject<Boolean> = PublishSubject.create()

    init {
        setTitleReadOnly()
        binding.titleEt.append(titleText)
        binding.distanceTv.visibility = View.GONE
        binding.trackPointCountTv.text = "$trackPointsText · $distanceText"
        binding.dateTv.text = dateText24Hour

        binding.resumeBtn.setOnClickListener { resumePressed() }
        binding.moreBtn.setOnClickListener { morePressed() }
        binding.addWptBtn.text = binding.root.context.getString(R.string.edit_track_points)
        binding.addWptBtn.setOnClickListener { trackPointEditToggleObservable.onNext(!isEditingTrackPoints) }
        binding.dateTv.setOnClickListener { toggleDateFormat() }
    }

    fun restoreInstanceState(outState: Bundle?) {
        outState?.getBoolean(DRAFT_DATE_AM_PM_KEY)?.let { draftDateAmPm ->
            isShowingAmPmDate = draftDateAmPm
            updateDateText()
            outState.remove(DRAFT_DATE_AM_PM_KEY)
        }

        val titleDraft = outState?.getString(DRAFT_TITLE_KEY) ?: return

        editPressed()
        binding.titleEt.setText(titleDraft)
        outState.remove(DRAFT_TITLE_KEY)
    }

    fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(DRAFT_DATE_AM_PM_KEY, isShowingAmPmDate)
        if (isEditingTitle) {
            outState.putString(DRAFT_TITLE_KEY, binding.titleEt.text.toString())
        }
    }

    fun setRouteStats(trackPointsText: String, distanceText: String) {
        binding.trackPointCountTv.text = "$trackPointsText · $distanceText"
    }

    fun setTrackPointEditingEnabled(isEnabled: Boolean) {
        isEditingTrackPoints = isEnabled
        binding.addWptBtn.text = binding.root.context.getString(
            if (isEditingTrackPoints) R.string.finish_editing else R.string.edit_track_points
        )
    }

    private fun setTitleReadOnly() {
        isEditingTitle = false
        binding.titleEt.isEnabled = true
        binding.titleEt.isFocusable = false
        binding.titleEt.isFocusableInTouchMode = false
        binding.titleEt.isCursorVisible = false
        binding.titleEt.clearFocus()
        binding.titleEt.setBackgroundColor(binding.root.context.getThemeColor(R.attr.gpxNavBarSurface))
        binding.titleEt.setHorizontallyScrolling(false)
    }

    private fun toggleDateFormat() {
        isShowingAmPmDate = !isShowingAmPmDate
        updateDateText()
    }

    private fun updateDateText() {
        binding.dateTv.text = if (isShowingAmPmDate) dateTextAmPm else dateText24Hour
    }

    private fun editPressed() {
        isEditingTitle = true
        binding.titleEt.isEnabled = true
        binding.titleEt.isFocusable = true
        binding.titleEt.isFocusableInTouchMode = true
        binding.titleEt.isCursorVisible = true
        binding.titleEt.requestFocusFromTouch()
        binding.titleEt.setBackgroundResource(R.drawable.rect_rounded_light_accent)
        savedText = binding.titleEt.text.toString()
        binding.addWptBtn.isEnabled = false
        binding.resumeBtn.setOnClickListener { applyPressed() }
        binding.resumeBtn.setImageResource(R.drawable.ic_check)
        binding.moreBtn.setOnClickListener { cancelPressed() }
        binding.moreBtn.setImageResource(R.drawable.ic_close)
    }

    private fun deletePressed() {
        MaterialAlertDialogBuilder(binding.root.context)
            .setTitle(R.string.delete_recording_alert_title)
            .setMessage(R.string.delete_recording_alert_message)
            .setCancelable(true)
            .setPositiveButton(R.string.delete) { _, _ ->
                deleteRouteObservable.onNext(Unit)
            }.create().show()
    }

    private fun sharePressed() {
        shareTouchObservable.onNext(Unit)
    }

    private fun savePressed() {
        saveTouchObservable.onNext(Unit)
    }

    private fun morePressed() {
        val context = binding.root.context
        val items = arrayOf(
            context.getString(R.string.save),
            context.getString(R.string.share),
            context.getString(R.string.toggle_map_type),
            context.getString(R.string.delete_route),
            context.getString(R.string.rename_route)
        )

        MaterialAlertDialogBuilder(context)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> savePressed()
                    1 -> sharePressed()
                    2 -> mapTypeToggleObservable.onNext(Unit)
                    3 -> deletePressed()
                    4 -> editPressed()
                }
            }
            .show()
    }

    private fun applyPressed() {
        setTitleReadOnly()
        binding.resumeBtn.setOnClickListener { resumePressed() }
        binding.resumeBtn.setImageResource(R.drawable.ic_near_me)
        binding.moreBtn.setOnClickListener { morePressed() }
        binding.moreBtn.setImageResource(R.drawable.ic_more)
        binding.addWptBtn.isEnabled = true
        gpxTitleObservable.onNext(binding.titleEt.text.toString())
    }

    private fun cancelPressed() {
        setTitleReadOnly()
        binding.titleEt.setText("")
        binding.titleEt.append(savedText)
        binding.resumeBtn.setOnClickListener { resumePressed() }
        binding.resumeBtn.setImageResource(R.drawable.ic_near_me)
        binding.moreBtn.setOnClickListener { morePressed() }
        binding.moreBtn.setImageResource(R.drawable.ic_more)
        binding.addWptBtn.isEnabled = true
    }

    private fun resumePressed() {
        MaterialAlertDialogBuilder(binding.root.context)
            .setTitle(R.string.resume_recording_alert_title)
            .setMessage(R.string.resume_recording_alert_message)
            .setCancelable(true)
            .setPositiveButton(R.string.continue_recording) { _, _ ->
                resumeRecordingObservable.onNext(Unit)
            }.create().show()
    }

    fun setButtonsExporting(isExporting: Boolean) {
        binding.resumeBtn.isEnabled = !isExporting
        binding.moreBtn.isEnabled = !isExporting
        binding.moreBtn.visibility = if (isExporting) View.INVISIBLE else View.VISIBLE
        binding.exportProgressBar.visibility = if (isExporting) View.VISIBLE else View.GONE
    }
}


package com.iboism.gpxrecorder.rescue

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.NumberPicker
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.iboism.gpxrecorder.Keys
import com.iboism.gpxrecorder.R
import com.iboism.gpxrecorder.databinding.FragmentRescueTrackBinding
import com.iboism.gpxrecorder.model.RescueAnchor
import com.iboism.gpxrecorder.model.RescueAnchorSource
import com.iboism.gpxrecorder.model.RescueDraftStep
import com.iboism.gpxrecorder.model.RescuePlanningStatus
import com.iboism.gpxrecorder.model.RescueTrackDraft
import com.iboism.gpxrecorder.model.RescueTravelMode
import com.iboism.gpxrecorder.util.DateTimeFormatHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.min

class RescueTrackFragment : Fragment(), AmapRescueTrackMapController.Listener {
    private lateinit var binding: FragmentRescueTrackBinding
    private lateinit var anchorAdapter: AnchorAdapter
    private lateinit var waypointAdapter: WaypointAdapter
    private var mapController: AmapRescueTrackMapController? = null
    private var draftId: Long = 0L
    private var currentSegmentIndex = 0
    private var isRendering = false
    private var routePlanningJob: Job? = null
    private var pendingRouteRefreshJob: Job? = null
    private var routePlanningVersion = 0
    private val routePlanner: AmapRoutePlanner by lazy {
        AmapRoutePlanner(getString(R.string.amap_web_api_key))
    }
    private val mediaPicker = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        importMedia(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        draftId = arguments?.getLong(ARG_DRAFT_ID)?.takeIf { it != 0L } ?: 0L
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentRescueTrackBinding.inflate(inflater, container, false)
        val toolbarInitialTopPadding = binding.rescueToolbar.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(binding.rescueRoot) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.rescueToolbar.setPadding(
                binding.rescueToolbar.paddingLeft,
                toolbarInitialTopPadding + systemBars.top,
                binding.rescueToolbar.paddingRight,
                binding.rescueToolbar.paddingBottom
            )
            view.setPadding(0, 0, 0, systemBars.bottom)
            insets
        }
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ensureDraft()
        configureControls()
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            if (navigateToPreviousSegment()) return@addCallback
            isEnabled = false
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        mapController = AmapRescueTrackMapController(binding.rescueMapContainer, this)
        mapController?.onCreate(savedInstanceState)
        render()
    }

    override fun onMapTapped(lat: Double, lon: Double) {
        val draft = RescueDraftRepository.copyDraft(draftId) ?: return
        when (RescueDraftStep.valueOf(draft.currentStep)) {
            RescueDraftStep.DRAWING -> addAnchorFromMap(lat, lon)
            RescueDraftStep.PLANNING -> addWaypointToCurrentSegment(lat, lon)
            RescueDraftStep.GENERATED -> Unit
        }
    }

    override fun onPoiTapped(name: String, lat: Double, lon: Double) {
        showMessage(getString(R.string.rescue_poi_selected, name))
        onMapTapped(lat, lon)
    }

    private fun addAnchorFromMap(lat: Double, lon: Double) {
        val draft = RescueDraftRepository.copyDraft(draftId) ?: return
        if (draft.currentStep != RescueDraftStep.DRAWING.name) return
        if (draft.anchors.isEmpty()) {
            pickDateTime(Date(), R.string.rescue_pick_start_time) { time ->
                if (time != null) {
                    runDrawingGeometryMutation {
                        RescueDraftRepository.addManualAnchor(draftId, lat, lon, time)
                    }
                }
            }
        } else {
            runDrawingGeometryMutation {
                RescueDraftRepository.addManualAnchor(draftId, lat, lon, null)
            }
        }
    }

    private fun addWaypointToCurrentSegment(lat: Double, lon: Double) {
        val segment = currentSegment() ?: return
        val added = RescueDraftRepository.addSegmentWaypoint(draftId, segment.identifier, lat, lon)
        showMessage(
            if (added) {
                getString(R.string.rescue_waypoint_added)
            } else {
                getString(R.string.rescue_waypoint_limit)
            }
        )
        if (added) {
            scheduleRouteRefreshForWaypointChange()
        } else {
            render()
        }
    }

    override fun onAnchorTapped(anchorId: Long) {
        showAnchorDialog(anchorId)
    }

    override fun onAnchorDragged(anchorId: Long, lat: Double, lon: Double) {
        runDrawingGeometryMutation(
            onCancel = { render() }
        ) {
            RescueDraftRepository.updateAnchorPosition(draftId, anchorId, lat, lon)
        }
    }

    override fun onStart() {
        super.onStart()
        mapController?.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapController?.onResume()
    }

    override fun onPause() {
        pendingRouteRefreshJob?.cancel()
        routePlanningJob?.cancel()
        mapController?.onPause()
        super.onPause()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapController?.onLowMemory()
    }

    override fun onDestroyView() {
        mapController?.onDestroy()
        mapController = null
        super.onDestroyView()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        mapController?.onSaveInstanceState(outState)
        outState.putLong(ARG_DRAFT_ID, draftId)
        super.onSaveInstanceState(outState)
    }

    private fun ensureDraft() {
        if (draftId != 0L) return
        draftId = RescueDraftRepository.latestActiveDraftId() ?: RescueDraftRepository.createDraft()
    }

    private fun configureControls() {
        configureAnchorList()
        configureWaypointList()
        binding.rescueBackButton.setOnClickListener {
            if (!navigateToPreviousSegment()) parentFragmentManager.popBackStack()
        }
        binding.rescueTitleInput.doAfterTextChanged {
            RescueDraftRepository.setTitle(draftId, it?.toString().orEmpty())
        }
        binding.rescueSearchButton.setOnClickListener { searchPlace() }
        binding.rescueImportMediaButton.setOnClickListener {
            mediaPicker.launch("*/*")
        }
        binding.rescueAnchorSortButton.setOnClickListener { showAnchorSortDialog() }
        binding.rescueWaypointSortButton.setOnClickListener { showWaypointSortDialog() }
        binding.rescueNextButton.setOnClickListener { nextPressed() }
        binding.rescueGenerateButton.setOnClickListener { generatePreview() }
        binding.rescuePreviousStepButton.setOnClickListener { previousStepPressed() }
        binding.rescuePreviousSegmentButton.setOnClickListener { navigateToPreviousSegment() }
        binding.rescueSaveButton.setOnClickListener { completeDraft() }

        binding.rescueModeSpinner.adapter = ArrayAdapter(
            requireContext(),
            R.layout.spinner_item_compact,
            listOf("直线", "驾车", "步行", "骑行")
        ).apply {
            setDropDownViewResource(R.layout.spinner_dropdown_item_compact)
        }
        binding.rescueModeSpinner.setOnItemSelectedListener(SimpleSelectionListener { position ->
            if (isRendering) return@SimpleSelectionListener
            val segment = currentSegment() ?: return@SimpleSelectionListener
            RescueDraftRepository.updateSegmentMode(draftId, segment.identifier, modeAt(position))
            refreshCurrentSegmentRoute()
        })
        binding.rescueIntervalSpinner.adapter = ArrayAdapter(
            requireContext(),
            R.layout.spinner_item_compact,
            listOf("1 秒", "10 秒", "30 秒", "60 秒", "90 秒", "120 秒")
        ).apply {
            setDropDownViewResource(R.layout.spinner_dropdown_item_compact)
        }
        binding.rescueIntervalSpinner.setSelection(1, false)
    }

    private fun configureAnchorList() {
        anchorAdapter = AnchorAdapter(
            onAnchorClick = { anchor -> mapController?.focusOn(anchor.lat, anchor.lon) }
        )
        binding.rescueAnchorList.layoutManager = LinearLayoutManager(requireContext())
        binding.rescueAnchorList.itemAnimator = null
        binding.rescueAnchorList.adapter = anchorAdapter
        keepParentFromStealingListScroll(binding.rescueAnchorList)
    }

    private fun configureWaypointList() {
        waypointAdapter = WaypointAdapter(
            onWaypointClick = { waypoint -> mapController?.focusOnWaypoint(waypoint.lat, waypoint.lon) },
            onDelete = { segmentId, index ->
                RescueDraftRepository.removeSegmentWaypoint(draftId, segmentId, index)
                scheduleRouteRefreshForWaypointChange()
            }
        )
        binding.rescueWaypointList.layoutManager = LinearLayoutManager(requireContext())
        binding.rescueWaypointList.itemAnimator = null
        binding.rescueWaypointList.adapter = waypointAdapter
        keepParentFromStealingListScroll(binding.rescueWaypointList)
    }

    private fun keepParentFromStealingListScroll(list: RecyclerView) {
        list.setOnTouchListener { view, event ->
            val canScroll = view.canScrollVertically(1) || view.canScrollVertically(-1)
            val lockParent = canScroll && event.actionMasked != MotionEvent.ACTION_UP &&
                event.actionMasked != MotionEvent.ACTION_CANCEL
            view.parent.requestDisallowInterceptTouchEvent(lockParent)
            false
        }
    }

    private fun render() {
        val draft = RescueDraftRepository.copyDraft(draftId) ?: return
        isRendering = true
        if (binding.rescueTitleInput.text?.toString() != draft.title) {
            binding.rescueTitleInput.setText(draft.title)
            binding.rescueTitleInput.setSelection(binding.rescueTitleInput.text?.length ?: 0)
        }
        val step = RescueDraftStep.valueOf(draft.currentStep)
        binding.rescueStepText.text = when (step) {
            RescueDraftStep.DRAWING -> getString(R.string.rescue_step_drawing)
            RescueDraftStep.PLANNING -> getString(R.string.rescue_step_planning)
            RescueDraftStep.GENERATED -> getString(R.string.rescue_step_generated)
        }
        binding.rescueStatusText.text = when (step) {
            RescueDraftStep.DRAWING -> getString(R.string.rescue_hint_drawing)
            RescueDraftStep.PLANNING -> getString(R.string.rescue_hint_planning)
            RescueDraftStep.GENERATED -> getString(R.string.rescue_hint_generated)
        }
        binding.rescuePreviousStepButton.visibility = if (step == RescueDraftStep.DRAWING) View.GONE else View.VISIBLE
        binding.rescuePreviousStepButton.text = when (step) {
            RescueDraftStep.DRAWING -> getString(R.string.rescue_previous_step)
            RescueDraftStep.PLANNING -> getString(R.string.rescue_back_to_drawing)
            RescueDraftStep.GENERATED -> getString(R.string.rescue_back_to_planning)
        }
        binding.rescueTitleInputLayout.visibility = if (step == RescueDraftStep.GENERATED) View.VISIBLE else View.GONE
        binding.rescueAnchorSummary.text = anchorSummary(draft)
        binding.rescueAnchorSortButton.visibility =
            if (step == RescueDraftStep.DRAWING && draft.anchors.size > 1) View.VISIBLE else View.GONE
        val anchors = draft.anchors.filterNotNull().sortedBy { it.order }
        anchorAdapter.submit(anchors)
        binding.rescueAnchorList.visibility =
            if (step == RescueDraftStep.DRAWING && anchors.isNotEmpty()) View.VISIBLE else View.GONE
        setBoundedListHeight(binding.rescueAnchorList, anchors.size)
        binding.rescueSegmentSummary.text = segmentSummary(draft)
        binding.rescueSegmentSummary.visibility = if (step == RescueDraftStep.PLANNING) View.GONE else View.VISIBLE
        binding.rescueImportMediaButton.visibility = if (step == RescueDraftStep.DRAWING) View.VISIBLE else View.GONE
        binding.rescueSearchRow.visibility = if (step == RescueDraftStep.DRAWING) View.VISIBLE else View.GONE
        binding.rescuePlanningRouteRow.visibility = if (step == RescueDraftStep.PLANNING) View.VISIBLE else View.GONE
        binding.rescueCandidateGroup.visibility = if (step == RescueDraftStep.PLANNING) View.VISIBLE else View.GONE
        binding.rescueWaypointSummaryRow.visibility = if (step == RescueDraftStep.PLANNING) View.VISIBLE else View.GONE
        binding.rescueWaypointList.visibility = if (step == RescueDraftStep.PLANNING) View.VISIBLE else View.GONE
        binding.rescueGenerationRow.visibility = if (step == RescueDraftStep.GENERATED) View.VISIBLE else View.GONE
        val hasPreviousSegment = step == RescueDraftStep.PLANNING && currentSegmentIndex > 0
        val canUseNextButton = step == RescueDraftStep.DRAWING || step == RescueDraftStep.PLANNING
        binding.rescueSegmentNavigationRow.visibility =
            if (canUseNextButton || hasPreviousSegment) View.VISIBLE else View.GONE
        binding.rescuePreviousSegmentButton.visibility = if (hasPreviousSegment) View.VISIBLE else View.GONE
        binding.rescueSaveButton.visibility =
            if (step == RescueDraftStep.GENERATED && draft.generatedPreviewPoints.size >= 2) View.VISIBLE else View.GONE
        binding.rescueNextButton.visibility = if (canUseNextButton) View.VISIBLE else View.GONE
        setNextButtonStartMargin(if (hasPreviousSegment) 8 else 0)
        binding.rescueNextButton.text = nextButtonText(draft, step)
        configurePlanningControls(draft)
        isRendering = false
        val activeSegmentIndex = if (step == RescueDraftStep.PLANNING) currentSegmentIndex else -1
        mapController?.render(draft, activeSegmentIndex)
    }

    private fun setNextButtonStartMargin(marginDp: Int) {
        val params = binding.rescueNextButton.layoutParams as ViewGroup.MarginLayoutParams
        val marginPx = (marginDp * resources.displayMetrics.density).toInt()
        if (params.marginStart == marginPx) return
        params.marginStart = marginPx
        binding.rescueNextButton.layoutParams = params
    }

    private fun configurePlanningControls(draft: RescueTrackDraft) {
        val segment = draft.segments.filterNotNull().sortedBy { it.order }.getOrNull(currentSegmentIndex)
        binding.rescueCandidateGroup.removeAllViews()
        if (segment == null) {
            waypointAdapter.submit(0L, emptyList())
            setBoundedListHeight(binding.rescueWaypointList, 0)
            binding.rescueWaypointSummary.text = getString(R.string.rescue_waypoint_summary, 0)
            binding.rescueWaypointSortButton.visibility = View.GONE
            binding.rescuePlanningSegmentText.text = "暂无路径"
            return
        }
        binding.rescueModeSpinner.setSelection(modeIndex(RescueTravelMode.valueOf(segment.travelMode)), false)
        binding.rescuePlanningSegmentText.text = planningSegmentText(draft, segment)
        val waypoints = RescueDraftRepository.waypointsFromJson(segment.waypointsJson)
        binding.rescueWaypointSummary.text = getString(R.string.rescue_waypoint_summary, waypoints.size)
        binding.rescueWaypointSortButton.visibility = if (waypoints.size > 1) View.VISIBLE else View.GONE
        renderWaypointRows(segment.identifier, waypoints)
        routePlanner.candidatesFromJson(segment.routeCandidatesJson).forEachIndexed { index, candidate ->
            val button = RadioButton(requireContext()).apply {
                text = "${candidate.title} · ${candidate.distanceMeters} 米 · ${candidate.durationSeconds / 60} 分钟"
                id = View.generateViewId()
                isChecked = index == segment.selectedRouteIndex
                minHeight = (40 * resources.displayMetrics.density).toInt()
                textSize = 14f
                setPadding(0, 0, 0, 0)
                setOnClickListener {
                    RescueDraftRepository.saveRouteCandidates(
                        draftId,
                        segment.identifier,
                        segment.routeCandidatesJson,
                        candidate.polyline,
                        index
                    )
                    render()
                    RescueDraftRepository.copyDraft(draftId)?.let { mapController?.focusOnSegment(it, currentSegmentIndex) }
                }
            }
            binding.rescueCandidateGroup.addView(button)
        }
    }

    private fun renderWaypointRows(segmentId: Long, waypoints: List<RescueRouteWaypoint>) {
        waypointAdapter.submit(segmentId, waypoints)
        setBoundedListHeight(binding.rescueWaypointList, waypoints.size)
    }

    private fun setBoundedListHeight(list: RecyclerView, itemCount: Int) {
        val params = list.layoutParams
        val targetHeight = if (itemCount > MAX_INLINE_LIST_VISIBLE_ITEMS) {
            (INLINE_LIST_MAX_ITEMS_HEIGHT * resources.displayMetrics.density).toInt()
        } else {
            ViewGroup.LayoutParams.WRAP_CONTENT
        }
        if (params.height == targetHeight) return
        params.height = targetHeight
        list.layoutParams = params
    }

    private fun navigateToPreviousSegment(): Boolean {
        val draft = RescueDraftRepository.copyDraft(draftId) ?: return false
        if (RescueDraftStep.valueOf(draft.currentStep) != RescueDraftStep.PLANNING) return false
        if (currentSegmentIndex <= 0) return false
        currentSegmentIndex -= 1
        render()
        return true
    }

    private fun previousStepPressed() {
        val draft = RescueDraftRepository.copyDraft(draftId) ?: return
        when (RescueDraftStep.valueOf(draft.currentStep)) {
            RescueDraftStep.DRAWING -> Unit
            RescueDraftStep.PLANNING -> {
                currentSegmentIndex = 0
                RescueDraftRepository.setStep(draftId, RescueDraftStep.DRAWING)
                render()
            }
            RescueDraftStep.GENERATED -> {
                currentSegmentIndex = draft.segments.size.minus(1).coerceAtLeast(0)
                RescueDraftRepository.setStep(draftId, RescueDraftStep.PLANNING)
                render()
            }
        }
    }

    private fun nextPressed() {
        val draft = RescueDraftRepository.copyDraft(draftId) ?: return
        when (RescueDraftStep.valueOf(draft.currentStep)) {
            RescueDraftStep.DRAWING -> finishDrawing(draft)
            RescueDraftStep.PLANNING -> {
                val segmentCount = draft.segments.size
                if (currentSegmentIndex < segmentCount - 1) {
                    currentSegmentIndex += 1
                    render()
                } else {
                    RescueDraftRepository.setStep(draftId, RescueDraftStep.GENERATED)
                    render()
                }
            }
            RescueDraftStep.GENERATED -> Unit
        }
    }

    private fun finishDrawing(draft: RescueTrackDraft) {
        val anchors = RescueDraftRepository.anchorsForGeneration(draft)
        if (anchors.size < 2) {
            showMessage("至少需要两个锚点。")
            return
        }
        val last = draft.anchors.filterNotNull().maxByOrNull { it.order } ?: return
        if (last.time == null) {
            pickDateTime(Date(), R.string.rescue_pick_end_time) { time ->
                if (time == null) return@pickDateTime
                RescueDraftRepository.updateAnchorTime(draftId, last.identifier, time)
                preparePlanningIfValid()
            }
            return
        }
        preparePlanningIfValid()
    }

    private fun preparePlanningIfValid() {
        val draft = RescueDraftRepository.copyDraft(draftId) ?: return
        val error = RescueTrackGenerator.validateAnchors(RescueDraftRepository.anchorsForGeneration(draft))
        if (error != null) {
            showMessage(error)
            return
        }
        currentSegmentIndex = 0
        RescueDraftRepository.preparePlanning(draftId)
        render()
    }

    private fun planCurrentSegment() {
        val draft = RescueDraftRepository.copyDraft(draftId) ?: return
        val segment = currentSegment(draft) ?: return
        val mode = RescueTravelMode.valueOf(segment.travelMode)
        if (mode == RescueTravelMode.STRAIGHT) {
            RescueDraftRepository.updateSegmentMode(draftId, segment.identifier, RescueTravelMode.STRAIGHT)
            render()
            RescueDraftRepository.copyDraft(draftId)?.let { mapController?.focusOnSegment(it, currentSegmentIndex) }
            return
        }
        val anchors = draft.anchors.filterNotNull().associateBy { it.identifier }
        val from = anchors[segment.fromAnchorId] ?: return
        val to = anchors[segment.toAnchorId] ?: return
        val waypoints = RescueDraftRepository.waypointsFromJson(segment.waypointsJson)
        val planningSegmentIndex = currentSegmentIndex
        val requestVersion = ++routePlanningVersion
        routePlanningJob?.cancel()
        render()
        showPlanningInProgress(draft, planningSegmentIndex)
        routePlanningJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { routePlanner.planRoute(mode, from.lat, from.lon, to.lat, to.lon, waypoints) }
            }
            if (requestVersion != routePlanningVersion) return@launch
            result.onSuccess { candidates ->
                if (candidates.isEmpty()) {
                    RescueDraftRepository.markSegmentPlanningFailed(draftId, segment.identifier, getString(R.string.rescue_route_planning_failed))
                    showMessage(getString(R.string.rescue_route_planning_failed))
                } else {
                    RescueDraftRepository.saveRouteCandidates(
                        draftId,
                        segment.identifier,
                        routePlanner.candidatesToJson(candidates),
                        candidates.first().polyline,
                        0
                    )
                }
                render()
                if (currentSegmentIndex == planningSegmentIndex) {
                    RescueDraftRepository.copyDraft(draftId)?.let { mapController?.focusOnSegment(it, currentSegmentIndex) }
                }
            }.onFailure {
                val message = amapErrorMessage(it, getString(R.string.rescue_route_planning_failed))
                RescueDraftRepository.markSegmentPlanningFailed(draftId, segment.identifier, message)
                showMessage(message)
                render()
            }
        }
    }

    private fun refreshCurrentSegmentRoute() {
        pendingRouteRefreshJob?.cancel()
        val segment = currentSegment() ?: return
        if (RescueTravelMode.valueOf(segment.travelMode) == RescueTravelMode.STRAIGHT) {
            routePlanningVersion += 1
            routePlanningJob?.cancel()
            render()
            RescueDraftRepository.copyDraft(draftId)?.let { mapController?.focusOnSegment(it, currentSegmentIndex) }
            return
        }
        planCurrentSegment()
    }

    private fun scheduleRouteRefreshForWaypointChange() {
        pendingRouteRefreshJob?.cancel()
        render()
        val draft = RescueDraftRepository.copyDraft(draftId) ?: return
        val segment = currentSegment(draft) ?: return
        if (RescueTravelMode.valueOf(segment.travelMode) != RescueTravelMode.STRAIGHT) {
            showPlanningInProgress(draft, currentSegmentIndex)
        }
        pendingRouteRefreshJob = lifecycleScope.launch {
            delay(WAYPOINT_ROUTE_REFRESH_DEBOUNCE_MS)
            refreshCurrentSegmentRoute()
        }
    }

    private fun showPlanningInProgress(draft: RescueTrackDraft, segmentIndex: Int) {
        binding.rescuePlanningSegmentText.text =
            "路径 ${segmentIndex + 1}/${draft.segments.size} · 规划中"
    }

    private fun runDrawingGeometryMutation(
        onCancel: () -> Unit = { render() },
        mutation: () -> Unit
    ) {
        val draft = RescueDraftRepository.copyDraft(draftId) ?: return
        if (!hasDownstreamPlanningData(draft)) {
            mutation()
            render()
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.rescue_clear_planning_title)
            .setMessage(R.string.rescue_clear_planning_message)
            .setPositiveButton(R.string.rescue_clear_planning_confirm) { _, _ ->
                mutation()
                RescueDraftRepository.clearDownstreamPlanningData(draftId)
                currentSegmentIndex = 0
                render()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .show()
    }

    private fun hasDownstreamPlanningData(draft: RescueTrackDraft): Boolean {
        return draft.segments.isNotEmpty() || draft.generatedPreviewPoints.isNotEmpty()
    }

    private fun showAnchorSortDialog() {
        val draft = RescueDraftRepository.copyDraft(draftId) ?: return
        val items = draft.anchors.filterNotNull().sortedBy { it.order }.map { anchor ->
            SortItem(
                id = anchor.identifier,
                primary = anchorSortTime(anchor.time, RescueAnchorSource.valueOf(anchor.source)),
                secondary = "%.6f, %.6f".format(anchor.lat, anchor.lon)
            )
        }
        if (items.size <= 1) return
        if (hasDownstreamPlanningData(draft)) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.rescue_clear_planning_title)
                .setMessage(R.string.rescue_clear_planning_message)
                .setPositiveButton(R.string.rescue_clear_planning_confirm) { _, _ ->
                    RescueDraftRepository.clearDownstreamPlanningData(draftId)
                    showAnchorSortDialog()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        var changed = false
        showSortDialog(
            title = getString(R.string.rescue_sort_anchors_title),
            items = items,
            onOrderChanged = { sorted ->
                changed = true
                RescueDraftRepository.setAnchorOrder(draftId, sorted.map { it.id })
            },
            onClosed = {
                if (changed) {
                    currentSegmentIndex = 0
                    render()
                }
            }
        )
    }

    private fun showWaypointSortDialog() {
        val segment = currentSegment() ?: return
        val waypoints = RescueDraftRepository.waypointsFromJson(segment.waypointsJson)
        if (waypoints.size <= 1) return
        var changed = false
        showSortDialog(
            title = getString(R.string.rescue_sort_waypoints_title),
            items = waypoints.mapIndexed { index, waypoint ->
                SortItem(
                    id = index.toLong(),
                    primary = "途经点 ${index + 1}",
                    secondary = "%.6f, %.6f".format(waypoint.lat, waypoint.lon)
                )
            },
            onOrderChanged = { sorted ->
                changed = true
                val sortedWaypoints = sorted.mapNotNull { item -> waypoints.getOrNull(item.id.toInt()) }
                RescueDraftRepository.setSegmentWaypoints(draftId, segment.identifier, sortedWaypoints)
            },
            onClosed = {
                if (changed) scheduleRouteRefreshForWaypointChange()
            }
        )
    }

    private fun showSortDialog(
        title: String,
        items: List<SortItem>,
        onOrderChanged: (List<SortItem>) -> Unit,
        onClosed: () -> Unit
    ) {
        val recyclerView = RecyclerView(requireContext()).apply {
            layoutManager = LinearLayoutManager(requireContext())
            itemAnimator = null
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val adapter = SortAdapter(
            onOrderChanged = onOrderChanged,
            onMoveByNumber = { fromIndex, size ->
                showSortMoveDialog(title, fromIndex, size) { toIndex ->
                    adapterMoveHolder?.moveByNumber(fromIndex, toIndex)
                }
            }
        )
        adapterMoveHolder = adapter
        recyclerView.adapter = adapter
        val touchHelper = ItemTouchHelper(SortDragCallback(adapter)).also { it.attachToRecyclerView(recyclerView) }
        adapter.onStartDrag = { holder -> touchHelper.startDrag(holder) }
        adapter.submit(items)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setView(recyclerView)
            .setPositiveButton(android.R.string.ok, null)
            .setOnDismissListener {
                adapterMoveHolder = null
                onClosed()
            }
            .show()
    }

    private var adapterMoveHolder: SortAdapter? = null

    private fun showSortMoveDialog(title: String, fromIndex: Int, size: Int, onSelected: (Int) -> Unit) {
        val labels = Array(size) { index -> "移动到第 ${index + 1} 个" }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setItems(labels) { _, which -> onSelected(which) }
            .show()
    }

    private fun anchorSortTime(time: String?, source: RescueAnchorSource): String {
        val timeText = time?.let { DateTimeFormatHelper.toReadableString(it) }
            ?: "未设置时间"
        return if (source == RescueAnchorSource.MEDIA) {
            "媒体 · $timeText"
        } else {
            timeText
        }
    }

    private fun generatePreview() {
        val draft = RescueDraftRepository.copyDraft(draftId) ?: return
        val anchors = RescueDraftRepository.anchorsForGeneration(draft)
        val error = RescueTrackGenerator.validateAnchors(anchors)
        if (error != null) {
            showMessage(error)
            return
        }
        val notReady = draft.segments.filterNotNull().firstOrNull {
            RescuePlanningStatus.valueOf(it.planningStatus) != RescuePlanningStatus.READY
        }
        if (notReady != null) {
            showMessage("还有分段没有完成规划。")
            return
        }
        val interval = intervalSeconds()
        val result = RescueTrackGenerator.generate(anchors, RescueDraftRepository.segmentsForGeneration(draft), interval)
        RescueDraftRepository.saveGeneratedPreview(draftId, interval, result.points)
        result.warnings.forEach { showMessage(it) }
        render()
    }

    private fun completeDraft() {
        RescueDraftRepository.setTitle(draftId, binding.rescueTitleInput.text?.toString().orEmpty())
        val gpxId = RescueDraftRepository.completeDraft(draftId)
        if (gpxId == null) {
            showMessage("预览点不足，无法保存。")
            return
        }
        showMessage(getString(R.string.rescue_status_completed))
        parentFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
    }

    private fun searchPlace() {
        val keyword = binding.rescueSearchInput.text?.toString().orEmpty()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { routePlanner.searchPlace(keyword) } }
            result.getOrNull()?.let {
                mapController?.focusOn(it.lat, it.lon)
                showMessage("已定位到 ${it.name}")
            } ?: showMessage(result.exceptionOrNull()?.let { amapErrorMessage(it, "未找到地点。") } ?: "未找到地点。")
        }
    }

    private fun importMedia(uris: List<Uri>) {
        if (uris.isEmpty()) return
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                RescueMediaImporter(requireContext().applicationContext).read(uris)
            }
            val draft = RescueDraftRepository.copyDraft(draftId) ?: return@launch
            val outOfRange = hasOutOfRangeMedia(draft, result.importable)
            if (outOfRange) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.rescue_media_out_of_range_title)
                    .setMessage(R.string.rescue_media_out_of_range_message)
                    .setPositiveButton(R.string.continue_recording) { _, _ -> saveMediaCandidates(result) }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            } else {
                saveMediaCandidates(result)
            }
        }
    }

    private fun saveMediaCandidates(result: RescueMediaImportResult) {
        if (result.importable.isEmpty()) {
            showMessage(getString(R.string.rescue_media_import_result, 0, result.rejectedCount))
            render()
            return
        }
        runDrawingGeometryMutation {
            result.importable.forEach {
                RescueDraftRepository.addMediaAnchor(
                    draftId = draftId,
                    lat = it.lat,
                    lon = it.lon,
                    time = it.time,
                    mediaUri = it.uri.toString(),
                    mediaName = it.displayName
                )
            }
            showMessage(getString(R.string.rescue_media_import_result, result.importable.size, result.rejectedCount))
        }
    }

    private fun hasOutOfRangeMedia(draft: RescueTrackDraft, media: List<RescueMediaAnchorCandidate>): Boolean {
        val anchors = draft.anchors.filterNotNull().sortedBy { it.order }
        val first = anchors.firstOrNull()?.time?.let { DateTimeFormatHelper.parseDate(it) } ?: return false
        val last = anchors.lastOrNull()?.time?.let { DateTimeFormatHelper.parseDate(it) } ?: return false
        return media.any {
            val time = DateTimeFormatHelper.parseDate(it.time) ?: return@any false
            time.before(first) || time.after(last)
        }
    }

    private fun showAnchorDialog(anchorId: Long) {
        val anchor = RescueDraftRepository.copyDraft(draftId)?.anchors?.filterNotNull()?.firstOrNull { it.identifier == anchorId } ?: return
        val initial = anchor.time?.let { DateTimeFormatHelper.parseDate(it) } ?: Date()
        val editor = createDateTimeEditorView(initial, enabled = !anchor.locked, showAnchorActions = true)
        editor.summary.text = if (anchor.locked) {
            getString(R.string.rescue_datetime_media_locked)
        } else {
            anchor.time?.let { getString(R.string.rescue_datetime_summary, DateTimeFormatHelper.toReadableString(it)) }
                ?: getString(R.string.rescue_datetime_not_set)
        }
        editor.clearButton.visibility = if (anchor.locked || anchor.time == null) View.GONE else View.VISIBLE

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.rescue_edit_anchor)
            .setView(editor.view)
            .setNegativeButton(android.R.string.cancel, null)
            .apply {
                if (!anchor.locked) setPositiveButton(R.string.rescue_save_time, null)
            }
            .show()
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            val date = editor.readDateTimeOrShowError() ?: return@setOnClickListener
            RescueDraftRepository.updateAnchorTime(draftId, anchorId, DateTimeFormatHelper.formatDate(date))
            dialog.dismiss()
            render()
        }
        editor.clearButton.setOnClickListener {
            RescueDraftRepository.updateAnchorTime(draftId, anchorId, null)
            dialog.dismiss()
            render()
        }
        editor.deleteButton.setOnClickListener {
            dialog.dismiss()
            runDrawingGeometryMutation {
                RescueDraftRepository.deleteAnchor(draftId, anchorId)
            }
        }
    }

    private fun pickDateTime(initialDate: Date, titleRes: Int, callback: (String?) -> Unit) {
        val editor = createDateTimeEditorView(initialDate, enabled = true, showAnchorActions = false)
        editor.summary.text = getString(
            R.string.rescue_datetime_summary,
            DateTimeFormatHelper.toReadableString(DateTimeFormatHelper.formatDate(initialDate))
        )
        var handled = false
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(titleRes)
            .setView(editor.view)
            .setPositiveButton(R.string.rescue_save_time, null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        dialog.setOnDismissListener {
            if (!handled) callback(null)
        }
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            val date = editor.readDateTimeOrShowError() ?: return@setOnClickListener
            handled = true
            callback(DateTimeFormatHelper.formatDate(date))
            dialog.dismiss()
        }
    }

    private fun createDateTimeEditorView(
        initialDate: Date,
        enabled: Boolean,
        showAnchorActions: Boolean
    ): DateTimeEditorView {
        val view = layoutInflater.inflate(R.layout.dialog_rescue_datetime, null)
        val editor = DateTimeEditorView(
            view = view,
            summary = view.findViewById(R.id.rescue_datetime_summary),
            yearPicker = view.findViewById(R.id.rescue_year_picker),
            monthPicker = view.findViewById(R.id.rescue_month_picker),
            dayPicker = view.findViewById(R.id.rescue_day_picker),
            hourPicker = view.findViewById(R.id.rescue_hour_picker),
            minutePicker = view.findViewById(R.id.rescue_minute_picker),
            presetGroup = view.findViewById(R.id.rescue_date_preset_group),
            yesterdayButton = view.findViewById(R.id.rescue_preset_yesterday_button),
            twoDaysButton = view.findViewById(R.id.rescue_preset_two_days_button),
            threeDaysButton = view.findViewById(R.id.rescue_preset_three_days_button),
            oneWeekButton = view.findViewById(R.id.rescue_preset_one_week_button),
            halfMonthButton = view.findViewById(R.id.rescue_preset_half_month_button),
            oneMonthButton = view.findViewById(R.id.rescue_preset_one_month_button),
            actionRow = view.findViewById(R.id.rescue_anchor_actions),
            clearButton = view.findViewById(R.id.rescue_clear_time_button),
            deleteButton = view.findViewById(R.id.rescue_delete_anchor_button)
        )
        editor.configurePickers()
        editor.setDateTime(initialDate)
        editor.setEnabled(enabled)
        editor.actionRow.visibility = if (showAnchorActions) View.VISIBLE else View.GONE
        editor.configurePresetButtons()
        return editor
    }

    private fun DateTimeEditorView.setEnabled(enabled: Boolean) {
        listOf(yearPicker, monthPicker, dayPicker, hourPicker, minutePicker).forEach {
            it.isEnabled = enabled
        }
        presetGroup.visibility = if (enabled) View.VISIBLE else View.GONE
    }

    private fun DateTimeEditorView.configurePickers() {
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        yearPicker.minValue = currentYear - 30
        yearPicker.maxValue = currentYear + 1
        monthPicker.minValue = 1
        monthPicker.maxValue = 12
        dayPicker.minValue = 1
        dayPicker.maxValue = 31
        hourPicker.minValue = 0
        hourPicker.maxValue = 23
        minutePicker.minValue = 0
        minutePicker.maxValue = 59
        monthPicker.setFormatter(twoDigitFormatter)
        dayPicker.setFormatter(twoDigitFormatter)
        hourPicker.setFormatter(twoDigitFormatter)
        minutePicker.setFormatter(twoDigitFormatter)
    }

    private fun DateTimeEditorView.setDateTime(date: Date) {
        val calendar = Calendar.getInstance().apply { time = date }
        yearPicker.value = calendar.get(Calendar.YEAR).coerceIn(yearPicker.minValue, yearPicker.maxValue)
        monthPicker.value = calendar.get(Calendar.MONTH) + 1
        dayPicker.value = calendar.get(Calendar.DAY_OF_MONTH)
        hourPicker.value = calendar.get(Calendar.HOUR_OF_DAY)
        minutePicker.value = calendar.get(Calendar.MINUTE)
    }

    private fun DateTimeEditorView.configurePresetButtons() {
        yesterdayButton.setOnClickListener { setPresetDate(daysAgo = 1) }
        twoDaysButton.setOnClickListener { setPresetDate(daysAgo = 2) }
        threeDaysButton.setOnClickListener { setPresetDate(daysAgo = 3) }
        oneWeekButton.setOnClickListener { setPresetDate(daysAgo = 7) }
        halfMonthButton.setOnClickListener { setPresetDate(daysAgo = 15) }
        oneMonthButton.setOnClickListener { setPresetDate(monthsAgo = 1) }
    }

    private fun DateTimeEditorView.setPresetDate(daysAgo: Int = 0, monthsAgo: Int = 0) {
        val calendar = Calendar.getInstance()
        if (monthsAgo != 0) calendar.add(Calendar.MONTH, -monthsAgo)
        if (daysAgo != 0) calendar.add(Calendar.DAY_OF_MONTH, -daysAgo)
        calendar.set(Calendar.HOUR_OF_DAY, 12)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        setDateTime(calendar.time)
    }

    private fun DateTimeEditorView.readDateTimeOrShowError(): Date? {
        val calendar = Calendar.getInstance().apply {
            isLenient = false
            set(Calendar.YEAR, yearPicker.value)
            set(Calendar.MONTH, monthPicker.value - 1)
            set(Calendar.DAY_OF_MONTH, dayPicker.value)
            set(Calendar.HOUR_OF_DAY, hourPicker.value)
            set(Calendar.MINUTE, minutePicker.value)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return try {
            calendar.time
        } catch (_: Exception) {
            showMessage(getString(R.string.rescue_date_time_invalid))
            null
        }
    }

    private val twoDigitFormatter = NumberPicker.Formatter { "%02d".format(it) }

    private data class DateTimeEditorView(
        val view: View,
        val summary: TextView,
        val yearPicker: NumberPicker,
        val monthPicker: NumberPicker,
        val dayPicker: NumberPicker,
        val hourPicker: NumberPicker,
        val minutePicker: NumberPicker,
        val presetGroup: View,
        val yesterdayButton: MaterialButton,
        val twoDaysButton: MaterialButton,
        val threeDaysButton: MaterialButton,
        val oneWeekButton: MaterialButton,
        val halfMonthButton: MaterialButton,
        val oneMonthButton: MaterialButton,
        val actionRow: View,
        val clearButton: MaterialButton,
        val deleteButton: MaterialButton
    )

    private fun currentSegment(draft: RescueTrackDraft? = RescueDraftRepository.copyDraft(draftId)) =
        draft?.segments?.filterNotNull()?.sortedBy { it.order }?.getOrNull(currentSegmentIndex)

    private fun anchorSummary(draft: RescueTrackDraft): String {
        val anchors = draft.anchors.filterNotNull().sortedBy { it.order }
        return "锚点 ${anchors.size} 个 · 有时间 ${anchors.count { it.time != null }} 个"
    }

    private fun segmentSummary(draft: RescueTrackDraft): String {
        val step = RescueDraftStep.valueOf(draft.currentStep)
        if (step == RescueDraftStep.DRAWING) return "完成草图后会按相邻锚点拆分路线段。"
        val segmentCount = draft.segments.size
        if (step == RescueDraftStep.GENERATED) return "已生成 ${draft.generatedPreviewPoints.size} 个预览轨迹点。"
        val segment = currentSegment(draft) ?: return "暂无路线段。"
        return "第 ${currentSegmentIndex + 1}/$segmentCount 段 · ${modeLabel(RescueTravelMode.valueOf(segment.travelMode))} · ${planningLabel(RescuePlanningStatus.valueOf(segment.planningStatus))}"
    }

    private fun planningSegmentText(draft: RescueTrackDraft, segment: com.iboism.gpxrecorder.model.RescueRouteSegment): String {
        return "路径 ${currentSegmentIndex + 1}/${draft.segments.size} · ${planningLabel(RescuePlanningStatus.valueOf(segment.planningStatus))}"
    }

    private fun nextButtonText(draft: RescueTrackDraft, step: RescueDraftStep): String {
        return when (step) {
            RescueDraftStep.DRAWING -> getString(R.string.rescue_start_planning)
            RescueDraftStep.PLANNING -> {
                if (currentSegmentIndex < draft.segments.size - 1) {
                    getString(R.string.rescue_next_segment)
                } else {
                    getString(R.string.rescue_next_step)
                }
            }
            RescueDraftStep.GENERATED -> getString(R.string.rescue_save_route)
        }
    }

    private fun intervalSeconds(): Int = when (binding.rescueIntervalSpinner.selectedItemPosition) {
        0 -> 1
        1 -> 10
        2 -> 30
        3 -> 60
        4 -> 90
        5 -> 120
        else -> 10
    }

    private fun modeAt(position: Int): RescueTravelMode = when (position) {
        1 -> RescueTravelMode.DRIVING
        2 -> RescueTravelMode.WALKING
        3 -> RescueTravelMode.RIDING
        else -> RescueTravelMode.STRAIGHT
    }

    private fun modeIndex(mode: RescueTravelMode): Int = when (mode) {
        RescueTravelMode.STRAIGHT -> 0
        RescueTravelMode.DRIVING -> 1
        RescueTravelMode.WALKING -> 2
        RescueTravelMode.RIDING -> 3
    }

    private fun modeLabel(mode: RescueTravelMode): String = when (mode) {
        RescueTravelMode.DRIVING -> "驾车"
        RescueTravelMode.WALKING -> "步行"
        RescueTravelMode.RIDING -> "骑行"
        RescueTravelMode.STRAIGHT -> "直线"
    }

    private fun planningLabel(status: RescuePlanningStatus): String = when (status) {
        RescuePlanningStatus.NOT_REQUESTED -> "未规划"
        RescuePlanningStatus.READY -> "已规划"
        RescuePlanningStatus.FAILED -> "失败"
    }

    private fun showMessage(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private fun amapErrorMessage(error: Throwable, fallback: String): String {
        return when (error) {
            is AmapServiceException -> {
                if (error.infoCode == "10009") {
                    "高德 Web 服务 Key 与当前接口不匹配，请配置 AMAP_WEB_API_KEY。"
                } else {
                    "高德服务错误 ${error.infoCode}：${error.info}"
                }
            }
            else -> error.message?.takeIf { it.isNotBlank() } ?: fallback
        }
    }

    private class AnchorAdapter(
        private val onAnchorClick: (RescueAnchor) -> Unit
    ) : RecyclerView.Adapter<AnchorAdapter.AnchorViewHolder>() {
        private val items = mutableListOf<RescueAnchor>()

        fun submit(anchors: List<RescueAnchor>) {
            items.clear()
            items.addAll(anchors)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AnchorViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.list_row_rescue_waypoint, parent, false)
            return AnchorViewHolder(view)
        }

        override fun onBindViewHolder(holder: AnchorViewHolder, position: Int) {
            val anchor = items[position]
            val source = RescueAnchorSource.valueOf(anchor.source)
            holder.dragHandle.visibility = View.GONE
            holder.deleteButton.visibility = View.GONE
            holder.index.text = (position + 1).toString()
            holder.primary.text = anchorListTime(anchor.time, source)
            holder.subtext.text = "%.6f, %.6f".format(anchor.lat, anchor.lon)
            holder.subtext.visibility = View.VISIBLE
            holder.itemView.setOnClickListener { onAnchorClick(anchor) }
        }

        override fun getItemCount(): Int = items.size

        class AnchorViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val dragHandle: View = view.findViewById(R.id.rescue_waypoint_drag_handle)
            val index: TextView = view.findViewById(R.id.rescue_waypoint_index)
            val primary: TextView = view.findViewById(R.id.rescue_waypoint_text)
            val subtext: TextView = view.findViewById(R.id.rescue_waypoint_subtext)
            val deleteButton: MaterialButton = view.findViewById(R.id.rescue_waypoint_delete_button)
        }

        private fun anchorListTime(time: String?, source: RescueAnchorSource): String {
            val timeText = time?.let { DateTimeFormatHelper.toReadableString(it) } ?: "未设置时间"
            return if (source == RescueAnchorSource.MEDIA) "媒体 · $timeText" else timeText
        }
    }

    private class WaypointAdapter(
        private val onWaypointClick: (RescueRouteWaypoint) -> Unit,
        private val onDelete: (Long, Int) -> Unit
    ) : RecyclerView.Adapter<WaypointAdapter.WaypointViewHolder>() {
        private var segmentId: Long = 0L
        private val items = mutableListOf<RescueRouteWaypoint>()

        fun submit(segmentId: Long, waypoints: List<RescueRouteWaypoint>) {
            this.segmentId = segmentId
            items.clear()
            items.addAll(waypoints)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WaypointViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.list_row_rescue_waypoint, parent, false)
            return WaypointViewHolder(view)
        }

        override fun onBindViewHolder(holder: WaypointViewHolder, position: Int) {
            val waypoint = items[position]
            holder.index.text = (position + 1).toString()
            holder.coordinate.text = "%.6f, %.6f".format(waypoint.lat, waypoint.lon)
            holder.subtext.visibility = View.GONE
            holder.dragHandle.visibility = View.GONE
            holder.itemView.setOnClickListener { onWaypointClick(waypoint) }
            holder.deleteButton.setOnClickListener {
                onDelete(segmentId, holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION } ?: position)
            }
        }

        override fun getItemCount(): Int = items.size

        class WaypointViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val dragHandle: View = view.findViewById(R.id.rescue_waypoint_drag_handle)
            val index: TextView = view.findViewById(R.id.rescue_waypoint_index)
            val coordinate: TextView = view.findViewById(R.id.rescue_waypoint_text)
            val subtext: TextView = view.findViewById(R.id.rescue_waypoint_subtext)
            val deleteButton: MaterialButton = view.findViewById(R.id.rescue_waypoint_delete_button)
        }
    }

    private data class SortItem(
        val id: Long,
        val primary: String,
        val secondary: String
    )

    private class SortAdapter(
        private val onOrderChanged: (List<SortItem>) -> Unit,
        private val onMoveByNumber: (Int, Int) -> Unit
    ) : RecyclerView.Adapter<SortAdapter.SortViewHolder>() {
        var onStartDrag: ((RecyclerView.ViewHolder) -> Unit)? = null
        private val items = mutableListOf<SortItem>()

        fun submit(newItems: List<SortItem>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SortViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.list_row_rescue_waypoint, parent, false)
            return SortViewHolder(view)
        }

        override fun onBindViewHolder(holder: SortViewHolder, position: Int) {
            holder.dragHandle.visibility = View.VISIBLE
            holder.index.text = (position + 1).toString()
            holder.coordinate.text = items[position].primary
            holder.subtext.text = items[position].secondary
            holder.subtext.visibility = View.VISIBLE
            holder.deleteButton.visibility = View.GONE
            holder.itemView.setOnClickListener(null)
            holder.dragHandle.setOnLongClickListener {
                onStartDrag?.invoke(holder)
                true
            }
            holder.index.setOnClickListener {
                val adapterPosition = holder.bindingAdapterPosition
                if (adapterPosition != RecyclerView.NO_POSITION) {
                    onMoveByNumber(adapterPosition, items.size)
                }
            }
        }

        override fun getItemCount(): Int = items.size

        fun move(fromPosition: Int, toPosition: Int): Boolean {
            if (fromPosition !in items.indices || toPosition !in items.indices || fromPosition == toPosition) {
                return false
            }
            val moved = items.removeAt(fromPosition)
            items.add(toPosition, moved)
            notifyItemMoved(fromPosition, toPosition)
            notifyItemRangeChanged(min(fromPosition, toPosition), abs(fromPosition - toPosition) + 1)
            onOrderChanged(items.toList())
            return true
        }

        fun moveByNumber(fromPosition: Int, toPosition: Int) {
            move(fromPosition, toPosition)
        }

        class SortViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val dragHandle: View = view.findViewById(R.id.rescue_waypoint_drag_handle)
            val index: TextView = view.findViewById(R.id.rescue_waypoint_index)
            val coordinate: TextView = view.findViewById(R.id.rescue_waypoint_text)
            val subtext: TextView = view.findViewById(R.id.rescue_waypoint_subtext)
            val deleteButton: MaterialButton = view.findViewById(R.id.rescue_waypoint_delete_button)
        }
    }

    private class SortDragCallback(
        private val adapter: SortAdapter
    ) : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
        override fun isLongPressDragEnabled(): Boolean = false

        override fun isItemViewSwipeEnabled(): Boolean = false

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            return adapter.move(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit
    }

    companion object {
        private const val ARG_DRAFT_ID = Keys.GpxId
        private const val WAYPOINT_ROUTE_REFRESH_DEBOUNCE_MS = 900L
        private const val MAX_INLINE_LIST_VISIBLE_ITEMS = 3
        private const val INLINE_LIST_MAX_ITEMS_HEIGHT = 144f

        fun newInstance(draftId: Long? = null): RescueTrackFragment {
            return RescueTrackFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_DRAFT_ID, draftId ?: 0L)
                }
            }
        }
    }
}

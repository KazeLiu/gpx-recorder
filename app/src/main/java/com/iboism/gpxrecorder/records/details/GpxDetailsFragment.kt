package com.iboism.gpxrecorder.records.details

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.iboism.gpxrecorder.Keys
import com.iboism.gpxrecorder.R
import com.iboism.gpxrecorder.databinding.FragmentRouteDetailsBinding
import com.iboism.gpxrecorder.export.ExportFragment
import com.iboism.gpxrecorder.model.GpxContent
import com.iboism.gpxrecorder.recording.configurator.GPX_ID_KEY
import com.iboism.gpxrecorder.recording.configurator.READ_ONLY_TITLE_KEY
import com.iboism.gpxrecorder.recording.configurator.RecordingConfiguratorModal
import com.iboism.gpxrecorder.util.DateTimeFormatHelper
import com.iboism.gpxrecorder.util.FileHelper
import com.iboism.gpxrecorder.util.Holder
import com.iboism.gpxrecorder.util.PermissionHelper
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.functions.Consumer
import io.realm.ObjectChangeSet
import io.realm.Realm
import io.realm.RealmObjectChangeListener

class GpxDetailsFragment : Fragment(), TrackPointEditingDelegate {
    private lateinit var detailsView: GpxDetailsView
    private lateinit var gpxId: Holder<Long>
    private var fileHelper: FileHelper? = null
    private val compositeDisposable = CompositeDisposable()
    private var mapController: MapController? = null
    private var content: GpxContent? = null
    private lateinit var binding: FragmentRouteDetailsBinding

    private var gpxTitleConsumer: Consumer<in String> = Consumer {
        updateGpxTitle(it)
    }

    private val saveTouchConsumer = Consumer<Unit> {
        savePressed()
    }

    private val shareTouchConsumer = Consumer<Unit> {
        sharePressed()
    }

    private val mapLayerTouchConsumer = Consumer<Unit> {
        mapController?.toggleMapType()
    }

    private val deleteRouteTouchConsumer = Consumer<Unit> {
        deleteRouteAndPopFragment()
    }

    private val resumeRecordingTouchConsumer = Consumer<Unit> {
        resumeRecording()
    }

    private val trackPointEditToggleConsumer = Consumer<Boolean> {
        setTrackPointEditingEnabled(it)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gpxId = Holder(requireArguments().getLong(Keys.GpxId))
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View {
        binding = FragmentRouteDetailsBinding.inflate(layoutInflater, container, false)

        // Handle bottom insets for this fragment's content
        ViewCompat.setOnApplyWindowInsetsListener(binding.detailRoot) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // Apply bottom padding to ensure FABs and RecyclerView content aren't hidden
            view.setPadding(0, 0, 0, systemBars.bottom)
            insets
        }

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val realm = Realm.getDefaultInstance()
        val gpxContent = GpxContent.withId(gpxId.value, realm) ?: return
        content = gpxContent
        fileHelper = FileHelper()

        val listener = RealmObjectChangeListener { _: GpxContent?, _: ObjectChangeSet? ->
            mapController?.redraw()
        }

        gpxContent.addChangeListener(listener)
        val distance = gpxContent.totalDistanceKm()
        val pointCount = gpxContent.trackPointCount()

        detailsView = GpxDetailsView(
            binding = binding,
            titleText = gpxContent.title,
            distanceText = resources.getString(R.string.distance_km, distance),
            dateText24Hour = DateTimeFormatHelper.toReadableString24Hour(gpxContent.date),
            dateTextAmPm = DateTimeFormatHelper.toReadableStringAmPm(gpxContent.date),
            waypointsText = resources.getQuantityString(R.plurals.point_count, pointCount, pointCount)
        )

        realm.close()

        detailsView.restoreInstanceState(savedInstanceState)

        compositeDisposable.addAll(
            detailsView.gpxTitleObservable.subscribe(gpxTitleConsumer),
            detailsView.saveTouchObservable.subscribe(saveTouchConsumer),
            detailsView.shareTouchObservable.subscribe(shareTouchConsumer),
            detailsView.mapTypeToggleObservable.subscribe(mapLayerTouchConsumer),
            detailsView.deleteRouteObservable.subscribe(deleteRouteTouchConsumer),
            detailsView.resumeRecordingObservable.subscribe(resumeRecordingTouchConsumer),
            detailsView.trackPointEditToggleObservable.subscribe(trackPointEditToggleConsumer)
        )

        binding.mapView.let {
            val controller = MapController(it, gpxId.value)
            controller.trackPointEditingDelegate = this
            mapController = controller
            controller.onCreate(savedInstanceState)
        }
    }

    private fun deleteRouteAndPopFragment() {
        val realm = Realm.getDefaultInstance()
        realm.executeTransaction { itRealm ->
            GpxContent.withId(gpxId.value, itRealm)?.deleteFromRealm()
        }
        realm.close()

        parentFragmentManager.popBackStack()
    }

    private fun savePressed() {
        ExportFragment.newInstance(listOf(gpxId.value), listOf(ExportFragment.Action.Save)).show(parentFragmentManager, "export")
    }

    private fun sharePressed() {
        ExportFragment.newInstance(listOf(gpxId.value), listOf(ExportFragment.Action.Share)).show(parentFragmentManager, "export")
    }

    private fun resumeRecording() {
        PermissionHelper.checkLocationPermissions(this.requireActivity()) {
            showConfiguratorModal()
        }
    }

    private fun showConfiguratorModal() {
        val args = Bundle()

        args.putString(READ_ONLY_TITLE_KEY, binding.titleEt.text.toString())
        args.putLong(GPX_ID_KEY, gpxId.value)
        val frag = RecordingConfiguratorModal.instance()
        frag.arguments = args

        parentFragmentManager.popBackStackImmediate()
        RecordingConfiguratorModal.show(parentFragmentManager, frag)
    }

    private fun updateGpxTitle(newTitle: String) {
        val realm = Realm.getDefaultInstance()
        realm.executeTransaction { itRealm ->
            GpxContent.withId(gpxId.value, itRealm)?.title = newTitle
        }
        realm.close()
    }

    private fun setTrackPointEditingEnabled(isEnabled: Boolean) {
        val controller = mapController ?: return
        if (isEnabled && !controller.supportsTrackPointEditing) {
            detailsView.setTrackPointEditingEnabled(false)
            onTrackPointEditingUnsupported()
            return
        }

        controller.setTrackPointEditingEnabled(isEnabled)
        detailsView.setTrackPointEditingEnabled(isEnabled)
    }

    private fun updateRouteStats() {
        val realm = Realm.getDefaultInstance()
        val gpxContent = GpxContent.withId(gpxId.value, realm)
        if (gpxContent != null) {
            val distance = gpxContent.totalDistanceKm()
            val pointCount = gpxContent.trackPointCount()
            detailsView.setRouteStats(
                waypointsText = resources.getQuantityString(R.plurals.point_count, pointCount, pointCount),
                distanceText = resources.getString(R.string.distance_km, distance)
            )
        }
        realm.close()
    }

    override fun onTrackPointEditingChanged() {
        updateRouteStats()
    }

    override fun onTrackPointEditingUnsupported() {
        context?.let {
            AlertDialog.Builder(it)
                .setTitle(R.string.track_point_editing_unavailable_title)
                .setMessage(R.string.track_point_editing_unavailable_message)
                .setPositiveButton(R.string.okay, null)
                .create()
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        mapController?.onResume()
    }

    override fun onDestroy() {
        super.onDestroy()
        compositeDisposable.dispose()
        content?.removeAllChangeListeners()
    }

    override fun onDestroyView() {
        mapController?.onDestroy()
        binding.detailRoot.removeAllViews()
        super.onDestroyView()
    }

    override fun onPause() {
        super.onPause()
        mapController?.onPause()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapController?.onLowMemory()
    }

    override fun onStart() {
        super.onStart()
        mapController?.onStart()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        mapController?.onSaveInstanceState(outState)
        detailsView.onSaveInstanceState(outState)
        super.onSaveInstanceState(outState)
    }

    companion object {
        fun newInstance(gpxId: Long): GpxDetailsFragment {
            val args = Bundle()
            args.putLong(Keys.GpxId, gpxId)

            val fragment = GpxDetailsFragment()
            fragment.arguments = args

            return fragment
        }
    }
}


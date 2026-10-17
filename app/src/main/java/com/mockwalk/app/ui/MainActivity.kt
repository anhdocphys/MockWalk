package com.mockwalk.app.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.model.LatLng
import com.mockwalk.app.R
import com.mockwalk.app.data.OsrmRouteHelper
import com.mockwalk.app.data.RouteStorageManager
import com.mockwalk.app.databinding.ActivityMainBinding
import com.mockwalk.app.service.MockWalkService
import kotlinx.coroutines.launch
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.util.Locale

/**
 * Main Activity supporting:
 * 1. Dual-Layer Auto-Persistence via [RouteStorageManager] (survives ADB reinstalls).
 * 2. Auto-Restore of last route on startup & immediate 1Hz GPS Pre-Mocking at Coordinate [1] (STATE_ANCHORED).
 * 3. Multi-Waypoint selection on OpenStreetMap (OSMDroid) with numbered markers (Điểm 1, Điểm 2...).
 * 4. Route editing toolbar: Undo, Clear, Close Loop switch, and Preview Route.
 * 5. Separate controls for Start Walk, Stop Walk (Hold GPS for Strava Save), and Exit Mock (Restore real GPS).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var routeStorageManager: RouteStorageManager

    private val osrmHelper by lazy { OsrmRouteHelper() }

    // Multi-Waypoint state & Map UI Overlays
    private val waypoints = mutableListOf<LatLng>()
    private val waypointMarkers = mutableListOf<Marker>()
    private var walkerMarker: Marker? = null
    private var routePolyline: Polyline? = null

    // Cached preview route result
    private var previewedRouteResult: OsrmRouteHelper.RouteResult? = null

    // Guard flag to prevent listeners from invalidating preview while restoring saved state
    private var isRestoringState = false
    private var hasRestoredSavedRoute = false

    // Service Progress Broadcast Receiver
    private val progressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                MockWalkService.ACTION_UPDATE_PROGRESS -> {
                    val state = intent.getIntExtra(
                        MockWalkService.EXTRA_SERVICE_STATE,
                        MockWalkService.currentState
                    )
                    val lat = intent.getDoubleExtra(MockWalkService.EXTRA_CURRENT_LAT, 0.0)
                    val lng = intent.getDoubleExtra(MockWalkService.EXTRA_CURRENT_LNG, 0.0)
                    val distCovered = intent.getDoubleExtra(MockWalkService.EXTRA_DISTANCE_COVERED_METERS, 0.0)
                    val targetDist = intent.getDoubleExtra(MockWalkService.EXTRA_TARGET_DISTANCE_METERS, 0.0)
                    val speed = intent.getDoubleExtra(MockWalkService.EXTRA_CURRENT_SPEED_KMH, 0.0)
                    val paceSec = intent.getIntExtra(MockWalkService.EXTRA_CURRENT_PACE_SECONDS, 0)
                    val currentLap = intent.getIntExtra(MockWalkService.EXTRA_CURRENT_LAP, 1)
                    val isFinished = intent.getBooleanExtra(MockWalkService.EXTRA_IS_FINISHED, false)

                    val currentPos = LatLng(lat, lng)
                    updateWalkerOnMap(currentPos)
                    updateStatsUI(currentLap, distCovered, targetDist, speed, paceSec)
                    updateControlsForServiceState(state)

                    when {
                        isFinished -> {
                            binding.tvStatusText.text = getString(R.string.status_completed)
                        }
                        state == MockWalkService.STATE_ANCHORED -> {
                            val singleLapKm = (previewedRouteResult?.singleLapDistanceMeters ?: 0.0) / 1000.0
                            val routeInfo = if (singleLapKm > 0.01) {
                                String.format(Locale.US, " (1 vòng: %.2f km)", singleLapKm)
                            } else ""
                            binding.tvStatusText.text =
                                "📍 Đang khóa GPS tại Điểm [1]$routeInfo - Sẵn sàng mở Strava"
                        }
                        state == MockWalkService.STATE_WALKING -> {
                            binding.tvStatusText.text = String.format(
                                Locale.US,
                                "🏃 Đang đi bộ (Vòng %d) • %.2f / %.2f km",
                                currentLap, distCovered / 1000.0, targetDist / 1000.0
                            )
                        }
                    }
                }

                MockWalkService.ACTION_MOCK_ERROR -> {
                    val errorMsg = intent.getStringExtra(MockWalkService.EXTRA_ERROR_MESSAGE)
                    updateControlsForServiceState(MockWalkService.STATE_IDLE)
                    showMockLocationErrorDialog(errorMsg)
                }
            }
        }
    }

    // Permission Request Launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseLocationGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false

        if (fineLocationGranted || coarseLocationGranted) {
            if (!hasRestoredSavedRoute && waypoints.isEmpty()) {
                centerMapOnCurrentLocation()
            }
        } else {
            Toast.makeText(this, "Cần cấp quyền truy cập vị trí để tự động định vị trên bản đồ", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        routeStorageManager = RouteStorageManager(this)

        setupOpenStreetMap()
        setupControls()

        // Automatically restore last saved route and pre-mock GPS at Coordinate [1]
        hasRestoredSavedRoute = restoreSavedRouteOnStartup()

        checkPermissions()
    }

    private fun setupOpenStreetMap() {
        binding.mapView.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            isTilesScaledToDpi = true

            // Default center: Hoan Kiem Lake / Ho Chi Minh City (Zoom level 16)
            controller.setZoom(16.0)
            controller.setCenter(GeoPoint(10.795, 106.721))

            // Multi-waypoint tap listener using MapEventsOverlay
            val mapEventsReceiver = object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                    if (!MockWalkService.isWalking) {
                        addWaypoint(LatLng(p.latitude, p.longitude), autoAnchorIfFirst = true)
                    }
                    return true
                }

                override fun longPressHelper(p: GeoPoint): Boolean = false
            }
            overlays.add(0, MapEventsOverlay(mapEventsReceiver))
        }
    }

    /**
     * Restores the last saved route from [RouteStorageManager] when the app opens,
     * redraws markers + polyline, centers camera on Coordinate [1], and immediately activates
     * 1Hz GPS Pre-Mocking at Coordinate [1].
     */
    private fun restoreSavedRouteOnStartup(): Boolean {
        val saved = routeStorageManager.loadLastRoute() ?: return false
        if (saved.waypoints.isEmpty()) return false

        isRestoringState = true
        try {
            // Restore input parameters & closeLoop switch
            binding.etMinSpeed.setText(String.format(Locale.US, "%.1f", saved.minSpeedKmh))
            binding.etMaxSpeed.setText(String.format(Locale.US, "%.1f", saved.maxSpeedKmh))
            binding.etTargetDistance.setText(String.format(Locale.US, "%.1f", saved.targetDistanceKm))
            binding.swCloseLoop.isChecked = saved.closeLoop

            // Restore waypoints & numbered markers
            waypoints.clear()
            waypointMarkers.forEach { binding.mapView.overlays.remove(it) }
            waypointMarkers.clear()

            for (wp in saved.waypoints) {
                waypoints.add(wp)
                val index = waypoints.size
                val marker = Marker(binding.mapView).apply {
                    position = GeoPoint(wp.latitude, wp.longitude)
                    title = "Điểm $index"
                    snippet = String.format(Locale.US, "%.5f, %.5f", wp.latitude, wp.longitude)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    icon = createNumberedMarkerDrawable(index)
                }
                waypointMarkers.add(marker)
                binding.mapView.overlays.add(marker)
            }

            // Restore routePoints & draw Polyline immediately without waiting for network
            if (saved.routePoints.size >= 2) {
                val result = OsrmRouteHelper.RouteResult(
                    routePoints = saved.routePoints,
                    singleLapDistanceMeters = saved.singleLapDistanceMeters,
                    isFallback = false
                )
                previewedRouteResult = result
                drawRouteOnMap(saved.routePoints, zoomToFit = false)
            }

            // Center camera on Coordinate [1]
            val firstPoint = saved.routePoints.firstOrNull() ?: saved.waypoints.first()
            val firstGeoPoint = GeoPoint(firstPoint.latitude, firstPoint.longitude)
            binding.mapView.controller.setZoom(16.5)
            binding.mapView.controller.setCenter(firstGeoPoint)
            updateWalkerOnMap(firstPoint)
            binding.mapView.invalidate()

            val singleLapKm = saved.singleLapDistanceMeters / 1000.0
            binding.tvStatDistance.text = String.format(
                Locale.US,
                "0.00 / %.2f km",
                saved.targetDistanceKm
            )

            // If not already walking, immediately activate 1Hz GPS Anchor at Coordinate [1]
            if (!MockWalkService.isWalking) {
                anchorGpsAtPoint(firstPoint)
                val lapText = if (singleLapKm > 0.01) String.format(Locale.US, " (1 vòng: %.2f km)", singleLapKm) else ""
                binding.tvStatusText.text =
                    "📍 Đã nạp lộ trình cũ$lapText & khóa GPS tại Điểm [1]"
            }
        } finally {
            isRestoringState = false
        }
        return true
    }

    /**
     * Sends [MockWalkService.ACTION_ANCHOR_AT_START] to lock the device GPS at [point] (1Hz with micro-drift).
     */
    private fun anchorGpsAtPoint(point: LatLng) {
        val anchorIntent = Intent(this, MockWalkService::class.java).apply {
            action = MockWalkService.ACTION_ANCHOR_AT_START
            putExtra(MockWalkService.EXTRA_ANCHOR_LAT, point.latitude)
            putExtra(MockWalkService.EXTRA_ANCHOR_LNG, point.longitude)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(anchorIntent)
            } else {
                startService(anchorIntent)
            }
            updateControlsForServiceState(MockWalkService.STATE_ANCHORED)
        } catch (e: Exception) {
            Toast.makeText(this, "Không thể khởi chạy Neo GPS: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Saves the current waypoints, previewed OSRM routePoints, and input settings to [RouteStorageManager].
     */
    private fun saveCurrentStateToStorage() {
        if (isRestoringState || waypoints.isEmpty()) return

        val minSpeed = binding.etMinSpeed.text.toString().toFloatOrNull() ?: 4.0f
        val maxSpeed = binding.etMaxSpeed.text.toString().toFloatOrNull() ?: 6.0f
        val targetDist = binding.etTargetDistance.text.toString().toFloatOrNull() ?: 3.0f
        val closeLoop = binding.swCloseLoop.isChecked

        val routePts = previewedRouteResult?.routePoints ?: emptyList()
        val singleLapDist = previewedRouteResult?.singleLapDistanceMeters ?: 0.0

        val state = RouteStorageManager.SavedRouteState(
            waypoints = waypoints.toList(),
            routePoints = routePts,
            singleLapDistanceMeters = singleLapDist,
            closeLoop = closeLoop,
            minSpeedKmh = minSpeed,
            maxSpeedKmh = maxSpeed,
            targetDistanceKm = targetDist
        )
        routeStorageManager.saveRouteState(state)
    }

    /**
     * Appends a new waypoint to the list and renders a numbered marker ("Điểm 1", "Điểm 2"...).
     * If this is the very first point ("Điểm 1"), automatically locks GPS at Coordinate [1].
     */
    private fun addWaypoint(latLng: LatLng, autoAnchorIfFirst: Boolean = true) {
        waypoints.add(latLng)
        val index = waypoints.size

        clearRoutePreviewOnly()

        val marker = Marker(binding.mapView).apply {
            position = GeoPoint(latLng.latitude, latLng.longitude)
            title = "Điểm $index"
            snippet = String.format(Locale.US, "%.5f, %.5f", latLng.latitude, latLng.longitude)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = createNumberedMarkerDrawable(index)
        }
        waypointMarkers.add(marker)
        binding.mapView.overlays.add(marker)
        binding.mapView.invalidate()

        if (index == 1 && autoAnchorIfFirst && !MockWalkService.isWalking) {
            updateWalkerOnMap(latLng)
            anchorGpsAtPoint(latLng)
        }

        saveCurrentStateToStorage()
        updateWaypointStatusText()
    }

    /**
     * Removes the most recently added waypoint.
     */
    private fun undoLastWaypoint() {
        if (waypoints.isEmpty()) {
            Toast.makeText(this, "Không có điểm nào để hoàn tác", Toast.LENGTH_SHORT).show()
            return
        }

        waypoints.removeAt(waypoints.lastIndex)
        if (waypointMarkers.isNotEmpty()) {
            val lastMarker = waypointMarkers.removeAt(waypointMarkers.lastIndex)
            binding.mapView.overlays.remove(lastMarker)
        }

        clearRoutePreviewOnly()
        binding.mapView.invalidate()

        if (waypoints.isEmpty()) {
            routeStorageManager.clearSavedRoute()
        } else {
            saveCurrentStateToStorage()
        }
        updateWaypointStatusText()
    }

    /**
     * Clears all waypoints, markers, polylines, and saved route storage.
     */
    private fun clearAllWaypoints() {
        waypoints.clear()
        waypointMarkers.forEach { binding.mapView.overlays.remove(it) }
        waypointMarkers.clear()

        walkerMarker?.let { binding.mapView.overlays.remove(it) }
        walkerMarker = null

        clearRoutePreviewOnly()
        routeStorageManager.clearSavedRoute()
        binding.mapView.invalidate()
        binding.tvStatLap.text = "Lap: 1"
        binding.tvStatusText.text = getString(R.string.status_idle)
    }

    private fun clearRoutePreviewOnly() {
        previewedRouteResult = null
        routePolyline?.let { binding.mapView.overlays.remove(it) }
        routePolyline = null
    }

    private fun updateWaypointStatusText() {
        when (waypoints.size) {
            0 -> binding.tvStatusText.text = getString(R.string.status_idle)
            1 -> binding.tvStatusText.text =
                "📍 Đã khóa GPS tại Điểm [1] • Chấm thêm điểm quanh hồ hoặc bấm Start"
            else -> {
                val loopMode = if (binding.swCloseLoop.isChecked) "Khép kín vòng" else "Đi 2 chiều"
                binding.tvStatusText.text =
                    "📍 Khóa tại Điểm [1] • Đã chấm ${waypoints.size} điểm ($loopMode)"
            }
        }
    }

    /**
     * Creates a circular badge drawable with the waypoint index number (1, 2, 3...) inside.
     */
    private fun createNumberedMarkerDrawable(number: Int): Drawable {
        val density = resources.displayMetrics.density
        val sizePx = (28 * density).toInt().coerceAtLeast(32)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val center = sizePx / 2f
        val radius = center - (2f * density)

        // Fill circle (Green for Start Point #1, Strava Orange for subsequent waypoints)
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = if (number == 1) Color.parseColor("#00C853") else Color.parseColor("#FC4C02")
        }
        canvas.drawCircle(center, center, radius, fillPaint)

        // White outer stroke
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
            color = Color.WHITE
        }
        canvas.drawCircle(center, center, radius, strokePaint)

        // Number text
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 12f * density
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val textY = center - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(number.toString(), center, textY, textPaint)

        return BitmapDrawable(resources, bitmap)
    }

    private fun setupControls() {
        binding.btnUndo.setOnClickListener {
            if (!MockWalkService.isWalking) {
                undoLastWaypoint()
            }
        }

        binding.btnClear.setOnClickListener {
            if (!MockWalkService.isWalking) {
                clearAllWaypoints()
            }
        }

        binding.swCloseLoop.setOnCheckedChangeListener { _, _ ->
            if (!isRestoringState && !MockWalkService.isWalking) {
                clearRoutePreviewOnly()
                binding.mapView.invalidate()
                saveCurrentStateToStorage()
                updateWaypointStatusText()
            }
        }

        // Auto-save whenever speed or target distance inputs change
        val autoSaveWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!isRestoringState && waypoints.isNotEmpty()) {
                    saveCurrentStateToStorage()
                }
            }
        }
        binding.etMinSpeed.addTextChangedListener(autoSaveWatcher)
        binding.etMaxSpeed.addTextChangedListener(autoSaveWatcher)
        binding.etTargetDistance.addTextChangedListener(autoSaveWatcher)

        binding.btnPreviewRoute.setOnClickListener {
            if (!MockWalkService.isWalking) {
                handlePreviewRoute()
            }
        }

        binding.btnStart.setOnClickListener {
            handleStartSimulation()
        }

        binding.btnStopWalk.setOnClickListener {
            handleStopWalkHoldGps()
        }

        binding.btnExitMock.setOnClickListener {
            handleExitMockCompletely()
        }

        binding.fabMyLocation.setOnClickListener {
            centerMapOnCurrentLocation()
        }
    }

    /**
     * Calls OSRM API for current [waypoints] and [swCloseLoop], draws the Polyline,
     * auto-saves the route to [RouteStorageManager], and anchors GPS at Coordinate [1].
     */
    private fun handlePreviewRoute() {
        if (waypoints.isEmpty()) {
            Toast.makeText(this, R.string.error_no_start_point, Toast.LENGTH_SHORT).show()
            return
        }

        val targetDistKm = binding.etTargetDistance.text.toString().toDoubleOrNull() ?: 3.0
        val effectiveCloseLoop = if (waypoints.size == 1) true else binding.swCloseLoop.isChecked

        binding.progressBar.visibility = View.VISIBLE
        binding.tvStatusText.text = getString(R.string.status_fetching_route)
        binding.btnPreviewRoute.isEnabled = false
        binding.btnStart.isEnabled = false

        lifecycleScope.launch {
            try {
                val routeResult = osrmHelper.getRouteFromWaypoints(
                    waypoints = waypoints.toList(),
                    closeLoop = effectiveCloseLoop,
                    fallbackTargetDistanceKm = targetDistKm
                )
                binding.progressBar.visibility = View.GONE
                binding.btnPreviewRoute.isEnabled = true
                binding.btnStart.isEnabled = true

                if (routeResult.isFallback) {
                    Toast.makeText(this@MainActivity, R.string.error_osrm_failed, Toast.LENGTH_SHORT).show()
                }

                previewedRouteResult = routeResult
                drawRouteOnMap(routeResult.routePoints, zoomToFit = true)

                // Persist routePoints + parameters immediately
                saveCurrentStateToStorage()

                // Ensure GPS is anchored at Coordinate [1] of the OSRM route
                val firstRoadPoint = routeResult.routePoints.firstOrNull() ?: waypoints.first()
                anchorGpsAtPoint(firstRoadPoint)

                val singleLapKm = routeResult.singleLapDistanceMeters / 1000.0
                val msg = String.format(Locale.US, "Độ dài 1 vòng: %.2f km", singleLapKm)
                binding.tvStatusText.text = "📍 $msg • Đã lưu & khóa GPS tại Điểm [1]"
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()

            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                binding.btnPreviewRoute.isEnabled = true
                binding.btnStart.isEnabled = true
                Toast.makeText(this@MainActivity, "Lỗi xem trước lộ trình: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun handleStartSimulation() {
        if (waypoints.isEmpty()) {
            Toast.makeText(this, R.string.error_no_start_point, Toast.LENGTH_SHORT).show()
            return
        }

        val minSpeedKmh = binding.etMinSpeed.text.toString().toDoubleOrNull() ?: 4.0
        val maxSpeedKmh = binding.etMaxSpeed.text.toString().toDoubleOrNull() ?: 6.0
        val targetDistKm = binding.etTargetDistance.text.toString().toDoubleOrNull() ?: 3.0

        if (minSpeedKmh <= 0 || maxSpeedKmh <= 0 || minSpeedKmh > maxSpeedKmh) {
            Toast.makeText(this, "Vui lòng nhập dải tốc độ hợp lệ (Min <= Max)", Toast.LENGTH_SHORT).show()
            return
        }

        if (targetDistKm <= 0.1) {
            Toast.makeText(this, "Quãng đường mục tiêu tối thiểu là 0.1 km", Toast.LENGTH_SHORT).show()
            return
        }

        val effectiveCloseLoop = if (waypoints.size == 1) true else binding.swCloseLoop.isChecked

        binding.progressBar.visibility = View.VISIBLE
        binding.tvStatusText.text = getString(R.string.status_fetching_route)
        binding.btnStart.isEnabled = false
        binding.btnPreviewRoute.isEnabled = false

        lifecycleScope.launch {
            try {
                // Reuse previewed/restored route if available, otherwise fetch from OSRM
                val routeResult = previewedRouteResult ?: osrmHelper.getRouteFromWaypoints(
                    waypoints = waypoints.toList(),
                    closeLoop = effectiveCloseLoop,
                    fallbackTargetDistanceKm = targetDistKm
                )
                binding.progressBar.visibility = View.GONE

                if (routeResult.isFallback && previewedRouteResult == null) {
                    Toast.makeText(this@MainActivity, R.string.error_osrm_failed, Toast.LENGTH_SHORT).show()
                }

                previewedRouteResult = routeResult
                drawRouteOnMap(routeResult.routePoints, zoomToFit = true)

                // Persist routePoints + parameters across both storage layers
                saveCurrentStateToStorage()

                val singleLapKm = routeResult.singleLapDistanceMeters / 1000.0

                // Transition Service smoothly from STATE_ANCHORED to STATE_WALKING
                val serviceIntent = Intent(this@MainActivity, MockWalkService::class.java).apply {
                    action = MockWalkService.ACTION_START
                    putParcelableArrayListExtra(MockWalkService.EXTRA_ROUTE_POINTS, ArrayList(routeResult.routePoints))
                    putParcelableArrayListExtra(MockWalkService.EXTRA_WAYPOINTS, ArrayList(waypoints))
                    putExtra(MockWalkService.EXTRA_CLOSE_LOOP, effectiveCloseLoop)
                    putExtra(MockWalkService.EXTRA_TARGET_DISTANCE_KM, targetDistKm)
                    putExtra(MockWalkService.EXTRA_MIN_SPEED_KMH, minSpeedKmh)
                    putExtra(MockWalkService.EXTRA_MAX_SPEED_KMH, maxSpeedKmh)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }

                updateControlsForServiceState(MockWalkService.STATE_WALKING)
                binding.tvStatLap.text = "Lap: 1"
                binding.tvStatusText.text = String.format(
                    Locale.US,
                    "🏃 Đang đi bộ (Vòng 1 • 1 vòng = %.2f km)...",
                    singleLapKm
                )

            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                binding.btnStart.isEnabled = true
                binding.btnPreviewRoute.isEnabled = true
                Toast.makeText(this@MainActivity, "Lỗi tính lộ trình: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Stops walking movement but keeps GPS locked in [MockWalkService.STATE_ANCHORED]
     * so the user can switch to Strava and press Finish/Save without GPS jumping back home.
     */
    private fun handleStopWalkHoldGps() {
        val holdIntent = Intent(this, MockWalkService::class.java).apply {
            action = MockWalkService.ACTION_STOP_WALK
        }
        startService(holdIntent)

        updateControlsForServiceState(MockWalkService.STATE_ANCHORED)
        binding.tvStatusText.text = "📍 Đã dừng bước • Đang giữ khóa GPS để bạn lưu Strava"
        Toast.makeText(this, "Đã dừng đi bộ (Vẫn giữ GPS giả lập để lưu Strava)", Toast.LENGTH_SHORT).show()
    }

    /**
     * Completely exits mock mode, removes test provider, restores real hardware GPS, and stops service.
     */
    private fun handleExitMockCompletely() {
        val exitIntent = Intent(this, MockWalkService::class.java).apply {
            action = MockWalkService.ACTION_EXIT_MOCK
        }
        startService(exitIntent)

        updateControlsForServiceState(MockWalkService.STATE_IDLE)
        binding.tvStatusText.text = getString(R.string.status_stopped)
        Toast.makeText(this, "Đã tắt hẳn Mock GPS và trả lại GPS thật", Toast.LENGTH_SHORT).show()
    }

    private fun drawRouteOnMap(points: List<LatLng>, zoomToFit: Boolean) {
        if (points.isEmpty()) return

        routePolyline?.let { binding.mapView.overlays.remove(it) }

        val geoPoints = points.map { GeoPoint(it.latitude, it.longitude) }
        val polyline = Polyline(binding.mapView).apply {
            outlinePaint.color = ContextCompat.getColor(this@MainActivity, R.color.route_cyan)
            outlinePaint.strokeWidth = 14f
            setPoints(geoPoints)
        }
        routePolyline = polyline
        // Add polyline below markers so numbered pins remain clearly visible on top
        val insertIndex = if (binding.mapView.overlays.isNotEmpty()) 1 else 0
        binding.mapView.overlays.add(insertIndex, polyline)

        if (zoomToFit) {
            try {
                val boundingBox = BoundingBox.fromGeoPoints(geoPoints)
                binding.mapView.zoomToBoundingBox(boundingBox, true, 120)
            } catch (ignored: Exception) {
                binding.mapView.controller.animateTo(geoPoints[0], 16.0, 500L)
            }
        }
        binding.mapView.invalidate()
    }

    private fun updateWalkerOnMap(pos: LatLng) {
        val geoPoint = GeoPoint(pos.latitude, pos.longitude)
        if (walkerMarker == null) {
            val marker = Marker(binding.mapView).apply {
                position = geoPoint
                title = "Vị trí GPS hiện tại"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_walk)
            }
            walkerMarker = marker
            binding.mapView.overlays.add(marker)
        } else {
            walkerMarker?.position = geoPoint
        }
        binding.mapView.invalidate()
    }

    private fun updateStatsUI(
        currentLap: Int,
        distanceCoveredMeters: Double,
        targetDistanceMeters: Double,
        speedKmh: Double,
        paceSeconds: Int
    ) {
        val distCoveredKm = distanceCoveredMeters / 1000.0
        val targetKm = targetDistanceMeters / 1000.0
        binding.tvStatLap.text = String.format(Locale.US, "Lap: %d", currentLap)
        binding.tvStatDistance.text = String.format(Locale.US, "%.2f / %.2f km", distCoveredKm, targetKm)
        binding.tvStatSpeed.text = String.format(Locale.US, "%.1f km/h", speedKmh)

        val paceMin = paceSeconds / 60
        val paceSec = paceSeconds % 60
        binding.tvStatPace.text = String.format(Locale.US, "%d'%02d\" /km", paceMin, paceSec)
    }

    private fun updateControlsForServiceState(state: Int) {
        val isWalking = (state == MockWalkService.STATE_WALKING)
        val isMockActive = (state == MockWalkService.STATE_ANCHORED || state == MockWalkService.STATE_WALKING)

        binding.btnStart.isEnabled = !isWalking
        binding.btnStopWalk.isEnabled = isWalking
        binding.btnExitMock.isEnabled = isMockActive

        binding.btnUndo.isEnabled = !isWalking
        binding.btnClear.isEnabled = !isWalking
        binding.btnPreviewRoute.isEnabled = !isWalking
        binding.swCloseLoop.isEnabled = !isWalking
        binding.etMinSpeed.isEnabled = !isWalking
        binding.etMaxSpeed.isEnabled = !isWalking
        binding.etTargetDistance.isEnabled = !isWalking

        val dotColor = when (state) {
            MockWalkService.STATE_WALKING -> R.color.accent_green
            MockWalkService.STATE_ANCHORED -> R.color.route_cyan
            else -> R.color.strava_orange
        }
        binding.ivStatusDot.setColorFilter(ContextCompat.getColor(this, dotColor))
    }

    private fun showMockLocationErrorDialog(errorMessage: String?) {
        AlertDialog.Builder(this)
            .setTitle(R.string.error_mock_location_title)
            .setMessage(getString(R.string.error_mock_location_message) + "\n\n(Chi tiết: ${errorMessage ?: "N/A"})")
            .setPositiveButton(R.string.btn_open_dev_settings) { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .setCancelable(false)
            .show()
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else if (!hasRestoredSavedRoute && waypoints.isEmpty()) {
            centerMapOnCurrentLocation()
        }
    }

    private fun centerMapOnCurrentLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        // If user already has waypoints, center on Coordinate [1] instead of jumping away
        if (waypoints.isNotEmpty()) {
            val first = previewedRouteResult?.routePoints?.firstOrNull() ?: waypoints.first()
            binding.mapView.controller.animateTo(GeoPoint(first.latitude, first.longitude), 16.5, 600L)
            return
        }

        fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
            loc?.let {
                val point = GeoPoint(it.latitude, it.longitude)
                binding.mapView.controller.animateTo(point, 16.0, 800L)
                if (waypoints.isEmpty() && !MockWalkService.isWalking) {
                    addWaypoint(LatLng(it.latitude, it.longitude), autoAnchorIfFirst = true)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()

        val filter = IntentFilter().apply {
            addAction(MockWalkService.ACTION_UPDATE_PROGRESS)
            addAction(MockWalkService.ACTION_MOCK_ERROR)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(progressReceiver, filter)
        updateControlsForServiceState(MockWalkService.currentState)
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(progressReceiver)
    }
}

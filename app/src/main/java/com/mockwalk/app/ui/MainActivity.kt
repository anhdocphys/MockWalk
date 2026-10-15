package com.mockwalk.app.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
 * Main Activity featuring OpenStreetMap (OSMDroid) for 100% free, reliable, no-API-key map rendering.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private val osrmHelper by lazy { OsrmRouteHelper() }

    // Map UI Overlays
    private var startPoint: LatLng? = null
    private var startMarker: Marker? = null
    private var walkerMarker: Marker? = null
    private var routePolyline: Polyline? = null

    private var currentRoutePoints: List<LatLng> = emptyList()

    // Service Progress Broadcast Receiver
    private val progressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                MockWalkService.ACTION_UPDATE_PROGRESS -> {
                    val lat = intent.getDoubleExtra(MockWalkService.EXTRA_CURRENT_LAT, 0.0)
                    val lng = intent.getDoubleExtra(MockWalkService.EXTRA_CURRENT_LNG, 0.0)
                    val distCovered = intent.getDoubleExtra(MockWalkService.EXTRA_DISTANCE_COVERED_METERS, 0.0)
                    val targetDist = intent.getDoubleExtra(MockWalkService.EXTRA_TARGET_DISTANCE_METERS, 0.0)
                    val speed = intent.getDoubleExtra(MockWalkService.EXTRA_CURRENT_SPEED_KMH, 0.0)
                    val paceSec = intent.getIntExtra(MockWalkService.EXTRA_CURRENT_PACE_SECONDS, 0)
                    val isFinished = intent.getBooleanExtra(MockWalkService.EXTRA_IS_FINISHED, false)

                    val currentPos = LatLng(lat, lng)
                    updateWalkerOnMap(currentPos)
                    updateStatsUI(distCovered, targetDist, speed, paceSec)

                    if (isFinished) {
                        setSimulationRunningState(false)
                        binding.tvStatusText.text = getString(R.string.status_completed)
                        Toast.makeText(this@MainActivity, R.string.status_completed, Toast.LENGTH_LONG).show()
                    }
                }
                MockWalkService.ACTION_MOCK_ERROR -> {
                    val errorMsg = intent.getStringExtra(MockWalkService.EXTRA_ERROR_MESSAGE)
                    setSimulationRunningState(false)
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
            centerMapOnCurrentLocation()
        } else {
            Toast.makeText(this, "Cần cấp quyền truy cập vị trí để tự động định vị trên bản đồ", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        setupOpenStreetMap()
        setupButtons()
        checkPermissions()
    }

    private fun setupOpenStreetMap() {
        binding.mapView.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            isTilesScaledToDpi = true

            // Default center: Landmark 81 / Ho Chi Minh City (Zoom level 16)
            controller.setZoom(16.0)
            controller.setCenter(GeoPoint(10.795, 106.721))

            // Map click listener using MapEventsOverlay
            val mapEventsReceiver = object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                    if (!MockWalkService.isServiceRunning) {
                        setStartLocation(LatLng(p.latitude, p.longitude))
                    }
                    return true
                }

                override fun longPressHelper(p: GeoPoint): Boolean = false
            }
            overlays.add(0, MapEventsOverlay(mapEventsReceiver))
        }
    }

    private fun setStartLocation(latLng: LatLng) {
        startPoint = latLng

        // Remove previous markers & route
        startMarker?.let { binding.mapView.overlays.remove(it) }
        walkerMarker?.let { binding.mapView.overlays.remove(it) }
        walkerMarker = null
        routePolyline?.let { binding.mapView.overlays.remove(it) }
        routePolyline = null

        // Add start marker
        val marker = Marker(binding.mapView).apply {
            position = GeoPoint(latLng.latitude, latLng.longitude)
            title = "Điểm xuất phát (Start)"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_walk)
        }
        startMarker = marker
        binding.mapView.overlays.add(marker)
        binding.mapView.invalidate()

        binding.tvStatusText.text = String.format(
            Locale.US,
            "Đã chọn: %.5f, %.5f (Bấm Start để bắt đầu)",
            latLng.latitude, latLng.longitude
        )
    }

    private fun setupButtons() {
        binding.btnStart.setOnClickListener {
            handleStartSimulation()
        }

        binding.btnStop.setOnClickListener {
            handleStopSimulation()
        }

        binding.fabMyLocation.setOnClickListener {
            centerMapOnCurrentLocation()
        }
    }

    private fun handleStartSimulation() {
        val selectedStart = startPoint
        if (selectedStart == null) {
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

        // Fetch OSRM route via coroutine
        binding.progressBar.visibility = View.VISIBLE
        binding.tvStatusText.text = getString(R.string.status_fetching_route)
        binding.btnStart.isEnabled = false

        lifecycleScope.launch {
            try {
                val routeResult = osrmHelper.getRealisticWalkRoute(selectedStart, targetDistKm)
                binding.progressBar.visibility = View.GONE

                if (routeResult.isFallback) {
                    Toast.makeText(this@MainActivity, R.string.error_osrm_failed, Toast.LENGTH_SHORT).show()
                }

                currentRoutePoints = routeResult.points
                drawRouteOnMap(routeResult.points)

                // Launch Foreground Service
                val serviceIntent = Intent(this@MainActivity, MockWalkService::class.java).apply {
                    action = MockWalkService.ACTION_START
                    putParcelableArrayListExtra(MockWalkService.EXTRA_ROUTE_POINTS, ArrayList(routeResult.points))
                    putExtra(MockWalkService.EXTRA_TARGET_DISTANCE_KM, targetDistKm)
                    putExtra(MockWalkService.EXTRA_MIN_SPEED_KMH, minSpeedKmh)
                    putExtra(MockWalkService.EXTRA_MAX_SPEED_KMH, maxSpeedKmh)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }

                setSimulationRunningState(true)
                binding.tvStatusText.text = getString(R.string.status_walking)

            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                binding.btnStart.isEnabled = true
                Toast.makeText(this@MainActivity, "Lỗi tính lộ trình: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun handleStopSimulation() {
        val stopIntent = Intent(this, MockWalkService::class.java).apply {
            action = MockWalkService.ACTION_STOP
        }
        startService(stopIntent)

        setSimulationRunningState(false)
        binding.tvStatusText.text = getString(R.string.status_stopped)
    }

    private fun drawRouteOnMap(points: List<LatLng>) {
        if (points.isEmpty()) return

        routePolyline?.let { binding.mapView.overlays.remove(it) }

        val geoPoints = points.map { GeoPoint(it.latitude, it.longitude) }
        val polyline = Polyline(binding.mapView).apply {
            outlinePaint.color = ContextCompat.getColor(this@MainActivity, R.color.route_cyan)
            outlinePaint.strokeWidth = 14f
            setPoints(geoPoints)
        }
        routePolyline = polyline
        binding.mapView.overlays.add(polyline)

        // Fit map bounds
        try {
            val boundingBox = BoundingBox.fromGeoPoints(geoPoints)
            binding.mapView.zoomToBoundingBox(boundingBox, true, 120)
        } catch (ignored: Exception) {
            binding.mapView.controller.animateTo(geoPoints[0], 16.0, 500L)
        }
        binding.mapView.invalidate()
    }

    private fun updateWalkerOnMap(pos: LatLng) {
        val geoPoint = GeoPoint(pos.latitude, pos.longitude)
        if (walkerMarker == null) {
            val marker = Marker(binding.mapView).apply {
                position = geoPoint
                title = "Vị trí đi bộ hiện tại"
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
        distanceCoveredMeters: Double,
        targetDistanceMeters: Double,
        speedKmh: Double,
        paceSeconds: Int
    ) {
        val distCoveredKm = distanceCoveredMeters / 1000.0
        val targetKm = targetDistanceMeters / 1000.0
        binding.tvStatDistance.text = String.format(Locale.US, "%.2f / %.2f km", distCoveredKm, targetKm)
        binding.tvStatSpeed.text = String.format(Locale.US, "%.1f km/h", speedKmh)

        val paceMin = paceSeconds / 60
        val paceSec = paceSeconds % 60
        binding.tvStatPace.text = String.format(Locale.US, "%d'%02d\" /km", paceMin, paceSec)
    }

    private fun setSimulationRunningState(isRunning: Boolean) {
        binding.btnStart.isEnabled = !isRunning
        binding.btnStop.isEnabled = isRunning
        binding.etMinSpeed.isEnabled = !isRunning
        binding.etMaxSpeed.isEnabled = !isRunning
        binding.etTargetDistance.isEnabled = !isRunning

        if (isRunning) {
            binding.ivStatusDot.setColorFilter(ContextCompat.getColor(this, R.color.accent_green))
        } else {
            binding.ivStatusDot.setColorFilter(ContextCompat.getColor(this, R.color.strava_orange))
        }
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
        } else {
            centerMapOnCurrentLocation()
        }
    }

    private fun centerMapOnCurrentLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
            loc?.let {
                val point = GeoPoint(it.latitude, it.longitude)
                binding.mapView.controller.animateTo(point, 16.0, 800L)
                if (startPoint == null) {
                    setStartLocation(LatLng(it.latitude, it.longitude))
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
        setSimulationRunningState(MockWalkService.isServiceRunning)
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(progressReceiver)
    }
}

package com.mockwalk.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.model.LatLng
import com.mockwalk.app.MockWalkApplication
import com.mockwalk.app.R
import com.mockwalk.app.ui.MainActivity
import com.mockwalk.app.util.GeoUtils
import kotlinx.coroutines.*
import java.util.Locale
import kotlin.math.*
import kotlin.random.Random

/**
 * Foreground Service responsible for:
 * 1. Simulating realistic pedestrian kinetics along road-following OSRM polyline.
 * 2. Generating anti-filter physics (cadence wave, speed random walk, lateral jitter, elevation waves).
 * 3. Dual-provider mock location injection into both LocationManager and FusedLocationProviderClient.
 * 4. Maintaining foreground ongoing notification on Android 14+ (Samsung S24 Ultra).
 */
class MockWalkService : Service() {

    companion object {
        private const val TAG = "MockWalkService"
        private const val NOTIFICATION_ID = 1001

        // Actions
        const val ACTION_START = "com.mockwalk.app.ACTION_START"
        const val ACTION_STOP = "com.mockwalk.app.ACTION_STOP"
        const val ACTION_UPDATE_PROGRESS = "com.mockwalk.app.ACTION_UPDATE_PROGRESS"
        const val ACTION_MOCK_ERROR = "com.mockwalk.app.ACTION_MOCK_ERROR"

        // Intent Extras
        const val EXTRA_ROUTE_POINTS = "extra_route_points"
        const val EXTRA_TARGET_DISTANCE_KM = "extra_target_distance_km"
        const val EXTRA_MIN_SPEED_KMH = "extra_min_speed_kmh"
        const val EXTRA_MAX_SPEED_KMH = "extra_max_speed_kmh"

        // Broadcast Extras
        const val EXTRA_CURRENT_LAT = "extra_current_lat"
        const val EXTRA_CURRENT_LNG = "extra_current_lng"
        const val EXTRA_DISTANCE_COVERED_METERS = "extra_distance_covered_meters"
        const val EXTRA_TARGET_DISTANCE_METERS = "extra_target_distance_meters"
        const val EXTRA_CURRENT_SPEED_KMH = "extra_current_speed_kmh"
        const val EXTRA_CURRENT_PACE_SECONDS = "extra_current_pace_seconds"
        const val EXTRA_IS_FINISHED = "extra_is_finished"
        const val EXTRA_ERROR_MESSAGE = "extra_error_message"

        @Volatile
        var isServiceRunning = false
            private set
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var simulationJob: Job? = null

    private lateinit var locationManager: LocationManager
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var wakeLock: PowerManager.WakeLock? = null

    private var isTestProviderAdded = false

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Acquire partial wake lock to prevent Samsung One UI CPU sleep while walking
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MockWalk::SimulationWakeLock").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L) // Max 12 hours safety timeout
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val routePoints = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(EXTRA_ROUTE_POINTS, LatLng::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra<LatLng>(EXTRA_ROUTE_POINTS)
                }
                val targetDistanceKm = intent.getDoubleExtra(EXTRA_TARGET_DISTANCE_KM, 3.0)
                val minSpeedKmh = intent.getDoubleExtra(EXTRA_MIN_SPEED_KMH, 4.0)
                val maxSpeedKmh = intent.getDoubleExtra(EXTRA_MAX_SPEED_KMH, 6.0)

                if (!routePoints.isNullOrEmpty()) {
                    startForegroundWithNotification(0.0, targetDistanceKm * 1000.0, minSpeedKmh)
                    isServiceRunning = true
                    startSimulation(routePoints, targetDistanceKm, minSpeedKmh, maxSpeedKmh)
                } else {
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                stopSimulation()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundWithNotification(
        distanceCoveredMeters: Double,
        targetDistanceMeters: Double,
        speedKmh: Double
    ) {
        val notification = buildNotification(distanceCoveredMeters, targetDistanceMeters, speedKmh)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(
        distanceCoveredMeters: Double,
        targetDistanceMeters: Double,
        speedKmh: Double
    ): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, MockWalkService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val distCoveredKm = distanceCoveredMeters / 1000.0
        val targetKm = targetDistanceMeters / 1000.0
        val paceSec = if (speedKmh > 0.1) (3600.0 / speedKmh).toInt() else 0
        val paceMin = paceSec / 60
        val paceRemSec = paceSec % 60

        val contentText = String.format(
            Locale.US,
            "Quãng đường: %.2f / %.2f km • %.1f km/h • %d'%02d\"/km",
            distCoveredKm, targetKm, speedKmh, paceMin, paceRemSec
        )

        return NotificationCompat.Builder(this, MockWalkApplication.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_walk)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(contentText)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.action_stop), stopPendingIntent)
            .build()
    }

    private fun updateNotification(
        distanceCoveredMeters: Double,
        targetDistanceMeters: Double,
        speedKmh: Double
    ) {
        val notification = buildNotification(distanceCoveredMeters, targetDistanceMeters, speedKmh)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun setupMockLocationProviders(): Boolean {
        return try {
            // Setup Android standard LocationManager GPS test provider
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    locationManager.addTestProvider(
                        LocationManager.GPS_PROVIDER,
                        false, false, false, false, true, true, true,
                        ProviderProperties.POWER_USAGE_LOW,
                        ProviderProperties.ACCURACY_FINE
                    )
                } else {
                    @Suppress("DEPRECATION")
                    locationManager.addTestProvider(
                        LocationManager.GPS_PROVIDER,
                        "requiresNetwork" == "",
                        "requiresSatellite" == "",
                        "requiresCell" == "",
                        "hasMonetaryCost" == "",
                        "supportsAltitude" == "",
                        "supportsSpeed" == "",
                        "supportsBearing" == "",
                        android.location.Criteria.POWER_LOW,
                        android.location.Criteria.ACCURACY_FINE
                    )
                }
            } catch (ignored: IllegalArgumentException) {
                // Provider may have already been added in a previous session
            }

            locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
            isTestProviderAdded = true

            // Setup Google Play Services FusedLocationProviderClient mock mode
            fusedLocationClient.setMockMode(true)

            Log.d(TAG, "Successfully initialized dual mock location providers.")
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException: Mock location not permitted in Developer Options!", e)
            broadcastError(e.message ?: "Chưa cấp quyền Mock Location trong Tùy chọn nhà phát triển")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing mock location providers", e)
            broadcastError("Lỗi cấu hình mock location: ${e.message}")
            false
        }
    }

    private fun startSimulation(
        routePoints: List<LatLng>,
        targetDistanceKm: Double,
        minSpeedKmh: Double,
        maxSpeedKmh: Double
    ) {
        simulationJob?.cancel()

        if (!setupMockLocationProviders()) {
            stopSelf()
            return
        }

        simulationJob = serviceScope.launch {
            val targetDistanceMeters = targetDistanceKm * 1000.0
            val minSpeedMps = minSpeedKmh / 3.6
            val maxSpeedMps = maxSpeedKmh / 3.6

            var currentSpeedMps = (minSpeedMps + maxSpeedMps) / 2.0
            var accumulatedDistanceMeters = 0.0

            var currentSegmentIndex = 0
            var distanceIntoCurrentSegment = 0.0

            var currentBearing = if (routePoints.size >= 2) {
                GeoUtils.computeBearing(routePoints[0], routePoints[1])
            } else 0.0

            val baseAltitude = 18.0 // Meters (standard urban elevation)
            var tickCount = 0L
            var slowdownTicksRemaining = 0
            var currentLateralJitter = 0.0

            var lastTickTimeNanos = SystemClock.elapsedRealtimeNanos()

            while (isActive && accumulatedDistanceMeters < targetDistanceMeters && currentSegmentIndex < routePoints.size - 1) {
                // Real-time loop tick: nominal 1000ms with jitter +/- 30ms
                val jitterMs = (1000 + Random.nextLong(-30, 31)).coerceAtLeast(850)
                delay(jitterMs)

                val nowNanos = SystemClock.elapsedRealtimeNanos()
                val dtSeconds = ((nowNanos - lastTickTimeNanos) / 1_000_000_000.0).coerceIn(0.5, 2.0)
                lastTickTimeNanos = nowNanos
                tickCount++

                // 1. Inertial Speed Random Walk & Cadence Wave
                val cadenceFrequency = 1.8 // Hz (~108 steps per minute)
                val cadenceWave = sin(2.0 * Math.PI * cadenceFrequency * (tickCount * dtSeconds)) * 0.035
                val randomAccel = Random.nextDouble(-0.12, 0.12)

                // 1% - 2% probability to simulate deceleration (e.g. crosswalk / corner pause)
                if (slowdownTicksRemaining > 0) {
                    slowdownTicksRemaining--
                    currentSpeedMps = (currentSpeedMps - 0.20 * dtSeconds).coerceAtLeast(minSpeedMps * 0.85)
                } else if (Random.nextDouble(0.0, 1.0) < 0.015) {
                    slowdownTicksRemaining = Random.nextInt(2, 5)
                } else {
                    currentSpeedMps = (currentSpeedMps + (randomAccel + cadenceWave) * dtSeconds)
                        .coerceIn(minSpeedMps, maxSpeedMps)
                }

                // 2. Segment Interpolation along OSRM Polyline
                val stepDistMeters = currentSpeedMps * dtSeconds
                accumulatedDistanceMeters += stepDistMeters

                var distanceToAdvance = stepDistMeters
                while (distanceToAdvance > 0 && currentSegmentIndex < routePoints.size - 1) {
                    val p1 = routePoints[currentSegmentIndex]
                    val p2 = routePoints[currentSegmentIndex + 1]
                    val segLength = GeoUtils.computeDistanceMeters(p1, p2)
                    val remainingInSeg = segLength - distanceIntoCurrentSegment

                    if (distanceToAdvance < remainingInSeg) {
                        distanceIntoCurrentSegment += distanceToAdvance
                        distanceToAdvance = 0.0
                    } else {
                        distanceToAdvance -= remainingInSeg
                        currentSegmentIndex++
                        distanceIntoCurrentSegment = 0.0
                    }
                }

                // Calculate base coordinate along road segment
                val pStart = routePoints[currentSegmentIndex.coerceAtMost(routePoints.size - 1)]
                val pEnd = routePoints[(currentSegmentIndex + 1).coerceAtMost(routePoints.size - 1)]
                val segLength = GeoUtils.computeDistanceMeters(pStart, pEnd).coerceAtLeast(0.001)
                val fraction = (distanceIntoCurrentSegment / segLength).coerceIn(0.0, 1.0)

                val roadCoordinate = GeoUtils.interpolate(pStart, pEnd, fraction)

                // 3. Realistic Heading & Bearing with small natural noise
                val rawBearing = if (pStart != pEnd) GeoUtils.computeBearing(pStart, pEnd) else currentBearing
                val bearingNoise = Random.nextDouble(-2.0, 2.0)
                currentBearing = ((rawBearing + bearingNoise) + 360.0) % 360.0

                // 4. Lateral Multipath Jitter (-1.2m to +1.2m smoothly)
                val targetJitter = Random.nextDouble(-1.2, 1.2)
                currentLateralJitter += (targetJitter - currentLateralJitter) * 0.25 // Low pass filter
                val realisticCoordinate = GeoUtils.applyLateralOffset(roadCoordinate, currentBearing, currentLateralJitter)

                // 5. Elevation Undulation
                val altitude = baseAltitude +
                        sin(accumulatedDistanceMeters / 120.0) * 2.5 +
                        cos(accumulatedDistanceMeters / 45.0) * 0.8 +
                        Random.nextDouble(-0.15, 0.15)

                // 6. Dual-Provider Mock Location Injection
                injectMockLocation(
                    lat = realisticCoordinate.latitude,
                    lng = realisticCoordinate.longitude,
                    alt = altitude,
                    speedMps = currentSpeedMps.toFloat(),
                    bearingDeg = currentBearing.toFloat()
                )

                // Update notification and broadcast progress to UI
                val currentSpeedKmh = currentSpeedMps * 3.6
                val currentPaceSec = if (currentSpeedKmh > 0.1) (3600.0 / currentSpeedKmh).toInt() else 0

                if (tickCount % 2L == 0L) {
                    updateNotification(accumulatedDistanceMeters, targetDistanceMeters, currentSpeedKmh)
                }

                broadcastProgress(
                    lat = realisticCoordinate.latitude,
                    lng = realisticCoordinate.longitude,
                    distanceCoveredMeters = accumulatedDistanceMeters,
                    targetDistanceMeters = targetDistanceMeters,
                    speedKmh = currentSpeedKmh,
                    paceSeconds = currentPaceSec,
                    isFinished = accumulatedDistanceMeters >= targetDistanceMeters
                )
            }

            // Target distance reached or finished
            Log.d(TAG, "Simulation finished. Total distance: ${accumulatedDistanceMeters.roundToInt()}m")
            broadcastProgress(
                lat = routePoints.last().latitude,
                lng = routePoints.last().longitude,
                distanceCoveredMeters = accumulatedDistanceMeters,
                targetDistanceMeters = targetDistanceMeters,
                speedKmh = 0.0,
                paceSeconds = 0,
                isFinished = true
            )
            stopSelf()
        }
    }

    private fun injectMockLocation(
        lat: Double,
        lng: Double,
        alt: Double,
        speedMps: Float,
        bearingDeg: Float
    ) {
        try {
            val nowMs = System.currentTimeMillis()
            val elapsedNanos = SystemClock.elapsedRealtimeNanos()

            val mockLocation = Location(LocationManager.GPS_PROVIDER).apply {
                latitude = lat
                longitude = lng
                altitude = alt
                speed = speedMps
                bearing = bearingDeg
                accuracy = Random.nextDouble(3.0, 5.5).toFloat()
                time = nowMs
                elapsedRealtimeNanos = elapsedNanos

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    speedAccuracyMetersPerSecond = 0.2f
                    bearingAccuracyDegrees = 5.0f
                    verticalAccuracyMeters = 2.0f
                }
            }

            // Inject into standard Android LocationManager
            if (isTestProviderAdded) {
                locationManager.setTestProviderLocation(LocationManager.GPS_PROVIDER, mockLocation)
            }

            // Inject into Google Play Services FusedLocationProviderClient
            fusedLocationClient.setMockLocation(mockLocation)

        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException during mock injection: ${e.message}")
            broadcastError(e.message ?: "Chưa cấp quyền Mock Location")
            stopSimulation()
            stopSelf()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inject mock location: ${e.message}", e)
        }
    }

    private fun broadcastProgress(
        lat: Double,
        lng: Double,
        distanceCoveredMeters: Double,
        targetDistanceMeters: Double,
        speedKmh: Double,
        paceSeconds: Int,
        isFinished: Boolean
    ) {
        val intent = Intent(ACTION_UPDATE_PROGRESS).apply {
            putExtra(EXTRA_CURRENT_LAT, lat)
            putExtra(EXTRA_CURRENT_LNG, lng)
            putExtra(EXTRA_DISTANCE_COVERED_METERS, distanceCoveredMeters)
            putExtra(EXTRA_TARGET_DISTANCE_METERS, targetDistanceMeters)
            putExtra(EXTRA_CURRENT_SPEED_KMH, speedKmh)
            putExtra(EXTRA_CURRENT_PACE_SECONDS, paceSeconds)
            putExtra(EXTRA_IS_FINISHED, isFinished)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastError(message: String) {
        val intent = Intent(ACTION_MOCK_ERROR).apply {
            putExtra(EXTRA_ERROR_MESSAGE, message)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun stopSimulation() {
        simulationJob?.cancel()
        simulationJob = null
        isServiceRunning = false

        try {
            fusedLocationClient.setMockMode(false)
        } catch (ignored: Exception) {}

        if (isTestProviderAdded) {
            try {
                locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false)
                locationManager.clearTestProviderLocation(LocationManager.GPS_PROVIDER)
                locationManager.removeTestProvider(LocationManager.GPS_PROVIDER)
            } catch (ignored: Exception) {}
            isTestProviderAdded = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSimulation()
        serviceScope.cancel()

        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        Log.d(TAG, "MockWalkService destroyed.")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

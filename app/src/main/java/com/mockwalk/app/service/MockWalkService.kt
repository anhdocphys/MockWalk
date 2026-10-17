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
import com.mockwalk.app.data.OsrmRouteHelper
import com.mockwalk.app.ui.MainActivity
import com.mockwalk.app.util.GeoUtils
import kotlinx.coroutines.*
import java.util.Locale
import kotlin.math.*
import kotlin.random.Random

/**
 * Foreground Service supporting 3 states:
 * - [STATE_IDLE]: Completely stopped, real hardware GPS active.
 * - [STATE_ANCHORED]: Stationary 1Hz GPS lock at Start Point [1] (or stopped position) with micro-drift (+/-0.25m)
 *                     so Strava locks onto the lakeside start point immediately without jumping from home.
 * - [STATE_WALKING]: Realistic multi-lap walking simulation along OSRM road geometry with per-tick lateral drift
 *                    [-1.5m, +1.5m] and corner slowdown (>35 deg).
 */
class MockWalkService : Service() {

    companion object {
        private const val TAG = "MockWalkService"
        private const val NOTIFICATION_ID = 1001

        // Service Operating States
        const val STATE_IDLE = 0
        const val STATE_ANCHORED = 1
        const val STATE_WALKING = 2

        // Actions
        const val ACTION_ANCHOR_AT_START = "com.mockwalk.app.ACTION_ANCHOR_AT_START"
        const val ACTION_START = "com.mockwalk.app.ACTION_START"
        const val ACTION_STOP_WALK = "com.mockwalk.app.ACTION_STOP_WALK"
        const val ACTION_EXIT_MOCK = "com.mockwalk.app.ACTION_EXIT_MOCK"
        const val ACTION_STOP = ACTION_EXIT_MOCK

        // Broadcast Actions
        const val ACTION_UPDATE_PROGRESS = "com.mockwalk.app.ACTION_UPDATE_PROGRESS"
        const val ACTION_MOCK_ERROR = "com.mockwalk.app.ACTION_MOCK_ERROR"

        // Intent Extras
        const val EXTRA_ANCHOR_LAT = "extra_anchor_lat"
        const val EXTRA_ANCHOR_LNG = "extra_anchor_lng"
        const val EXTRA_ROUTE_POINTS = "extra_route_points"
        const val EXTRA_WAYPOINTS = "extra_waypoints"
        const val EXTRA_CLOSE_LOOP = "extra_close_loop"
        const val EXTRA_TARGET_DISTANCE_KM = "extra_target_distance_km"
        const val EXTRA_MIN_SPEED_KMH = "extra_min_speed_kmh"
        const val EXTRA_MAX_SPEED_KMH = "extra_max_speed_kmh"

        // Broadcast Extras
        const val EXTRA_SERVICE_STATE = "extra_service_state"
        const val EXTRA_CURRENT_LAT = "extra_current_lat"
        const val EXTRA_CURRENT_LNG = "extra_current_lng"
        const val EXTRA_DISTANCE_COVERED_METERS = "extra_distance_covered_meters"
        const val EXTRA_TARGET_DISTANCE_METERS = "extra_target_distance_meters"
        const val EXTRA_CURRENT_SPEED_KMH = "extra_current_speed_kmh"
        const val EXTRA_CURRENT_PACE_SECONDS = "extra_current_pace_seconds"
        const val EXTRA_CURRENT_LAP = "extra_current_lap"
        const val EXTRA_IS_FINISHED = "extra_is_finished"
        const val EXTRA_ERROR_MESSAGE = "extra_error_message"

        @Volatile
        var currentState: Int = STATE_IDLE
            private set

        val isServiceRunning: Boolean
            get() = currentState != STATE_IDLE

        val isWalking: Boolean
            get() = currentState == STATE_WALKING
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var activeJob: Job? = null

    private lateinit var locationManager: LocationManager
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var wakeLock: PowerManager.WakeLock? = null

    private var isTestProviderAdded = false
    private var isForegroundStarted = false

    @Volatile
    private var lastAnchorOrWalkPosition: LatLng? = null

    @Volatile
    private var lastAccumulatedDistanceMeters: Double = 0.0

    @Volatile
    private var lastTargetDistanceMeters: Double = 3000.0

    @Volatile
    private var lastLap: Int = 1

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Acquire partial wake lock to prevent Samsung One UI CPU sleep while anchoring or walking
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MockWalk::SimulationWakeLock").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L) // Max 12 hours safety timeout
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ANCHOR_AT_START -> {
                val lat = intent.getDoubleExtra(EXTRA_ANCHOR_LAT, Double.NaN)
                val lng = intent.getDoubleExtra(EXTRA_ANCHOR_LNG, Double.NaN)

                val anchorPoint: LatLng? = if (!lat.isNaN() && !lng.isNaN()) {
                    LatLng(lat, lng)
                } else {
                    val routePoints = getParcelableList(intent, EXTRA_ROUTE_POINTS)
                    val waypoints = getParcelableList(intent, EXTRA_WAYPOINTS)
                    routePoints?.firstOrNull() ?: waypoints?.firstOrNull() ?: lastAnchorOrWalkPosition
                }

                if (anchorPoint != null) {
                    lastAnchorOrWalkPosition = anchorPoint
                    ensureForegroundStarted(
                        state = STATE_ANCHORED,
                        currentLap = 1,
                        distanceCoveredMeters = 0.0,
                        targetDistanceMeters = lastTargetDistanceMeters,
                        speedKmh = 0.0,
                        anchorPoint = anchorPoint
                    )
                    startAnchorLoop(anchorPoint, isAfterWalkFinished = false)
                }
            }

            ACTION_START -> {
                val routePoints = getParcelableList(intent, EXTRA_ROUTE_POINTS)
                val waypoints = getParcelableList(intent, EXTRA_WAYPOINTS)
                val closeLoop = intent.getBooleanExtra(EXTRA_CLOSE_LOOP, true)
                val targetDistanceKm = intent.getDoubleExtra(EXTRA_TARGET_DISTANCE_KM, 3.0)
                val minSpeedKmh = intent.getDoubleExtra(EXTRA_MIN_SPEED_KMH, 4.0)
                val maxSpeedKmh = intent.getDoubleExtra(EXTRA_MAX_SPEED_KMH, 6.0)

                if (!routePoints.isNullOrEmpty() || !waypoints.isNullOrEmpty()) {
                    lastTargetDistanceMeters = targetDistanceKm * 1000.0
                    ensureForegroundStarted(
                        state = STATE_WALKING,
                        currentLap = 1,
                        distanceCoveredMeters = 0.0,
                        targetDistanceMeters = lastTargetDistanceMeters,
                        speedKmh = minSpeedKmh,
                        anchorPoint = routePoints?.firstOrNull() ?: waypoints?.firstOrNull()
                    )
                    startWalkingSimulation(
                        initialRoutePoints = routePoints,
                        fallbackWaypoints = waypoints,
                        closeLoop = closeLoop,
                        targetDistanceKm = targetDistanceKm,
                        minSpeedKmh = minSpeedKmh,
                        maxSpeedKmh = maxSpeedKmh
                    )
                }
            }

            ACTION_STOP_WALK -> {
                // Stop walking movement, but keep GPS anchored at the current coordinate so Strava won't jump home
                val holdPoint = lastAnchorOrWalkPosition
                if (holdPoint != null) {
                    startAnchorLoop(holdPoint, isAfterWalkFinished = false)
                } else {
                    stopAllMockAndExit()
                }
            }

            ACTION_EXIT_MOCK -> {
                stopAllMockAndExit()
            }
        }
        return START_NOT_STICKY
    }

    private fun getParcelableList(intent: Intent, key: String): ArrayList<LatLng>? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(key, LatLng::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra(key)
        }
    }

    private fun ensureForegroundStarted(
        state: Int,
        currentLap: Int,
        distanceCoveredMeters: Double,
        targetDistanceMeters: Double,
        speedKmh: Double,
        anchorPoint: LatLng?
    ) {
        val notification = buildNotification(
            state = state,
            currentLap = currentLap,
            distanceCoveredMeters = distanceCoveredMeters,
            targetDistanceMeters = targetDistanceMeters,
            speedKmh = speedKmh,
            anchorPoint = anchorPoint
        )
        if (!isForegroundStarted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isForegroundStarted = true
        } else {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(
        state: Int,
        currentLap: Int,
        distanceCoveredMeters: Double,
        targetDistanceMeters: Double,
        speedKmh: Double,
        anchorPoint: LatLng?
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

        val stopWalkIntent = Intent(this, MockWalkService::class.java).apply {
            action = ACTION_STOP_WALK
        }
        val stopWalkPendingIntent = PendingIntent.getService(
            this,
            1,
            stopWalkIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val exitMockIntent = Intent(this, MockWalkService::class.java).apply {
            action = ACTION_EXIT_MOCK
        }
        val exitMockPendingIntent = PendingIntent.getService(
            this,
            2,
            exitMockIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title: String
        val contentText: String

        if (state == STATE_ANCHORED) {
            title = getString(R.string.notification_title_anchored)
            contentText = if (anchorPoint != null) {
                String.format(
                    Locale.US,
                    "Tọa độ [1]: %.5f, %.5f • Speed: 0.0 km/h (1Hz Lock)",
                    anchorPoint.latitude, anchorPoint.longitude
                )
            } else {
                "Đang giữ vị trí GPS giả lập tại chỗ (Speed: 0.0 km/h)"
            }
        } else {
            title = getString(R.string.notification_title)
            val distCoveredKm = distanceCoveredMeters / 1000.0
            val targetKm = targetDistanceMeters / 1000.0
            val paceSec = if (speedKmh > 0.1) (3600.0 / speedKmh).toInt() else 0
            val paceMin = paceSec / 60
            val paceRemSec = paceSec % 60

            contentText = String.format(
                Locale.US,
                "Lap %d • %.2f/%.2f km • %.1f km/h • %d'%02d\"/km",
                currentLap, distCoveredKm, targetKm, speedKmh, paceMin, paceRemSec
            )
        }

        val builder = NotificationCompat.Builder(this, MockWalkApplication.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_walk)
            .setContentTitle(title)
            .setContentText(contentText)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (state == STATE_WALKING) {
            builder.addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.action_stop_walk),
                stopWalkPendingIntent
            )
        }
        builder.addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            getString(R.string.action_exit_mock),
            exitMockPendingIntent
        )

        return builder.build()
    }

    private fun setupMockLocationProviders(): Boolean {
        return try {
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
                // Provider already registered
            }

            locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
            isTestProviderAdded = true

            fusedLocationClient.setMockMode(true)
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

    /**
     * Runs a continuous 1Hz stationary GPS lock at [anchorPoint] with realistic micro-drift (+/-0.25m)
     * so the phone's hardware GPS chip cannot override the position back to the user's real house.
     */
    private fun startAnchorLoop(anchorPoint: LatLng, isAfterWalkFinished: Boolean) {
        activeJob?.cancel()

        if (!setupMockLocationProviders()) {
            stopAllMockAndExit()
            return
        }

        currentState = STATE_ANCHORED
        lastAnchorOrWalkPosition = anchorPoint

        activeJob = serviceScope.launch {
            Log.d(TAG, "Entered STATE_ANCHORED at $anchorPoint")
            var tick = 0L
            val baseAltitude = 18.0

            ensureForegroundStarted(
                state = STATE_ANCHORED,
                currentLap = lastLap,
                distanceCoveredMeters = lastAccumulatedDistanceMeters,
                targetDistanceMeters = lastTargetDistanceMeters,
                speedKmh = 0.0,
                anchorPoint = anchorPoint
            )

            while (isActive && currentState == STATE_ANCHORED) {
                // Stationary micro-drift (+/- 0.25m) to mimic a real phone held still at the start line
                val microDistMeters = Random.nextDouble(0.0, 0.25)
                val microBearingDeg = Random.nextDouble(0.0, 360.0)
                val driftedCoord = GeoUtils.computeDestination(anchorPoint, microDistMeters, microBearingDeg)
                val altitude = baseAltitude + Random.nextDouble(-0.08, 0.08)

                injectMockLocation(
                    lat = driftedCoord.latitude,
                    lng = driftedCoord.longitude,
                    alt = altitude,
                    speedMps = 0.0f,
                    bearingDeg = 0.0f,
                    accuracyMeters = Random.nextDouble(3.0, 4.2).toFloat()
                )

                broadcastProgress(
                    state = STATE_ANCHORED,
                    lat = driftedCoord.latitude,
                    lng = driftedCoord.longitude,
                    distanceCoveredMeters = lastAccumulatedDistanceMeters,
                    targetDistanceMeters = lastTargetDistanceMeters,
                    speedKmh = 0.0,
                    paceSeconds = 0,
                    currentLap = lastLap,
                    isFinished = isAfterWalkFinished
                )

                tick++
                if (tick % 5L == 0L) {
                    ensureForegroundStarted(
                        state = STATE_ANCHORED,
                        currentLap = lastLap,
                        distanceCoveredMeters = lastAccumulatedDistanceMeters,
                        targetDistanceMeters = lastTargetDistanceMeters,
                        speedKmh = 0.0,
                        anchorPoint = anchorPoint
                    )
                }

                val delayMs = (1000 + Random.nextLong(-20, 21)).coerceAtLeast(900)
                delay(delayMs)
            }
        }
    }

    /**
     * Transitions smoothly into [STATE_WALKING] along the OSRM route points.
     */
    private fun startWalkingSimulation(
        initialRoutePoints: List<LatLng>?,
        fallbackWaypoints: List<LatLng>?,
        closeLoop: Boolean,
        targetDistanceKm: Double,
        minSpeedKmh: Double,
        maxSpeedKmh: Double
    ) {
        activeJob?.cancel()

        if (!setupMockLocationProviders()) {
            stopAllMockAndExit()
            return
        }

        currentState = STATE_WALKING

        activeJob = serviceScope.launch {
            val baseRoutePoints: List<LatLng> = if (!initialRoutePoints.isNullOrEmpty() && initialRoutePoints.size >= 2) {
                initialRoutePoints
            } else if (!fallbackWaypoints.isNullOrEmpty()) {
                val helper = OsrmRouteHelper()
                helper.getRouteFromWaypoints(fallbackWaypoints, closeLoop, targetDistanceKm).routePoints
            } else {
                emptyList()
            }

            if (baseRoutePoints.size < 2) {
                broadcastError("Lộ trình không hợp lệ (cần ít nhất 2 điểm tọa độ).")
                stopAllMockAndExit()
                return@launch
            }

            var activeRoutePoints: List<LatLng> = baseRoutePoints.toList()

            val targetDistanceMeters = targetDistanceKm * 1000.0
            lastTargetDistanceMeters = targetDistanceMeters

            val minSpeedMps = minSpeedKmh / 3.6
            val maxSpeedMps = maxSpeedKmh / 3.6

            var currentSpeedMps = (minSpeedMps + maxSpeedMps) / 2.0
            var accumulatedDistanceMeters = 0.0
            lastAccumulatedDistanceMeters = 0.0

            var currentLap = 1
            lastLap = 1
            var currentSegmentIndex = 0
            var distanceIntoCurrentSegment = 0.0

            var currentBearing = GeoUtils.computeBearing(activeRoutePoints[0], activeRoutePoints[1])
            var previousRoadBearing = currentBearing

            val baseAltitude = 18.0
            var tickCount = 0L
            var slowdownTicksRemaining = 0

            // Per-tick dynamic lateral drift in meters [-1.5m, +1.5m] via smooth Random Walk
            var currentLateralDrift = Random.nextDouble(-0.6, 0.6)

            var lastTickTimeNanos = SystemClock.elapsedRealtimeNanos()
            lastAnchorOrWalkPosition = activeRoutePoints.first()

            while (isActive && currentState == STATE_WALKING && accumulatedDistanceMeters < targetDistanceMeters) {
                val jitterMs = (1000 + Random.nextLong(-30, 31)).coerceAtLeast(850)
                delay(jitterMs)

                val nowNanos = SystemClock.elapsedRealtimeNanos()
                val dtSeconds = ((nowNanos - lastTickTimeNanos) / 1_000_000_000.0).coerceIn(0.5, 2.0)
                lastTickTimeNanos = nowNanos
                tickCount++

                // Corner Slowdown detection (> 35 degrees between current and next segment)
                val segIdx = currentSegmentIndex.coerceIn(0, activeRoutePoints.size - 2)
                val curStart = activeRoutePoints[segIdx]
                val curEnd = activeRoutePoints[segIdx + 1]
                val curSegLength = GeoUtils.computeDistanceMeters(curStart, curEnd)
                val curSegBearing = if (curSegLength > 0.01) GeoUtils.computeBearing(curStart, curEnd) else previousRoadBearing

                val nextSegBearing = when {
                    segIdx + 2 < activeRoutePoints.size -> {
                        GeoUtils.computeBearing(activeRoutePoints[segIdx + 1], activeRoutePoints[segIdx + 2])
                    }
                    closeLoop && activeRoutePoints.size >= 3 -> {
                        GeoUtils.computeBearing(activeRoutePoints[0], activeRoutePoints[1])
                    }
                    else -> curSegBearing
                }

                val upcomingTurnAngle = GeoUtils.computeAngleDiff(curSegBearing, nextSegBearing)
                val justTurnedAngle = GeoUtils.computeAngleDiff(previousRoadBearing, curSegBearing)
                val distRemainingInSeg = (curSegLength - distanceIntoCurrentSegment).coerceAtLeast(0.0)

                val isCornerSlowdown = (upcomingTurnAngle > 35.0 && distRemainingInSeg <= 10.0) || (justTurnedAngle > 35.0)

                // 1. Inertial Speed Random Walk, Cadence Wave & Corner Slowdown
                val cadenceFrequency = 1.8 // Hz
                val cadenceWave = sin(2.0 * Math.PI * cadenceFrequency * (tickCount * dtSeconds)) * 0.035
                val randomAccel = Random.nextDouble(-0.12, 0.12)

                if (isCornerSlowdown) {
                    val cornerTargetSpeed = minSpeedMps + (maxSpeedMps - minSpeedMps) * 0.12
                    currentSpeedMps = (currentSpeedMps - 0.22 * dtSeconds).coerceAtLeast(minSpeedMps)
                    if (currentSpeedMps > cornerTargetSpeed) {
                        currentSpeedMps = (currentSpeedMps * 0.85 + cornerTargetSpeed * 0.15)
                            .coerceIn(minSpeedMps, maxSpeedMps)
                    }
                } else if (slowdownTicksRemaining > 0) {
                    slowdownTicksRemaining--
                    currentSpeedMps = (currentSpeedMps - 0.18 * dtSeconds).coerceAtLeast(minSpeedMps)
                } else if (Random.nextDouble(0.0, 1.0) < 0.015) {
                    slowdownTicksRemaining = Random.nextInt(2, 4)
                } else {
                    currentSpeedMps = (currentSpeedMps + (randomAccel + cadenceWave) * dtSeconds)
                        .coerceIn(minSpeedMps, maxSpeedMps)
                }

                // 2. Multi-Lap Segment Interpolation along OSRM Polyline
                val stepDistMeters = currentSpeedMps * dtSeconds
                accumulatedDistanceMeters += stepDistMeters
                lastAccumulatedDistanceMeters = accumulatedDistanceMeters

                var distanceToAdvance = stepDistMeters
                var safetyCounter = 0
                while (distanceToAdvance > 0.0 && safetyCounter < 10000) {
                    safetyCounter++

                    if (currentSegmentIndex >= activeRoutePoints.size - 1) {
                        if (accumulatedDistanceMeters < targetDistanceMeters) {
                            if (closeLoop) {
                                currentSegmentIndex = 0
                                distanceIntoCurrentSegment = 0.0
                                currentLap++
                            } else {
                                activeRoutePoints = activeRoutePoints.reversed()
                                currentSegmentIndex = 0
                                distanceIntoCurrentSegment = 0.0
                                currentLap++
                            }
                            lastLap = currentLap
                            Log.d(TAG, "Transitioned to Lap $currentLap (closeLoop=$closeLoop)")
                        } else {
                            break
                        }
                    }

                    val p1 = activeRoutePoints[currentSegmentIndex]
                    val p2 = activeRoutePoints[currentSegmentIndex + 1]
                    val segLength = GeoUtils.computeDistanceMeters(p1, p2)

                    if (segLength < 0.01) {
                        currentSegmentIndex++
                        distanceIntoCurrentSegment = 0.0
                        continue
                    }

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

                // Base road coordinate on current segment
                val activeIdx = currentSegmentIndex.coerceIn(0, activeRoutePoints.size - 2)
                val pStart = activeRoutePoints[activeIdx]
                val pEnd = activeRoutePoints[activeIdx + 1]
                val segLength = GeoUtils.computeDistanceMeters(pStart, pEnd).coerceAtLeast(0.001)
                val fraction = (distanceIntoCurrentSegment / segLength).coerceIn(0.0, 1.0)

                val baseRoadCoord = GeoUtils.interpolate(pStart, pEnd, fraction)

                // 3. Realistic Heading & Bearing with natural noise (+/- 2.0 deg)
                val rawBearing = if (pStart != pEnd) GeoUtils.computeBearing(pStart, pEnd) else currentBearing
                previousRoadBearing = rawBearing
                val bearingNoise = Random.nextDouble(-2.0, 2.0)
                currentBearing = ((rawBearing + bearingNoise) + 360.0) % 360.0

                // 4. Per-Tick Dynamic Lateral Drift [-1.5m, +1.5m] perpendicular to road
                val driftDelta = Random.nextDouble(-0.35, 0.35)
                val meanReversion = -currentLateralDrift * 0.08
                currentLateralDrift = (currentLateralDrift + driftDelta + meanReversion).coerceIn(-1.5, 1.5)

                val realisticCoordinate = GeoUtils.applyLateralOffset(
                    point = baseRoadCoord,
                    headingBearing = rawBearing,
                    offsetMeters = currentLateralDrift
                )
                lastAnchorOrWalkPosition = realisticCoordinate

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
                    bearingDeg = currentBearing.toFloat(),
                    accuracyMeters = Random.nextDouble(3.0, 5.5).toFloat()
                )

                val currentSpeedKmh = currentSpeedMps * 3.6
                val currentPaceSec = if (currentSpeedKmh > 0.1) (3600.0 / currentSpeedKmh).toInt() else 0

                if (tickCount % 2L == 0L) {
                    ensureForegroundStarted(
                        state = STATE_WALKING,
                        currentLap = currentLap,
                        distanceCoveredMeters = accumulatedDistanceMeters,
                        targetDistanceMeters = targetDistanceMeters,
                        speedKmh = currentSpeedKmh,
                        anchorPoint = null
                    )
                }

                val isFinished = accumulatedDistanceMeters >= targetDistanceMeters
                broadcastProgress(
                    state = if (isFinished) STATE_ANCHORED else STATE_WALKING,
                    lat = realisticCoordinate.latitude,
                    lng = realisticCoordinate.longitude,
                    distanceCoveredMeters = accumulatedDistanceMeters,
                    targetDistanceMeters = targetDistanceMeters,
                    speedKmh = currentSpeedKmh,
                    paceSeconds = currentPaceSec,
                    currentLap = currentLap,
                    isFinished = isFinished
                )

                if (isFinished) {
                    Log.d(TAG, "Target distance reached! Holding GPS anchor at final position $realisticCoordinate")
                    startAnchorLoop(realisticCoordinate, isAfterWalkFinished = true)
                    return@launch
                }
            }
        }
    }

    private fun injectMockLocation(
        lat: Double,
        lng: Double,
        alt: Double,
        speedMps: Float,
        bearingDeg: Float,
        accuracyMeters: Float
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
                accuracy = accuracyMeters
                time = nowMs
                elapsedRealtimeNanos = elapsedNanos

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    speedAccuracyMetersPerSecond = 0.2f
                    bearingAccuracyDegrees = 5.0f
                    verticalAccuracyMeters = 2.0f
                }
            }

            if (isTestProviderAdded) {
                locationManager.setTestProviderLocation(LocationManager.GPS_PROVIDER, mockLocation)
            }

            fusedLocationClient.setMockLocation(mockLocation)

        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException during mock injection: ${e.message}")
            broadcastError(e.message ?: "Chưa cấp quyền Mock Location")
            stopAllMockAndExit()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inject mock location: ${e.message}", e)
        }
    }

    private fun broadcastProgress(
        state: Int,
        lat: Double,
        lng: Double,
        distanceCoveredMeters: Double,
        targetDistanceMeters: Double,
        speedKmh: Double,
        paceSeconds: Int,
        currentLap: Int,
        isFinished: Boolean
    ) {
        val intent = Intent(ACTION_UPDATE_PROGRESS).apply {
            putExtra(EXTRA_SERVICE_STATE, state)
            putExtra(EXTRA_CURRENT_LAT, lat)
            putExtra(EXTRA_CURRENT_LNG, lng)
            putExtra(EXTRA_DISTANCE_COVERED_METERS, distanceCoveredMeters)
            putExtra(EXTRA_TARGET_DISTANCE_METERS, targetDistanceMeters)
            putExtra(EXTRA_CURRENT_SPEED_KMH, speedKmh)
            putExtra(EXTRA_CURRENT_PACE_SECONDS, paceSeconds)
            putExtra(EXTRA_CURRENT_LAP, currentLap)
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

    private fun stopAllMockAndExit() {
        activeJob?.cancel()
        activeJob = null
        currentState = STATE_IDLE

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

        isForegroundStarted = false
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAllMockAndExit()
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

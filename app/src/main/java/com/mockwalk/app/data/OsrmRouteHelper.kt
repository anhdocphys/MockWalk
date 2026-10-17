package com.mockwalk.app.data

import android.util.Log
import com.google.android.gms.maps.model.LatLng
import com.mockwalk.app.util.GeoUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Helper class to handle:
 * 1. Custom Multi-Waypoint routing (with optional loop closure P1 -> P2 -> ... -> Pn -> P1).
 * 2. Automatic random loop generation fallback when only 1 start point is selected.
 * 3. Querying the public OSRM Foot Routing API and parsing GeoJSON coordinates & distance metadata.
 */
class OsrmRouteHelper(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
) {

    companion object {
        private const val TAG = "OsrmRouteHelper"
        private const val OSRM_BASE_URL = "https://router.project-osrm.org/route/v1/foot/"
    }

    /**
     * Metadata & geometry for a single lap/traversal returned from OSRM.
     */
    data class RouteResult(
        val routePoints: List<LatLng>,
        val singleLapDistanceMeters: Double,
        val isFallback: Boolean = false
    )

    /**
     * Builds a realistic walking route from a list of user-selected [waypoints].
     * - If [waypoints] has only 1 point: falls back to generating a random polygon loop around that point.
     * - If [waypoints] has >= 2 points and [closeLoop] == true: appends [waypoints.first()] to close the loop.
     * - Queries OSRM foot routing API and returns single-lap coordinates and distance.
     */
    suspend fun getRouteFromWaypoints(
        waypoints: List<LatLng>,
        closeLoop: Boolean,
        fallbackTargetDistanceKm: Double = 3.0
    ): RouteResult = withContext(Dispatchers.IO) {
        require(waypoints.isNotEmpty()) { "Waypoints list cannot be empty" }

        // Prepare effective waypoints to send to OSRM
        val effectiveWaypoints: List<LatLng> = if (waypoints.size == 1) {
            val targetMeters = (fallbackTargetDistanceKm * 1000.0).coerceAtLeast(500.0)
            Log.d(TAG, "Single waypoint provided. Generating random loop around ${waypoints.first()}")
            generateLoopWaypoints(waypoints.first(), targetMeters)
        } else {
            val list = waypoints.toMutableList()
            if (closeLoop) {
                val first = list.first()
                val last = list.last()
                // Append first point if not already closed
                if (GeoUtils.computeDistanceMeters(first, last) > 5.0) {
                    list.add(first)
                }
            }
            list
        }

        Log.d(TAG, "Requesting route with ${effectiveWaypoints.size} waypoints (closeLoop=$closeLoop)")

        val fetchedResult = try {
            fetchOsrmRoute(effectiveWaypoints)
        } catch (e: Exception) {
            Log.w(TAG, "OSRM query failed: ${e.message}. Using dense linear fallback route.", e)
            null
        }

        if (fetchedResult != null && fetchedResult.routePoints.size >= 2) {
            val dist = if (fetchedResult.singleLapDistanceMeters > 0.0) {
                fetchedResult.singleLapDistanceMeters
            } else {
                GeoUtils.computeTotalPathDistance(fetchedResult.routePoints)
            }
            Log.d(TAG, "OSRM route ready: ${fetchedResult.routePoints.size} points, 1-lap distance: ${dist.roundToInt()}m")
            RouteResult(
                routePoints = fetchedResult.routePoints,
                singleLapDistanceMeters = dist,
                isFallback = false
            )
        } else {
            val fallbackPoints = createDenseFallbackPath(effectiveWaypoints)
            val fallbackDistance = GeoUtils.computeTotalPathDistance(fallbackPoints)
            Log.d(TAG, "Fallback route ready: ${fallbackPoints.size} points, 1-lap distance: ${fallbackDistance.roundToInt()}m")
            RouteResult(
                routePoints = fallbackPoints,
                singleLapDistanceMeters = fallbackDistance,
                isFallback = true
            )
        }
    }

    /**
     * Legacy helper for 1-point automatic loop generation.
     */
    suspend fun getRealisticWalkRoute(
        startPoint: LatLng,
        targetDistanceKm: Double
    ): RouteResult = getRouteFromWaypoints(
        waypoints = listOf(startPoint),
        closeLoop = true,
        fallbackTargetDistanceKm = targetDistanceKm
    )

    /**
     * Generates a circular / polygonal series of waypoints starting and ending at [startPoint].
     * Radius is estimated based on the target distance and urban street winding factor (~1.35).
     */
    fun generateLoopWaypoints(startPoint: LatLng, targetDistanceMeters: Double): List<LatLng> {
        val waypoints = mutableListOf<LatLng>()
        waypoints.add(startPoint)

        // Estimated circumference of polygon considering road tortuosity
        val effectivePerimeter = targetDistanceMeters / 1.35
        val radiusMeters = (effectivePerimeter / (2.0 * Math.PI)).coerceIn(120.0, 5000.0)

        // Choose a random direction for loop center
        val centerBearing = Random.nextDouble(0.0, 360.0)
        val loopCenter = GeoUtils.computeDestination(startPoint, radiusMeters, centerBearing)

        // Number of vertices for the loop
        val numVertices = 6
        val baseAngleStep = 360.0 / numVertices
        val startAngle = (centerBearing + 180.0) % 360.0

        for (i in 1 until numVertices) {
            val angle = (startAngle + i * baseAngleStep + Random.nextDouble(-15.0, 15.0) + 360.0) % 360.0
            val vertexRadius = radiusMeters * Random.nextDouble(0.85, 1.15)
            val wp = GeoUtils.computeDestination(loopCenter, vertexRadius, angle)
            waypoints.add(wp)
        }

        // Return to start point to complete the loop
        waypoints.add(startPoint)
        return waypoints
    }

    /**
     * Executes HTTP GET request to OSRM Foot Routing API and parses GeoJSON coordinates + distance.
     * Note: OSRM coordinate order is {longitude},{latitude}.
     */
    private fun fetchOsrmRoute(waypoints: List<LatLng>): RouteResult? {
        val coordString = waypoints.joinToString(";") {
            String.format(Locale.US, "%.6f,%.6f", it.longitude, it.latitude)
        }

        val url = "$OSRM_BASE_URL$coordString?overview=full&geometries=geojson&steps=false"
        Log.d(TAG, "GET OSRM URL: $url")

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "MockWalkAndroid/2.0 (StravaMultiLapSimulator)")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.e(TAG, "OSRM response error: HTTP ${response.code}")
                return null
            }

            val body = response.body?.string() ?: return null
            val json = JSONObject(body)

            if (!json.has("code") || json.getString("code") != "Ok") {
                Log.e(TAG, "OSRM returned non-Ok code: ${json.optString("code")}")
                return null
            }

            val routes = json.getJSONArray("routes")
            if (routes.length() == 0) return null

            val primaryRoute = routes.getJSONObject(0)
            val distanceMeters = primaryRoute.optDouble("distance", 0.0)
            val geometry = primaryRoute.getJSONObject("geometry")
            val coordinates = geometry.getJSONArray("coordinates")

            val points = ArrayList<LatLng>(coordinates.length())
            for (i in 0 until coordinates.length()) {
                val coord = coordinates.getJSONArray(i)
                val lng = coord.getDouble(0)
                val lat = coord.getDouble(1)
                points.add(LatLng(lat, lng))
            }

            return RouteResult(
                routePoints = points,
                singleLapDistanceMeters = distanceMeters,
                isFallback = false
            )
        }
    }

    /**
     * Resilient fallback in case OSRM service is unreachable:
     * Dense linear interpolation between waypoints so simulation continues smoothly.
     */
    private fun createDenseFallbackPath(waypoints: List<LatLng>): List<LatLng> {
        val path = mutableListOf<LatLng>()
        val stepSizeMeters = 15.0

        for (i in 0 until waypoints.size - 1) {
            val p1 = waypoints[i]
            val p2 = waypoints[i + 1]
            val dist = GeoUtils.computeDistanceMeters(p1, p2)
            val steps = (dist / stepSizeMeters).roundToInt().coerceAtLeast(1)

            for (s in 0 until steps) {
                val fraction = s.toDouble() / steps
                path.add(GeoUtils.interpolate(p1, p2, fraction))
            }
        }
        if (waypoints.isNotEmpty()) {
            path.add(waypoints.last())
        }
        return path
    }
}

package com.mockwalk.app.data

import android.util.Log
import com.google.android.gms.maps.model.LatLng
import com.mockwalk.app.util.GeoUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.*
import kotlin.random.Random

/**
 * Helper class to generate loop waypoints, query the public OSRM routing API (foot profile),
 * and parse GeoJSON coordinates into a realistic road-following polyline.
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
     * Result containing the decoded route points and actual total distance in meters.
     */
    data class RouteResult(
        val points: List<LatLng>,
        val totalDistanceMeters: Double,
        val isFallback: Boolean = false
    )

    /**
     * Main entry point: Generates waypoints around [startPoint] based on [targetDistanceKm],
     * queries OSRM, parses coordinates, and extends/loops the route if necessary to meet target distance.
     */
    suspend fun getRealisticWalkRoute(
        startPoint: LatLng,
        targetDistanceKm: Double
    ): RouteResult = withContext(Dispatchers.IO) {
        val targetDistanceMeters = targetDistanceKm * 1000.0

        // 1. Generate waypoints forming a loop
        val waypoints = generateLoopWaypoints(startPoint, targetDistanceMeters)
        Log.d(TAG, "Generated ${waypoints.size} waypoints for loop around $startPoint")

        // 2. Fetch OSRM route
        val fetchedResult = try {
            fetchOsrmRoute(waypoints)
        } catch (e: Exception) {
            Log.w(TAG, "OSRM query failed: ${e.message}. Using synthetic fallback route.", e)
            null
        }

        val basePoints: List<LatLng>
        val baseDistance: Double
        val isFallback: Boolean

        if (fetchedResult != null && fetchedResult.points.size >= 2) {
            basePoints = fetchedResult.points
            baseDistance = fetchedResult.totalDistanceMeters
            isFallback = false
        } else {
            // Resilient fallback: interpolate between waypoints directly
            basePoints = createDenseFallbackPath(waypoints)
            baseDistance = GeoUtils.computeTotalPathDistance(basePoints)
            isFallback = true
        }

        // 3. If route is shorter than target distance, loop the route points until distance >= target
        val finalPoints = ensureTargetDistanceMet(basePoints, baseDistance, targetDistanceMeters)
        val finalDistance = GeoUtils.computeTotalPathDistance(finalPoints)

        Log.d(TAG, "Final route ready: ${finalPoints.size} points, total distance: ${finalDistance.roundToInt()}m (target: ${targetDistanceMeters.roundToInt()}m)")
        RouteResult(finalPoints, finalDistance, isFallback)
    }

    /**
     * Generates a circular / polygonal series of waypoints starting and ending at [startPoint].
     * Radius is estimated based on the target distance and urban street winding factor (~1.35).
     */
    fun generateLoopWaypoints(startPoint: LatLng, targetDistanceMeters: Double): List<LatLng> {
        val waypoints = mutableListOf<LatLng>()
        waypoints.add(startPoint)

        // Estimated circumference of polygon considering road tortuosity
        val effectivePerimeter = targetDistanceMeters / 1.35
        val radiusMeters = (effectivePerimeter / (2.0 * Math.PI)).coerceIn(150.0, 5000.0)

        // Choose a random direction for loop center
        val centerBearing = Random.nextDouble(0.0, 360.0)
        val loopCenter = GeoUtils.computeDestination(startPoint, radiusMeters, centerBearing)

        // Number of vertices for the loop (between 5 and 7 points)
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
     * Executes HTTP GET request to OSRM Foot Routing API and parses GeoJSON coordinates.
     * Note: OSRM coordinate order is {longitude},{latitude}.
     */
    private fun fetchOsrmRoute(waypoints: List<LatLng>): RouteResult? {
        // Construct coordinates string: "lng1,lat1;lng2,lat2;..."
        val coordString = waypoints.joinToString(";") {
            String.format(Locale.US, "%.6f,%.6f", it.longitude, it.latitude)
        }

        val url = "$OSRM_BASE_URL$coordString?overview=full&geometries=geojson&steps=false"
        Log.d(TAG, "Requesting OSRM: $url")

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "MockWalkAndroid/1.0 (StravaWalkSimulator)")
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

            return RouteResult(points, distanceMeters)
        }
    }

    /**
     * If the returned route from OSRM is shorter than the desired target distance,
     * seamlessly loops the route coordinates until the cumulative distance reaches or exceeds target.
     */
    private fun ensureTargetDistanceMet(
        basePoints: List<LatLng>,
        baseDistance: Double,
        targetDistanceMeters: Double
    ): List<LatLng> {
        if (basePoints.size < 2) return basePoints
        if (baseDistance >= targetDistanceMeters) return basePoints

        val result = mutableListOf<LatLng>()
        result.addAll(basePoints)
        var accumulated = baseDistance

        var loopCount = 1
        while (accumulated < targetDistanceMeters && loopCount < 50) {
            // Append basePoints starting from index 1 (skipping duplicate connecting node)
            for (i in 1 until basePoints.size) {
                result.add(basePoints[i])
                accumulated += GeoUtils.computeDistanceMeters(basePoints[i - 1], basePoints[i])
                if (accumulated >= targetDistanceMeters) break
            }
            loopCount++
        }

        return result
    }

    /**
     * Resilient fallback in case OSRM service is unreachable:
     * Dense linear interpolation between waypoints so simulation continues smoothly.
     */
    private fun createDenseFallbackPath(waypoints: List<LatLng>): List<LatLng> {
        val path = mutableListOf<LatLng>()
        val stepSizeMeters = 20.0

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

package com.mockwalk.app.util

import com.google.android.gms.maps.model.LatLng
import kotlin.math.*

/**
 * Geometric and Spherical Geodesic utilities for natural GPS navigation.
 */
object GeoUtils {

    const val EARTH_RADIUS_METERS = 6371000.0

    /**
     * Compute Haversine distance between two coordinates in meters.
     */
    fun computeDistanceMeters(p1: LatLng, p2: LatLng): Double {
        val lat1 = Math.toRadians(p1.latitude)
        val lon1 = Math.toRadians(p1.longitude)
        val lat2 = Math.toRadians(p2.latitude)
        val lon2 = Math.toRadians(p2.longitude)

        val dLat = lat2 - lat1
        val dLon = lon2 - lon1

        val a = sin(dLat / 2).pow(2.0) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_METERS * c
    }

    /**
     * Compute initial compass bearing in degrees [0, 360) from p1 to p2.
     */
    fun computeBearing(p1: LatLng, p2: LatLng): Double {
        val lat1 = Math.toRadians(p1.latitude)
        val lon1 = Math.toRadians(p1.longitude)
        val lat2 = Math.toRadians(p2.latitude)
        val lon2 = Math.toRadians(p2.longitude)

        val dLon = lon2 - lon1
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)

        val initialBearing = Math.toDegrees(atan2(y, x))
        return (initialBearing + 360.0) % 360.0
    }

    /**
     * Compute destination point given start coordinate, distance in meters, and bearing in degrees.
     */
    fun computeDestination(start: LatLng, distanceMeters: Double, bearingDegrees: Double): LatLng {
        val distRatio = distanceMeters / EARTH_RADIUS_METERS
        val bearingRad = Math.toRadians(bearingDegrees)

        val lat1 = Math.toRadians(start.latitude)
        val lon1 = Math.toRadians(start.longitude)

        val lat2 = asin(sin(lat1) * cos(distRatio) + cos(lat1) * sin(distRatio) * cos(bearingRad))
        val lon2 = lon1 + atan2(
            sin(bearingRad) * sin(distRatio) * cos(lat1),
            cos(distRatio) - sin(lat1) * sin(lat2)
        )

        return LatLng(Math.toDegrees(lat2), Math.toDegrees(lon2))
    }

    /**
     * Linear interpolation between two coordinates by fraction [0.0, 1.0].
     */
    fun interpolate(p1: LatLng, p2: LatLng, fraction: Double): LatLng {
        val f = fraction.coerceIn(0.0, 1.0)
        val lat = p1.latitude + f * (p2.latitude - p1.latitude)
        val lon = p1.longitude + f * (p2.longitude - p1.longitude)
        return LatLng(lat, lon)
    }

    /**
     * Apply lateral (perpendicular) offset in meters relative to travel bearing.
     * Positive offset shifts right, negative shifts left.
     */
    fun applyLateralOffset(point: LatLng, headingBearing: Double, offsetMeters: Double): LatLng {
        if (abs(offsetMeters) < 0.001) return point
        val perpendicularBearing = (headingBearing + 90.0) % 360.0
        return computeDestination(point, offsetMeters, perpendicularBearing)
    }

    /**
     * Calculate cumulative length of a polyline in meters.
     */
    fun computeTotalPathDistance(path: List<LatLng>): Double {
        if (path.size < 2) return 0.0
        var total = 0.0
        for (i in 0 until path.size - 1) {
            total += computeDistanceMeters(path[i], path[i + 1])
        }
        return total
    }

    /**
     * Compute shortest angular difference in degrees [0, 180] between two bearings.
     */
    fun computeAngleDiff(bearing1: Double, bearing2: Double): Double {
        val diff = abs(bearing1 - bearing2) % 360.0
        return if (diff > 180.0) 360.0 - diff else diff
    }
}

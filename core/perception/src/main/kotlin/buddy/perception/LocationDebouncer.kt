package buddy.perception

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Decides whether a location sample is worth a ledger event. A phone sitting on a desk
 * produces a fix every few seconds; the ledger wants one when the user has actually
 * moved, or at a slow heartbeat so "where was I at 3pm" is always answerable.
 */
class LocationDebouncer(
    private val minDistanceMeters: Double = 75.0,
    private val heartbeatMillis: Long = 15 * 60 * 1000,
) {
    private var last: LocationSample? = null

    /** Returns true if the sample should be recorded, and remembers it if so. */
    fun accept(s: LocationSample): Boolean {
        val prev = last
        val keep = prev == null ||
            s.ts - prev.ts >= heartbeatMillis ||
            distanceMeters(prev.latitude, prev.longitude, s.latitude, s.longitude) >= minDistanceMeters
        if (keep) last = s
        return keep
    }

    companion object {
        private const val EARTH_RADIUS_M = 6_371_000.0

        fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
            return 2 * EARTH_RADIUS_M * asin(sqrt(a))
        }
    }
}

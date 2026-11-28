package com.whereareu.app.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GeoUtilsTest {

    /**
     * Changshu, Jiangsu — the fix that surfaced the offset bug. AMap reported
     * GCJ-02; the platform geocoder needs WGS-84 for the same physical point.
     */
    private val gcjLat = 31.67928704134287
    private val gcjLng = 120.794275757105

    @Test
    fun `gcj02 to wgs84 moves the point by a few hundred metres in china`() {
        val (lat, lng) = GeoUtils.gcj02ToWgs84(gcjLat, gcjLng)
        val moved = GeoUtils.distanceMeters(gcjLat, gcjLng, lat, lng)
        assertTrue("expected a 300-700m datum shift, got ${moved}m", moved in 300.0..700.0)
    }

    @Test
    fun `gcj02 to wgs84 lands on the known wgs84 value`() {
        val (lat, lng) = GeoUtils.gcj02ToWgs84(gcjLat, gcjLng)
        // Reference value from an iterative inverse of wgs84ToGcj02; the
        // single-pass approximation used here is within ~1 m of it.
        assertEquals(31.681253, lat, 0.00005)
        assertEquals(120.789911, lng, 0.00005)
    }

    @Test
    fun `coordinates outside china are returned unchanged`() {
        val (lat, lng) = GeoUtils.gcj02ToWgs84(48.8566, 2.3522)
        assertEquals(48.8566, lat, 1e-9)
        assertEquals(2.3522, lng, 1e-9)
    }

    @Test
    fun `distance between identical points is zero`() {
        assertTrue(abs(GeoUtils.distanceMeters(31.6, 120.7, 31.6, 120.7)) < 1e-9)
    }
}

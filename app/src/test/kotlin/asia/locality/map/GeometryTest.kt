package asia.locality.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeometryTest {
    @Test fun mercatorRoundTrips() {
        for (lat in listOf(-60.0, 0.0, 23.5, 45.0, 70.0)) {
            assertEquals(lat, MapData.latitude(MapData.mercator(lat)), 1e-9)
        }
    }

    @Test fun distanceMatchesKnownSpans() {
        // One degree of latitude is about 111.2 km on the mean-radius sphere.
        assertEquals(111.19, MapData.distance(120.0, 30.0, 120.0, 31.0), 0.05)
        assertEquals(0.0, MapData.distance(120.0, 30.0, 120.0, 30.0), 1e-9)
    }

    @Test fun bearingAndCardinalDirectionsFollowEightWinds() {
        assertEquals(0.0, MapData.bearing(118.0, 32.0, 118.0, 33.0), 0.1)
        assertEquals(0, MapData.cardinalIndex(MapData.bearing(118.0, 32.0, 118.0, 33.0)))
        assertEquals(2, MapData.cardinalIndex(MapData.bearing(118.0, 32.0, 119.0, 32.0)))
        assertEquals(4, MapData.cardinalIndex(MapData.bearing(118.0, 32.0, 118.0, 31.0)))
        assertEquals(6, MapData.cardinalIndex(MapData.bearing(118.0, 32.0, 117.0, 32.0)))
        assertEquals(1, MapData.cardinalIndex(45.0))
        assertEquals(7, MapData.cardinalIndex(315.0))
    }

    @Test fun searchIgnoresCaseAndDiacritics() {
        assertEquals("ha noi", MapData.normalizeSearch("Hà Nội"))
        assertEquals("d", MapData.normalizeSearch("Đ"))
    }

    @Test fun cameraBoundsFollowSpanAndAspect() {
        val camera = MapCamera(100.0, 30.0, 20.0, 1000, 500)
        assertEquals(90.0, camera.left, 1e-9)
        assertEquals(110.0, camera.right, 1e-9)
        assertEquals(35.0, camera.top, 1e-9)
        assertEquals(25.0, camera.bottom, 1e-9)
        assertTrue(camera == MapCamera(100.0, 30.0, 20.0, 1000, 500))
    }
}

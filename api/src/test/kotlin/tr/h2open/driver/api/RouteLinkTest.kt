package tr.h2open.driver.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteLinkTest {
    private fun stop(id: Long, sequence: Int, state: String = StopState.PENDING, lat: Double? = null, lng: Double? = null) = Stop(
        id = id, tripId = 3, sequence = sequence, state = state,
        partner = Partner(id, "Customer $id", street = "Atatürk Cd. $id", city = "Sultanköy", latitude = lat, longitude = lng),
    )

    @Test
    fun `routes through the pending stops in order, pins first, address otherwise`() {
        val url = RouteLink.googleMaps(listOf(
            stop(3, 30, lat = 41.1, lng = 28.2),
            stop(1, 10, lat = 41.0, lng = 28.0),
            stop(2, 20, state = StopState.DELIVERED, lat = 40.0, lng = 27.0),
            stop(4, 40),
        ))
        assertEquals(
            "https://www.google.com/maps/dir/?api=1&destination=Atat%C3%BCrk%20Cd.%204%2C%20Sultank%C3%B6y" +
                "&waypoints=41.0%2C28.0%7C41.1%2C28.2&travelmode=driving",
            url,
        )
    }

    @Test
    fun `one stop has no waypoints`() {
        assertEquals(
            "https://www.google.com/maps/dir/?api=1&destination=41.0%2C28.0&travelmode=driving",
            RouteLink.googleMaps(listOf(stop(1, 10, lat = 41.0, lng = 28.0))),
        )
    }

    @Test
    fun `nothing pending, no route`() {
        assertNull(RouteLink.googleMaps(listOf(stop(1, 10, state = StopState.DELIVERED))))
    }

    @Test
    fun `long trips route the next stops only`() {
        val url = RouteLink.googleMaps((1..15L).map { stop(it, it.toInt(), lat = 41.0 + it / 100.0, lng = 28.0) })!!
        assertEquals(RouteLink.MAX_STOPS - 1, url.substringAfter("waypoints=").substringBefore("&").split("%7C").size)
        assertEquals("41.1%2C28.0", url.substringAfter("destination=").substringBefore("&"))
    }
}

package tr.h2open.driver.api

import java.net.URLEncoder

/**
 * Google Maps directions through a trip's pending stops, as a plain Maps
 * URL: opening it in the Maps app costs nothing (no Google API key, no
 * billing). The route starts where the phone is.
 */
object RouteLink {
    /** Google Maps takes a destination plus 9 waypoints on a phone. */
    const val MAX_STOPS = 10

    /** The pending stops in driving order; null when none is left. Past [MAX_STOPS], only the next ones. */
    fun googleMaps(stops: List<Stop>): String? {
        val places = stops.filter { it.isPending }.sortedBy { it.sequence }.take(MAX_STOPS).map(::place)
        if (places.isEmpty()) return null
        val query = buildList {
            add("api=1")
            add("destination=" + encode(places.last()))
            if (places.size > 1) add("waypoints=" + places.dropLast(1).joinToString("%7C", transform = ::encode))
            add("travelmode=driving")
        }
        return "https://www.google.com/maps/dir/?" + query.joinToString("&")
    }

    /** The customer's pin, or the address text when the office has not pinned it yet. */
    private fun place(stop: Stop): String {
        val lat = stop.partner.latitude
        val lng = stop.partner.longitude
        return if (lat != null && lng != null) "$lat,$lng" else stop.partner.address
    }

    private fun encode(text: String): String = URLEncoder.encode(text, Charsets.UTF_8).replace("+", "%20")
}

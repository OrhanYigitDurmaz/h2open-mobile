package tr.h2open.driver.ui.map

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import tr.h2open.driver.api.Partner
import tr.h2open.driver.api.RouteLink
import tr.h2open.driver.api.Stop
import tr.h2open.driver.data.ErrorLog

/*
 * Handing a stop or a route to the maps app. Opening Google Maps (or any maps
 * app, or the browser) costs nothing: no Google API is called.
 */

private const val GOOGLE_MAPS = "com.google.android.apps.maps"

/**
 * Starts Google Maps turn-by-turn to the customer's pin. Without Google Maps,
 * opens any maps app on the pin, then the browser; without a pin, the same on
 * the address text. False when nothing on the phone could open it.
 */
fun navigateTo(context: Context, partner: Partner): Boolean {
    val lat = partner.latitude
    val lng = partner.longitude
    val opened = if (lat != null && lng != null) {
        context.startSafely(Intent(Intent.ACTION_VIEW, "google.navigation:q=$lat,$lng".toUri()).setPackage(GOOGLE_MAPS)) ||
            context.startSafely(Intent(Intent.ACTION_VIEW, "geo:$lat,$lng?q=$lat,$lng(${Uri.encode(partner.name)})".toUri())) ||
            context.startSafely(Intent(Intent.ACTION_VIEW, "https://www.google.com/maps/dir/?api=1&destination=$lat,$lng".toUri()))
    } else {
        val address = Uri.encode(partner.address)
        context.startSafely(Intent(Intent.ACTION_VIEW, "geo:0,0?q=$address".toUri())) ||
            context.startSafely(Intent(Intent.ACTION_VIEW, "https://www.google.com/maps/dir/?api=1&destination=$address".toUri()))
    }
    if (!opened) ErrorLog.log("Navigate to ${partner.name}", ActivityNotFoundException("No maps app or browser on this phone"))
    return opened
}

/** Opens Google Maps (or the browser) with the route through the trip's pending stops. False when nothing could. */
fun openRoute(context: Context, stops: List<Stop>): Boolean {
    val url = RouteLink.googleMaps(stops) ?: return false
    val opened = context.startSafely(Intent(Intent.ACTION_VIEW, url.toUri()).setPackage(GOOGLE_MAPS)) ||
        context.startSafely(Intent(Intent.ACTION_VIEW, url.toUri()))
    if (!opened) ErrorLog.log("Open the route", ActivityNotFoundException("No maps app or browser on this phone: $url"))
    return opened
}

/** False when no app on the phone can open [intent]. */
fun Context.startSafely(intent: Intent): Boolean = try {
    startActivity(intent)
    true
} catch (_: ActivityNotFoundException) {
    false
}

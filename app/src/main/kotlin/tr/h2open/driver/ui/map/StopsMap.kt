package tr.h2open.driver.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.createBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.infowindow.MarkerInfoWindow
import tr.h2open.driver.BuildConfig
import tr.h2open.driver.api.Stop
import java.io.File

/**
 * The trip's stops as numbered pins on an OpenStreetMap map (no API key, no
 * billing). Stops without a pin are left out; the caller lists them.
 * [colorOf] gives each pin its stop's status color. Tapping a pin opens it.
 */
@Composable
fun StopsMap(stops: List<Stop>, colorOf: (Stop) -> Color, onOpenStop: (Stop) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val pins = stops.filter { it.partner.hasLocation }
    val colors = pins.associate { it.id to colorOf(it).toArgb() }
    val map = remember {
        configure(context)
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            setTilesScaledToDpi(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
            setMinZoomLevel(5.0)
            // OpenStreetMap's tiles require the attribution on the map.
            overlays.add(CopyrightOverlay(context))
        }
    }
    val fitted = remember { BooleanArray(1) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, map) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> map.onResume()
                Lifecycle.Event.ON_PAUSE -> map.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            map.onDetach()
        }
    }

    AndroidView(factory = { map }, modifier = modifier, update = { view ->
        view.overlays.removeAll { it is Marker }
        pins.forEach { stop ->
            view.overlays.add(Marker(view).apply {
                position = GeoPoint(stop.partner.latitude!!, stop.partner.longitude!!)
                icon = BitmapDrawable(view.resources, pinBitmap(view.context, stop.sequence.toString(), colors.getValue(stop.id)))
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                // No bubble: a tap opens the stop.
                setInfoWindow(null as MarkerInfoWindow?)
                setOnMarkerClickListener { _, _ ->
                    onOpenStop(stop)
                    true
                }
            })
        }
        view.invalidate()
        // Fit the pins once; after that the driver's own pan and zoom stay.
        if (!fitted[0] && pins.isNotEmpty()) {
            fitted[0] = true
            val points = pins.map { GeoPoint(it.partner.latitude!!, it.partner.longitude!!) }
            val fit = {
                if (points.size == 1) {
                    view.controller.setZoom(16.0)
                    view.controller.setCenter(points.first())
                } else {
                    val padding = (48 * view.resources.displayMetrics.density).toInt()
                    view.zoomToBoundingBox(BoundingBox.fromGeoPoints(points), false, padding)
                }
            }
            if (view.isLaidOut) fit() else view.addOnFirstLayoutListener { _, _, _, _, _ -> fit() }
        }
    })
}

/** Tiles are cached in the app's cache folder; OpenStreetMap asks apps to name themselves. */
private fun configure(context: Context) {
    Configuration.getInstance().apply {
        userAgentValue = "${BuildConfig.APPLICATION_ID}/${BuildConfig.VERSION_NAME}"
        osmdroidBasePath = File(context.cacheDir, "osmdroid")
        osmdroidTileCache = File(context.cacheDir, "osmdroid/tiles")
    }
}

/** A filled circle with the stop's number, like the list's badges. */
private fun pinBitmap(context: Context, label: String, color: Int): Bitmap {
    val density = context.resources.displayMetrics.density
    val size = (32 * density).toInt()
    val bitmap = createBitmap(size, size)
    val canvas = Canvas(bitmap)
    val radius = size / 2f
    canvas.drawCircle(radius, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = android.graphics.Color.WHITE })
    canvas.drawCircle(radius, radius, radius - 2 * density, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = android.graphics.Color.WHITE
        textSize = 14 * density
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    canvas.drawText(label, radius, radius - (text.descent() + text.ascent()) / 2, text)
    return bitmap
}

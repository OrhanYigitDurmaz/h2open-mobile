package tr.h2open.driver.api

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Base64

/**
 * Runs a whole trip against a real Odoo with the h2o modules and demo data,
 * only when `H2O_IT_URL` is set (for example `http://localhost:8069`).
 * `H2O_IT_DATE` picks a day the demo driver has a planned trip on.
 */
class LiveOdooTest {
    private val url: String? = System.getenv("H2O_IT_URL")
    private val day: String? = System.getenv("H2O_IT_DATE")

    @Test
    fun `drive a trip from login to close`() = runTest {
        assumeTrue("H2O_IT_URL not set", url != null)
        var token: String? = null
        val api = H2oApi(url!!, { token })

        try {
            api.login("driver", "wrong", "test")
            throw AssertionError("expected a 401")
        } catch (e: UnauthorizedException) {
            assertTrue(e.message!!.isNotBlank())
        }

        val login = api.login("driver", "driver", "LiveOdooTest")
        token = login.token
        assertTrue(login.user.isDriver)
        assertEquals("driver", api.me().login)

        val trip = api.trips(day).single { it.state == TripState.PLANNED }
        val detail = api.trip(trip.id)
        assertEquals(trip.stopCount, detail.stops.size)
        assertTrue(detail.stops.all { it.isPending && it.lines.isNotEmpty() })

        try {
            api.closeTrip(trip.id)
            throw AssertionError("expected a 422")
        } catch (e: ApiException) {
            assertEquals(422, e.status)
            println("close refused: ${e.message}")
        }

        val started = api.startTrip(trip.id)
        assertEquals(TripState.ON_ROAD, started.state)
        api.position(trip.id, 40.99, 29.03)

        val (first, second) = started.stops
        val delivered = api.deliver(first.id, DeliverRequest(
            lines = first.lines.map { DeliveredLine(it.productId, it.qtyPlanned) },
            emptiesCollected = first.order?.emptiesExpected ?: 0,
            paymentMethod = PaymentMethod.CASH,
            signature = Base64.getEncoder().encodeToString(TINY_PNG),
            note = "Left at the door",
        ))
        assertEquals(StopState.DELIVERED, delivered.state)
        assertTrue(delivered.doneAt != null)

        val failed = api.fail(second.id, FailureReason.NOT_HOME, "Nobody answered")
        assertEquals(StopState.FAILED, failed.state)
        assertEquals(FailureReason.NOT_HOME, failed.failureReason)

        try {
            api.fail(second.id, "lost")
            throw AssertionError("expected a 400")
        } catch (e: ApiException) {
            assertEquals(400, e.status)
        }

        val rest = api.trip(trip.id).stops.filter { it.isPending }
        if (rest.size > 1) {
            val reordered = api.reorder(trip.id, rest.map { it.id }.reversed())
            assertEquals(rest.last().id, reordered.stops.first { it.isPending }.id)
        }
        rest.forEach { assertEquals(StopState.SKIPPED, api.skip(it.id).state) }

        val closed = api.closeTrip(trip.id)
        assertEquals(TripState.CLOSED, closed.state)
        assertEquals(0, closed.pendingCount)

        api.logout()
        try {
            api.me()
            throw AssertionError("expected a 401 after logout")
        } catch (_: UnauthorizedException) {
        }
    }

    private companion object {
        /** A 1×1 transparent PNG. */
        val TINY_PNG: ByteArray = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII="
        )
    }
}

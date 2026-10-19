package tr.h2open.driver.api

import org.junit.Assert.assertEquals
import org.junit.Test

class TripSummaryTest {
    private fun line(name: String, delivered: Double) =
        StopLine(id = 1, productId = name.hashCode().toLong(), productName = name, qtyPlanned = delivered, qtyDelivered = delivered)

    private fun stop(id: Long, state: String, lines: List<StopLine> = emptyList(), empties: Int = 0,
                     method: String? = null, amount: Double = 0.0, reason: String? = null) = Stop(
        id = id, tripId = 3, state = state, partner = Partner(id, "Customer $id"), lines = lines,
        emptiesCollected = empties, paymentMethod = method, paymentAmount = amount, failureReason = reason,
    )

    @Test
    fun `sums what was delivered and collected`() {
        val trip = Trip(id = 3, name = "TRIP/0003", date = "2026-10-19", state = TripState.CLOSED, stops = listOf(
            stop(1, StopState.DELIVERED, listOf(line("19 L", 2.0), line("0.5 L pack", 1.0)), empties = 2,
                method = PaymentMethod.CASH, amount = 300.0),
            stop(2, StopState.DELIVERED, listOf(line("19 L", 3.0)), empties = 3, method = PaymentMethod.CARD, amount = 450.0),
            stop(3, StopState.DELIVERED, listOf(line("19 L", 1.0)), method = PaymentMethod.ACCOUNT),
            stop(4, StopState.FAILED, listOf(line("19 L", 0.0)), reason = FailureReason.NOT_HOME),
            stop(5, StopState.SKIPPED),
            stop(6, StopState.PENDING, listOf(line("19 L", 0.0))),
        ))
        val summary = TripSummary.of(trip)
        assertEquals(3, summary.delivered)
        assertEquals(1, summary.failed)
        assertEquals(1, summary.skipped)
        assertEquals(1, summary.pending)
        assertEquals(listOf("19 L" to 6.0, "0.5 L pack" to 1.0), summary.products)
        assertEquals(5, summary.emptiesCollected)
        assertEquals(300.0, summary.cashCollected, 0.001)
        assertEquals(450.0, summary.cardCollected, 0.001)
        assertEquals(1, summary.onAccount)
        assertEquals(listOf(4L, 5L), summary.failedStops.map { it.id })
    }
}

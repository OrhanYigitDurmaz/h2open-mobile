package tr.h2open.driver.api

/** What a driver did on a trip, for the end-of-day summary; worked out from the trip's stops. */
data class TripSummary(
    val delivered: Int,
    val failed: Int,
    val skipped: Int,
    val pending: Int,
    /** Delivered quantity by product name, largest first. */
    val products: List<Pair<String, Double>>,
    val emptiesCollected: Int,
    val cashCollected: Double,
    val cardCollected: Double,
    /** Delivered on monthly account: nothing collected at the door. */
    val onAccount: Int,
    val failedStops: List<Stop>,
    val currency: String,
) {
    companion object {
        fun of(trip: Trip): TripSummary {
            val stops = trip.stops
            val delivered = stops.filter { it.state == StopState.DELIVERED }
            val products = delivered.flatMap { it.lines }
                .groupBy { it.productName }
                .mapValues { (_, lines) -> lines.sumOf { it.qtyDelivered } }
                .filterValues { it > 0 }
                .toList()
                .sortedByDescending { it.second }
            return TripSummary(
                delivered = delivered.size,
                failed = stops.count { it.state == StopState.FAILED },
                skipped = stops.count { it.state == StopState.SKIPPED },
                pending = stops.count { it.isPending },
                products = products,
                emptiesCollected = delivered.sumOf { it.emptiesCollected },
                cashCollected = delivered.filter { it.paymentMethod == PaymentMethod.CASH }.sumOf { it.paymentAmount },
                cardCollected = delivered.filter { it.paymentMethod == PaymentMethod.CARD }.sumOf { it.paymentAmount },
                onAccount = delivered.count { it.paymentMethod == PaymentMethod.ACCOUNT },
                failedStops = stops.filter { it.state == StopState.FAILED || it.state == StopState.SKIPPED },
                currency = trip.currency,
            )
        }
    }
}

package tr.h2open.driver.ui.trip

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import tr.h2open.driver.R
import tr.h2open.driver.api.FailureReason
import tr.h2open.driver.api.PaymentMethod
import tr.h2open.driver.api.StopState
import tr.h2open.driver.api.TripState

@Composable
fun tripStateLabel(state: String): String = stringResource(
    when (state) {
        TripState.PLANNED -> R.string.trip_state_planned
        TripState.LOADED -> R.string.trip_state_loaded
        TripState.ON_ROAD -> R.string.trip_state_on_road
        TripState.CLOSED -> R.string.trip_state_closed
        else -> R.string.trip_state_cancelled
    }
)

@Composable
fun stopStateLabel(state: String): String = stringResource(
    when (state) {
        StopState.DELIVERED -> R.string.stop_state_delivered
        StopState.FAILED -> R.string.stop_state_failed
        StopState.SKIPPED -> R.string.stop_state_skipped
        else -> R.string.stop_state_pending
    }
)

@Composable
fun timeWindowLabel(window: String): String? = when (window) {
    "morning" -> stringResource(R.string.time_window_morning)
    "afternoon" -> stringResource(R.string.time_window_afternoon)
    "evening" -> stringResource(R.string.time_window_evening)
    else -> null
}

@Composable
fun paymentMethodLabel(method: String): String = stringResource(
    when (method) {
        PaymentMethod.CASH -> R.string.payment_cash
        PaymentMethod.CARD -> R.string.payment_card
        PaymentMethod.ACCOUNT -> R.string.payment_account
        else -> R.string.payment_none
    }
)

@Composable
fun failureReasonLabel(reason: String): String = stringResource(
    when (reason) {
        FailureReason.NOT_HOME -> R.string.failure_not_home
        FailureReason.REFUSED -> R.string.failure_refused
        FailureReason.NO_ACCESS -> R.string.failure_no_access
        FailureReason.NO_PAYMENT -> R.string.failure_no_payment
        else -> R.string.failure_other
    }
)

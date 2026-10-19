package tr.h2open.driver.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import tr.h2open.driver.NotADriverException
import tr.h2open.driver.R
import tr.h2open.driver.api.ApiException
import tr.h2open.driver.api.BadResponseException
import tr.h2open.driver.api.NetworkException
import tr.h2open.driver.data.ErrorLog
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import javax.net.ssl.SSLException

/**
 * What went wrong, for the driver and for a bug report: a line saying what
 * it means, then the exact failure (call, HTTP status, Odoo's message or the
 * exception). The same failure is in logcat under "H2Open" ([ErrorLog]).
 */
@Composable
fun errorText(error: Throwable): String {
    val headline = when (error) {
        is NotADriverException -> return stringResource(R.string.error_not_driver)
        is ApiException -> when (error.status) {
            400 -> stringResource(R.string.error_bad_request)
            401 -> stringResource(R.string.error_signed_out)
            403 -> stringResource(R.string.error_not_driver)
            404 -> stringResource(R.string.error_not_found)
            409 -> stringResource(R.string.error_conflict)
            422 -> stringResource(R.string.error_not_allowed)
            in 500..599 -> stringResource(R.string.error_server)
            else -> stringResource(R.string.error_http)
        }
        is NetworkException -> when (error.cause) {
            is UnknownHostException -> stringResource(R.string.error_host)
            is ConnectException -> stringResource(R.string.error_refused)
            is SocketTimeoutException -> stringResource(R.string.error_timeout)
            is SSLException -> stringResource(R.string.error_tls)
            else -> stringResource(R.string.error_network)
        }
        is IOException -> stringResource(R.string.error_network)
        is BadResponseException -> stringResource(R.string.error_bad_response)
        else -> stringResource(R.string.error_app)
    }
    return headline + "\n\n" + ErrorLog.describe(error)
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun ErrorBox(error: Throwable, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(errorText(error), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Button(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
    }
}

fun formatMoney(amount: Double, currency: String): String {
    val format = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("tr-TR"))
    runCatching { format.currency = Currency.getInstance(currency) }
    return format.format(amount)
}

/** 2.0 → "2", 1.5 → "1.5". */
fun formatQty(qty: Double): String =
    if (qty == Math.floor(qty)) qty.toLong().toString() else qty.toString()

/** "2026-10-18" → "18 Eki 2026" in the phone's language; anything unparsable as it came. */
fun formatDate(iso: String): String = runCatching {
    java.time.LocalDate.parse(iso).format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM))
}.getOrDefault(iso)

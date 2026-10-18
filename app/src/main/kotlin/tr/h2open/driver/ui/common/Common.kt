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
import java.io.IOException
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/** A message a driver can act on, from any error the API client throws. */
@Composable
fun errorText(error: Throwable): String = when (error) {
    is NotADriverException -> stringResource(R.string.error_not_driver)
    is ApiException -> when (error.status) {
        401 -> stringResource(R.string.error_signed_out)
        403 -> stringResource(R.string.error_not_driver)
        404 -> stringResource(R.string.error_not_found)
        else -> error.message ?: stringResource(R.string.error_unknown)
    }
    is IOException -> stringResource(R.string.error_network)
    else -> error.message ?: stringResource(R.string.error_unknown)
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

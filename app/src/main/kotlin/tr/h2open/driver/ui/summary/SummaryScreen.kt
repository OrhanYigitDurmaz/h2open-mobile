package tr.h2open.driver.ui.summary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.launch
import tr.h2open.driver.H2oApplication
import tr.h2open.driver.R
import tr.h2open.driver.api.StopState
import tr.h2open.driver.api.Trip
import tr.h2open.driver.api.TripSummary
import tr.h2open.driver.ui.common.ErrorBox
import tr.h2open.driver.ui.common.Load
import tr.h2open.driver.ui.common.LoadingBox
import tr.h2open.driver.ui.common.formatDate
import tr.h2open.driver.ui.common.formatMoney
import tr.h2open.driver.ui.common.formatQty
import tr.h2open.driver.ui.trip.failureReasonLabel
import tr.h2open.driver.ui.trip.stopStateLabel

class SummaryViewModel(private val app: H2oApplication, handle: SavedStateHandle) : ViewModel() {
    private val tripId: Long = checkNotNull(handle["tripId"])

    var trip by mutableStateOf<Load<Trip>>(Load.Loading)
        private set
    var refreshing by mutableStateOf(false)
        private set

    init {
        refresh()
        viewModelScope.launch { app.backOnlineEvents.collect { refresh() } }
    }

    fun refresh() {
        if (refreshing) return
        refreshing = true
        viewModelScope.launch {
            trip = try {
                Load.Ready(app.api().trip(tripId))
            } catch (e: Exception) {
                app.onUnauthorized(e)
                if (trip is Load.Ready) trip else Load.Failed(e)
            } finally {
                refreshing = false
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { SummaryViewModel(this[APPLICATION_KEY] as H2oApplication, createSavedStateHandle()) }
        }
    }
}

/** End of the day: what was delivered, collected and left undone on a trip. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryScreen(onBack: () -> Unit, vm: SummaryViewModel = viewModel(factory = SummaryViewModel.Factory)) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.summary_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = vm.refreshing && vm.trip is Load.Ready,
            onRefresh = vm::refresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            when (val state = vm.trip) {
                Load.Loading -> LoadingBox()
                is Load.Failed -> ErrorBox(state.error, onRetry = vm::refresh)
                is Load.Ready -> SummaryContent(state.value, TripSummary.of(state.value))
            }
        }
    }
}

@Composable
private fun SummaryContent(trip: Trip, summary: TripSummary) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                listOf(trip.route?.name ?: trip.name, formatDate(trip.date)).joinToString(" · "),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        item {
            SummaryCard(stringResource(R.string.summary_stops)) {
                SummaryRow(stopStateLabel(StopState.DELIVERED), summary.delivered.toString())
                SummaryRow(stopStateLabel(StopState.FAILED), summary.failed.toString())
                SummaryRow(stopStateLabel(StopState.SKIPPED), summary.skipped.toString())
                if (summary.pending > 0) SummaryRow(stopStateLabel(StopState.PENDING), summary.pending.toString())
            }
        }
        item {
            SummaryCard(stringResource(R.string.summary_delivered)) {
                if (summary.products.isEmpty()) Text(stringResource(R.string.summary_nothing_delivered))
                summary.products.forEach { (product, qty) -> SummaryRow(product, formatQty(qty)) }
                SummaryRow(stringResource(R.string.stop_empties), summary.emptiesCollected.toString())
            }
        }
        item {
            SummaryCard(stringResource(R.string.summary_collected)) {
                SummaryRow(stringResource(R.string.payment_cash), formatMoney(summary.cashCollected, summary.currency))
                SummaryRow(stringResource(R.string.payment_card), formatMoney(summary.cardCollected, summary.currency))
                SummaryRow(stringResource(R.string.payment_account), summary.onAccount.toString())
                SummaryRow(
                    stringResource(R.string.summary_total),
                    formatMoney(summary.cashCollected + summary.cardCollected, summary.currency),
                    bold = true,
                )
            }
        }
        if (summary.failedStops.isNotEmpty()) {
            item {
                SummaryCard(stringResource(R.string.summary_not_delivered)) {
                    summary.failedStops.forEach { stop ->
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Text(stop.partner.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                listOfNotNull(
                                    stop.failureReason?.let { failureReasonLabel(it) } ?: stopStateLabel(stop.state),
                                    stop.failureNote.ifBlank { null },
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String, bold: Boolean = false) {
    val style = if (bold) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = style, modifier = Modifier.weight(1f))
        Text(value, style = style)
    }
}

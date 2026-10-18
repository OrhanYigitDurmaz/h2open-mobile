package tr.h2open.driver.ui.trip

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
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
import tr.h2open.driver.api.Stop
import tr.h2open.driver.api.StopState
import tr.h2open.driver.api.Trip
import tr.h2open.driver.ui.common.ErrorBox
import tr.h2open.driver.ui.common.Load
import tr.h2open.driver.ui.common.LoadingBox
import tr.h2open.driver.ui.common.errorText
import tr.h2open.driver.ui.common.formatMoney
import tr.h2open.driver.ui.common.formatQty
import tr.h2open.driver.ui.theme.StatusColors

class TripViewModel(private val app: H2oApplication, handle: SavedStateHandle) : ViewModel() {
    val tripId: Long = checkNotNull(handle["tripId"])

    var trip by mutableStateOf<Load<Trip>>(Load.Loading)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    /** An action the server refused, shown once in a snackbar. */
    var actionError by mutableStateOf<Throwable?>(null)

    fun refresh() {
        if (refreshing) return
        refreshing = true
        viewModelScope.launch {
            trip = try {
                Load.Ready(app.api().trip(tripId))
            } catch (e: Exception) {
                app.onUnauthorized(e)
                if (trip is Load.Ready) trip.also { actionError = e } else Load.Failed(e)
            } finally {
                refreshing = false
            }
        }
    }

    fun start() = act { app.api().startTrip(tripId) }

    fun close() = act {
        app.api().closeTrip(tripId)
        // close answers the summary only; reload for the stops.
        app.api().trip(tripId)
    }

    private fun act(call: suspend () -> Trip) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                trip = Load.Ready(call())
            } catch (e: Exception) {
                app.onUnauthorized(e)
                actionError = e
            } finally {
                busy = false
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { TripViewModel(this[APPLICATION_KEY] as H2oApplication, createSavedStateHandle()) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripScreen(
    onBack: () -> Unit,
    onOpenStop: (tripId: Long, stopId: Long) -> Unit,
    vm: TripViewModel = viewModel(factory = TripViewModel.Factory),
) {
    // Runs again when coming back from a stop, so its new state shows.
    LaunchedEffect(Unit) { vm.refresh() }

    val snackbar = remember { SnackbarHostState() }
    vm.actionError?.let { error ->
        val message = errorText(error)
        LaunchedEffect(error) {
            snackbar.showSnackbar(message)
            vm.actionError = null
        }
    }
    var confirmClose by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text((vm.trip as? Load.Ready)?.value?.let { it.route?.name ?: it.name } ?: "") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = vm.refreshing && vm.trip is Load.Ready,
            onRefresh = vm::refresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            when (val state = vm.trip) {
                Load.Loading -> LoadingBox()
                is Load.Failed -> ErrorBox(state.error, onRetry = vm::refresh)
                is Load.Ready -> TripContent(
                    trip = state.value,
                    busy = vm.busy,
                    onStart = vm::start,
                    onClose = { confirmClose = true },
                    onOpenStop = { onOpenStop(state.value.id, it.id) },
                )
            }
        }
    }

    if (confirmClose) {
        AlertDialog(
            onDismissRequest = { confirmClose = false },
            title = { Text(stringResource(R.string.trip_close_title)) },
            text = { Text(stringResource(R.string.trip_close_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClose = false
                    vm.close()
                }) { Text(stringResource(R.string.trip_close)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClose = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun TripContent(trip: Trip, busy: Boolean, onStart: () -> Unit, onClose: () -> Unit, onOpenStop: (Stop) -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tripStateLabel(trip.state), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                trip.vehicle?.let { Text(listOfNotNull(it.licensePlate, it.name).distinct().joinToString(" · ")) }
                Text(pluralStringResource(R.plurals.trip_progress, trip.stopCount, trip.stopCount - trip.pendingCount, trip.stopCount))
                Text(stringResource(R.string.trip_bottles, trip.plannedBottleQty))
                if (trip.cashExpected > 0) Text(stringResource(R.string.trip_cash, formatMoney(trip.cashExpected, trip.currency)))
                if (trip.note.isNotBlank()) Text(trip.note, style = MaterialTheme.typography.bodyMedium)
                when {
                    trip.canStart -> Button(onClick = onStart, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.trip_start))
                    }
                    trip.canClose -> Button(onClick = onClose, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.trip_close))
                    }
                }
            }
        }
        items(trip.stops, key = { it.id }) { stop ->
            StopRow(stop, enabled = trip.isActive, onClick = { onOpenStop(stop) })
        }
    }
}

@Composable
private fun StopRow(stop: Stop, enabled: Boolean, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(32.dp).background(stopColor(stop.state), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(stop.sequence.toString(), color = Color.White, style = MaterialTheme.typography.labelLarge)
            }
            Column(Modifier.weight(1f)) {
                Text(stop.partner.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stop.partner.address, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val window = timeWindowLabel(stop.timeWindow)
                val summary = listOfNotNull(
                    stop.lines.joinToString(", ") { "${formatQty(it.qtyPlanned)} × ${it.productName}" }.ifBlank { null },
                    window,
                )
                if (summary.isNotEmpty()) Text(summary.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            }
            if (!stop.isPending || !enabled) {
                Text(stopStateLabel(stop.state), style = MaterialTheme.typography.labelMedium, color = stopColor(stop.state))
            }
        }
    }
}

@Composable
private fun stopColor(state: String): Color = when (state) {
    StopState.DELIVERED -> StatusColors.delivered
    StopState.FAILED -> StatusColors.failed
    StopState.SKIPPED -> StatusColors.skipped
    else -> MaterialTheme.colorScheme.primary
}

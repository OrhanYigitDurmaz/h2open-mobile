package tr.h2open.driver.ui.trip

import android.widget.Toast
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
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
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
import tr.h2open.driver.api.RouteLink
import tr.h2open.driver.api.Stop
import tr.h2open.driver.api.StopState
import tr.h2open.driver.api.Trip
import tr.h2open.driver.ui.common.ErrorBox
import tr.h2open.driver.ui.common.Load
import tr.h2open.driver.ui.common.LoadingBox
import tr.h2open.driver.ui.common.LocalOnline
import tr.h2open.driver.ui.common.errorText
import tr.h2open.driver.ui.common.formatMoney
import tr.h2open.driver.ui.common.formatQty
import tr.h2open.driver.ui.map.StopsMap
import tr.h2open.driver.ui.map.openRoute
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
    /** The pending stops in the order the driver is arranging, while reordering. */
    var reordering by mutableStateOf<List<Stop>?>(null)
        private set
    /** Set once the driver closed the trip here: the screen opens the day's summary. */
    var closed by mutableStateOf(false)

    init {
        // The office added or removed a stop, or the phone is back online: show the trip as it is now.
        viewModelScope.launch { app.tripChangedEvents.collect { if (it == tripId) refresh() } }
        viewModelScope.launch { app.backOnlineEvents.collect { refresh() } }
    }

    fun refresh() {
        if (refreshing) return
        refreshing = true
        viewModelScope.launch {
            trip = try {
                Load.Ready(app.api().trip(tripId))
            } catch (e: Exception) {
                app.onError("Trip: load", e)
                if (trip is Load.Ready) trip.also { actionError = e } else Load.Failed(e)
            } finally {
                refreshing = false
            }
        }
    }

    fun start() = act("start") { app.api().startTrip(tripId) }

    fun close() = act("close") {
        app.api().closeTrip(tripId)
        closed = true
        // close answers the summary only; reload for the stops.
        app.api().trip(tripId)
    }

    fun startReorder() {
        reordering = (trip as? Load.Ready)?.value?.stops?.filter { it.isPending }
    }

    fun cancelReorder() {
        reordering = null
    }

    /** Moves the stop at [index] one place up ([delta] = -1) or down (+1). */
    fun move(index: Int, delta: Int) {
        val stops = reordering?.toMutableList() ?: return
        val target = index + delta
        if (index !in stops.indices || target !in stops.indices) return
        stops.add(target, stops.removeAt(index))
        reordering = stops
    }

    fun saveReorder() {
        val stops = reordering ?: return
        act("reorder") {
            app.api().reorder(tripId, stops.map { it.id }).also { reordering = null }
        }
    }

    private fun act(name: String, call: suspend () -> Trip) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                trip = Load.Ready(call())
            } catch (e: Exception) {
                app.onError("Trip: $name", e)
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
    onOpenSummary: (tripId: Long) -> Unit,
    vm: TripViewModel = viewModel(factory = TripViewModel.Factory),
) {
    // Runs again when coming back from a stop, so its new state shows.
    LaunchedEffect(Unit) { vm.refresh() }
    LaunchedEffect(vm.closed) {
        if (vm.closed) {
            vm.closed = false
            onOpenSummary(vm.tripId)
        }
    }
    val online = LocalOnline.current

    val snackbar = remember { SnackbarHostState() }
    vm.actionError?.let { error ->
        val message = errorText(error)
        LaunchedEffect(error) {
            snackbar.showSnackbar(message)
            vm.actionError = null
        }
    }
    var confirmClose by remember { mutableStateOf(false) }
    var showMap by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text((vm.trip as? Load.Ready)?.value?.let { it.route?.name ?: it.name } ?: "") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    val trip = (vm.trip as? Load.Ready)?.value
                    if (trip != null && vm.reordering == null) {
                        if (trip.canReorder) {
                            IconButton(onClick = vm::startReorder, enabled = online && !vm.busy) {
                                Icon(Icons.Filled.SwapVert, contentDescription = stringResource(R.string.trip_reorder))
                            }
                        }
                        IconButton(onClick = { onOpenSummary(trip.id) }) {
                            Icon(Icons.Filled.Summarize, contentDescription = stringResource(R.string.summary_title))
                        }
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
                is Load.Ready -> when (val order = vm.reordering) {
                    null -> Column(Modifier.fillMaxSize()) {
                        PrimaryTabRow(selectedTabIndex = if (showMap) 1 else 0) {
                            Tab(selected = !showMap, onClick = { showMap = false }, text = { Text(stringResource(R.string.trip_tab_stops)) })
                            Tab(selected = showMap, onClick = { showMap = true }, text = { Text(stringResource(R.string.trip_tab_map)) })
                        }
                        if (showMap) {
                            TripMap(trip = state.value, onOpenStop = { onOpenStop(state.value.id, it.id) })
                        } else {
                            TripContent(
                                trip = state.value,
                                busy = vm.busy || !online,
                                onStart = vm::start,
                                onClose = { confirmClose = true },
                                onOpenStop = { onOpenStop(state.value.id, it.id) },
                            )
                        }
                    }
                    else -> ReorderContent(
                        stops = order,
                        busy = vm.busy || !online,
                        onMove = vm::move,
                        onSave = vm::saveReorder,
                        onCancel = vm::cancelReorder,
                    )
                }
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
                trip.vehicle?.let { Text(it.label) }
                Text(pluralStringResource(R.plurals.trip_progress, trip.stopCount, trip.stopCount - trip.pendingCount, trip.stopCount))
                Text(stringResource(R.string.trip_bottles, trip.plannedBottleQty))
                if (trip.cashExpected > 0) Text(stringResource(R.string.trip_cash, formatMoney(trip.cashExpected, trip.currency)))
                if (trip.note.isNotBlank()) Text(trip.note, style = MaterialTheme.typography.bodyMedium)
                RouteButton(trip)
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

/** The pinned stops on a map; the ones without a pin are named under it. */
@Composable
private fun TripMap(trip: Trip, onOpenStop: (Stop) -> Unit) {
    val pending = MaterialTheme.colorScheme.primary
    val unpinned = trip.stops.filter { it.isPending && !it.partner.hasLocation }
    Column(Modifier.fillMaxSize()) {
        if (trip.stops.any { it.partner.hasLocation }) {
            StopsMap(
                stops = trip.stops,
                colorOf = { statusColor(it.state, pending) },
                onOpenStop = onOpenStop,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        } else {
            Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.map_no_pins), textAlign = TextAlign.Center)
            }
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (unpinned.isNotEmpty()) {
                Text(
                    pluralStringResource(
                        R.plurals.map_without_location,
                        unpinned.size,
                        unpinned.size,
                        unpinned.joinToString(", ") { "${it.sequence}. ${it.partner.name}" },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            RouteButton(trip)
        }
    }
}

/** Google Maps with the route through the pending stops, in the trip's order. */
@Composable
private fun RouteButton(trip: Trip) {
    if (trip.pendingCount == 0) return
    val context = LocalContext.current
    OutlinedButton(
        onClick = { if (!openRoute(context, trip.stops)) Toast.makeText(context, R.string.maps_no_app, Toast.LENGTH_LONG).show() },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Filled.Directions, contentDescription = null)
        Text(stringResource(R.string.trip_route), Modifier.padding(start = 8.dp))
    }
    if (trip.pendingCount > RouteLink.MAX_STOPS) {
        Text(stringResource(R.string.trip_route_next, RouteLink.MAX_STOPS), style = MaterialTheme.typography.bodySmall)
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
                if (stop.isPending && !stop.partner.hasLocation) NoLocationLabel()
            }
            if (!stop.isPending || !enabled) {
                Text(stopStateLabel(stop.state), style = MaterialTheme.typography.labelMedium, color = stopColor(stop.state))
            }
        }
    }
}

/** The office has not pinned this address yet: navigation goes by the address text. */
@Composable
fun NoLocationLabel() {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.LocationOff, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
        Text(stringResource(R.string.stop_no_location), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
    }
}

/** The pending stops with up and down buttons, in the order the driver wants to drive them. */
@Composable
private fun ReorderContent(stops: List<Stop>, busy: Boolean, onMove: (index: Int, delta: Int) -> Unit, onSave: () -> Unit, onCancel: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Text(
            stringResource(R.string.trip_reorder_hint),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp),
        )
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(stops.size, key = { stops[it].id }) { index ->
                val stop = stops[index]
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${index + 1}.", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(end = 12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stop.partner.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(stop.partner.address, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { onMove(index, -1) }, enabled = index > 0) {
                            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.trip_move_up))
                        }
                        IconButton(onClick = { onMove(index, 1) }, enabled = index < stops.lastIndex) {
                            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.trip_move_down))
                        }
                    }
                }
            }
        }
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_cancel)) }
            Button(onClick = onSave, enabled = !busy, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_save)) }
        }
    }
}

@Composable
private fun stopColor(state: String): Color = statusColor(state, pending = MaterialTheme.colorScheme.primary)

private fun statusColor(state: String, pending: Color): Color = when (state) {
    StopState.DELIVERED -> StatusColors.delivered
    StopState.FAILED -> StatusColors.failed
    StopState.SKIPPED -> StatusColors.skipped
    else -> pending
}

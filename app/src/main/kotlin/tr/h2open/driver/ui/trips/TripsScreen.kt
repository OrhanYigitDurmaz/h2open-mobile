package tr.h2open.driver.ui.trips

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.launch
import tr.h2open.driver.H2oApplication
import tr.h2open.driver.R
import tr.h2open.driver.api.Trip
import tr.h2open.driver.ui.common.ErrorBox
import tr.h2open.driver.ui.common.Load
import tr.h2open.driver.ui.common.LoadingBox
import tr.h2open.driver.ui.common.formatMoney
import tr.h2open.driver.ui.trip.tripStateLabel

class TripsViewModel(private val app: H2oApplication) : ViewModel() {
    var trips by mutableStateOf<Load<List<Trip>>>(Load.Loading)
        private set
    var refreshing by mutableStateOf(false)
        private set

    val driverName get() = app.sessions.session.value?.user?.name.orEmpty()

    fun refresh() {
        if (refreshing) return
        refreshing = true
        viewModelScope.launch {
            trips = try {
                Load.Ready(app.api().trips())
            } catch (e: Exception) {
                app.onUnauthorized(e)
                if (trips is Load.Ready) trips else Load.Failed(e)
            } finally {
                refreshing = false
            }
        }
    }

    fun signOut() {
        viewModelScope.launch { app.signOut() }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { TripsViewModel(this[APPLICATION_KEY] as H2oApplication) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripsScreen(onOpenTrip: (Long) -> Unit, vm: TripsViewModel = viewModel(factory = TripsViewModel.Factory)) {
    LaunchedEffect(Unit) { vm.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.trips_title))
                        Text(vm.driverName, style = MaterialTheme.typography.bodySmall)
                    }
                },
                actions = {
                    IconButton(onClick = vm::signOut) {
                        Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = stringResource(R.string.action_sign_out))
                    }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = vm.refreshing && vm.trips is Load.Ready,
            onRefresh = vm::refresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            when (val state = vm.trips) {
                Load.Loading -> LoadingBox()
                is Load.Failed -> ErrorBox(state.error, onRetry = vm::refresh)
                is Load.Ready -> if (state.value.isEmpty()) {
                    // A LazyColumn so pull-to-refresh still works on the empty state.
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp)) {
                        item { Text(stringResource(R.string.trips_empty), style = MaterialTheme.typography.bodyLarge) }
                    }
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(state.value, key = { it.id }) { trip -> TripCard(trip, onClick = { onOpenTrip(trip.id) }) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TripCard(trip: Trip, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(trip.route?.name ?: trip.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(tripStateLabel(trip.state), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            trip.vehicle?.let { vehicle ->
                Text(vehicle.label, style = MaterialTheme.typography.bodyMedium)
            }
            val done = trip.stopCount - trip.pendingCount
            Text(pluralStringResource(R.plurals.trip_progress, trip.stopCount, done, trip.stopCount), style = MaterialTheme.typography.bodyMedium)
            if (trip.stopCount > 0) {
                LinearProgressIndicator(progress = { done.toFloat() / trip.stopCount }, modifier = Modifier.fillMaxWidth())
            }
            Row {
                Text(stringResource(R.string.trip_bottles, trip.plannedBottleQty), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                if (trip.cashExpected > 0) {
                    Text(stringResource(R.string.trip_cash, formatMoney(trip.cashExpected, trip.currency)), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

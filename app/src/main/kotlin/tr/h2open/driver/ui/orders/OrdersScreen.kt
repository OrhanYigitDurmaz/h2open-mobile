package tr.h2open.driver.ui.orders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import tr.h2open.driver.api.ApiException
import tr.h2open.driver.api.ClaimResult
import tr.h2open.driver.api.OpenOrder
import tr.h2open.driver.push.OrderNotifications
import tr.h2open.driver.ui.common.ErrorBox
import tr.h2open.driver.ui.common.Load
import tr.h2open.driver.ui.common.LoadingBox
import tr.h2open.driver.ui.common.LocalOnline
import tr.h2open.driver.ui.common.errorText
import tr.h2open.driver.ui.common.formatDate
import tr.h2open.driver.ui.common.formatMoney
import tr.h2open.driver.ui.common.formatQty
import tr.h2open.driver.ui.trip.timeWindowLabel

class OrdersViewModel(private val app: H2oApplication) : ViewModel() {
    var orders by mutableStateOf<Load<List<OpenOrder>>>(Load.Loading)
        private set
    var refreshing by mutableStateOf(false)
        private set
    /** The order being taken right now. */
    var claiming by mutableStateOf<Long?>(null)
        private set
    var claimed by mutableStateOf<ClaimResult?>(null)
    var claimError by mutableStateOf<Throwable?>(null)

    init {
        // A push said an order was placed or taken: show the list as it is now.
        viewModelScope.launch { app.ordersChangedEvents.collect { refresh() } }
        viewModelScope.launch { app.backOnlineEvents.collect { refresh() } }
    }

    fun refresh() {
        if (refreshing) return
        refreshing = true
        viewModelScope.launch {
            orders = try {
                Load.Ready(app.api().openOrders())
            } catch (e: Exception) {
                app.onUnauthorized(e)
                if (orders is Load.Ready) orders else Load.Failed(e)
            } finally {
                refreshing = false
            }
        }
    }

    fun claim(order: OpenOrder) {
        if (claiming != null) return
        claiming = order.id
        viewModelScope.launch {
            val gone = try {
                claimed = app.api().claimOrder(order.id)
                true
            } catch (e: Exception) {
                app.onUnauthorized(e)
                claimError = e
                e is ApiException && e.status == 409
            } finally {
                claiming = null
            }
            if (gone) {
                // Taken, by us or by someone else: it leaves the list and the notifications.
                OrderNotifications.cancel(app, order.id)
                (orders as? Load.Ready)?.let { ready -> orders = Load.Ready(ready.value.filter { it.id != order.id }) }
            }
            refresh()
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { OrdersViewModel(this[APPLICATION_KEY] as H2oApplication) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrdersScreen(
    onOpenTrip: (Long) -> Unit,
    modifier: Modifier = Modifier,
    vm: OrdersViewModel = viewModel(factory = OrdersViewModel.Factory),
) {
    LaunchedEffect(Unit) { vm.refresh() }
    var confirm by remember { mutableStateOf<OpenOrder?>(null) }
    val snackbar = remember { SnackbarHostState() }

    vm.claimed?.let { result ->
        val message = stringResource(R.string.orders_claimed, result.stop.partner.name)
        val open = stringResource(R.string.orders_open_trip)
        LaunchedEffect(result) {
            val action = snackbar.showSnackbar(message, actionLabel = open)
            vm.claimed = null
            if (action == SnackbarResult.ActionPerformed) onOpenTrip(result.trip.id)
        }
    }
    vm.claimError?.let { error ->
        val message = if (error is ApiException && error.status == 409) stringResource(R.string.orders_already_taken) else errorText(error)
        LaunchedEffect(error) {
            snackbar.showSnackbar(message)
            vm.claimError = null
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.orders_title)) }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = vm.refreshing && vm.orders is Load.Ready,
            onRefresh = vm::refresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            when (val state = vm.orders) {
                Load.Loading -> LoadingBox()
                is Load.Failed -> ErrorBox(state.error, onRetry = vm::refresh)
                is Load.Ready -> if (state.value.isEmpty()) {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp)) {
                        item { Text(stringResource(R.string.orders_empty), style = MaterialTheme.typography.bodyLarge) }
                    }
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(state.value, key = { it.id }) { order ->
                            OrderCard(order, busy = vm.claiming == order.id, enabled = vm.claiming == null && LocalOnline.current, onClaim = { confirm = order })
                        }
                    }
                }
            }
        }
    }

    confirm?.let { order ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.orders_claim_title)) },
            text = { Text(stringResource(R.string.orders_claim_text, order.partner.name)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    vm.claim(order)
                }) { Text(stringResource(R.string.orders_claim)) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun OrderCard(order: OpenOrder, busy: Boolean, enabled: Boolean, onClaim: () -> Unit) {
    val partner = order.partner
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(partner.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (order.myZone) {
                    Text(stringResource(R.string.orders_my_zone), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
            if (partner.customerName.isNotBlank() && partner.customerName != partner.name) {
                Text(partner.customerName, style = MaterialTheme.typography.labelLarge)
            }
            Text(partner.address.ifBlank { "—" }, style = MaterialTheme.typography.bodyMedium)
            listOfNotNull(
                order.zone?.name,
                order.deliveryDate?.let { stringResource(R.string.orders_delivery_date, formatDate(it)) },
                timeWindowLabel(order.timeWindow),
            ).takeIf { it.isNotEmpty() }?.let {
                Text(it.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            }
            order.lines.forEach { Text("${formatQty(it.qty)} × ${it.productName}", style = MaterialTheme.typography.bodyMedium) }
            if (order.emptiesExpected > 0) {
                Text(stringResource(R.string.stop_empties_expected, order.emptiesExpected), style = MaterialTheme.typography.bodySmall)
            }
            listOf(partner.deliveryNotes, order.note).filter { it.isNotBlank() }.distinct().forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatMoney(order.amountTotal, order.currency), style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f))
                Button(onClick = onClaim, enabled = enabled) {
                    Text(stringResource(if (busy) R.string.orders_claiming else R.string.orders_claim))
                }
            }
        }
    }
}

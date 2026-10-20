package tr.h2open.driver.ui.stop

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
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
import tr.h2open.driver.api.ApiException
import tr.h2open.driver.api.DeliverRequest
import tr.h2open.driver.api.DeliveredLine
import tr.h2open.driver.api.FailureReason
import tr.h2open.driver.api.PaymentMethod
import tr.h2open.driver.api.Stop
import tr.h2open.driver.api.Trip
import tr.h2open.driver.ui.call.rememberVoipCall
import tr.h2open.driver.ui.common.ErrorBox
import tr.h2open.driver.ui.common.Load
import tr.h2open.driver.ui.common.LoadingBox
import tr.h2open.driver.ui.common.LocalOnline
import tr.h2open.driver.ui.common.errorText
import tr.h2open.driver.ui.common.formatMoney
import tr.h2open.driver.ui.common.formatQty
import tr.h2open.driver.ui.map.navigateTo
import tr.h2open.driver.ui.map.startSafely
import tr.h2open.driver.ui.trip.NoLocationLabel
import tr.h2open.driver.ui.trip.failureReasonLabel
import tr.h2open.driver.ui.trip.paymentMethodLabel
import tr.h2open.driver.ui.trip.stopStateLabel
import tr.h2open.driver.ui.trip.timeWindowLabel

class StopViewModel(private val app: H2oApplication, handle: SavedStateHandle) : ViewModel() {
    private val tripId: Long = checkNotNull(handle["tripId"])
    private val stopId: Long = checkNotNull(handle["stopId"])

    /** The trip and this stop in it (there is no single-stop GET). */
    var data by mutableStateOf<Load<Pair<Trip, Stop>>>(Load.Loading)
        private set
    var busy by mutableStateOf(false)
        private set
    var actionError by mutableStateOf<Throwable?>(null)
    /** Set once the stop is delivered, failed or skipped: the screen goes back. */
    var finished by mutableStateOf(false)
        private set

    // The delivery form, filled with what was planned.
    val quantities = mutableStateMapOf<Long, Double>()
    var empties by mutableIntStateOf(0)
    var paymentMethod by mutableStateOf(PaymentMethod.NONE)
    /** Blank means "the amount due", computed by Odoo. */
    var paymentAmount by mutableStateOf("")
    var note by mutableStateOf("")

    init {
        load()
    }

    fun load() {
        data = Load.Loading
        viewModelScope.launch {
            data = try {
                val trip = app.api().trip(tripId)
                // Gone when the office moved it to another trip meanwhile.
                val stop = trip.stops.firstOrNull { it.id == stopId } ?: throw ApiException(404, "Stop not found")
                prefill(stop)
                Load.Ready(trip to stop)
            } catch (e: Exception) {
                app.onError("Stop: load", e)
                Load.Failed(e)
            }
        }
    }

    private fun prefill(stop: Stop) {
        quantities.clear()
        stop.lines.forEach { quantities[it.productId] = it.qtyPlanned }
        empties = stop.emptiesExpected
        paymentMethod = stop.paymentMethod
            ?: stop.paymentMode?.takeIf { it in PaymentMethod.all }
            ?: if (stop.amountDue > 0) PaymentMethod.CASH else PaymentMethod.NONE
    }

    fun deliver(signature: String?) {
        val stop = (data as? Load.Ready)?.value?.second ?: return
        val amount = paymentAmount.replace(',', '.').trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
        act("deliver") {
            app.api().deliver(stop.id, DeliverRequest(
                // Only lines that differ from the plan; the rest are delivered as planned.
                lines = stop.lines
                    .filter { quantities[it.productId] != it.qtyPlanned }
                    .map { DeliveredLine(it.productId, quantities[it.productId] ?: it.qtyPlanned) }
                    .ifEmpty { null },
                emptiesCollected = empties,
                paymentMethod = paymentMethod,
                paymentAmount = if (paymentMethod == PaymentMethod.CASH || paymentMethod == PaymentMethod.CARD) amount else null,
                signature = signature,
                note = note.trim().ifEmpty { null },
            ))
        }
    }

    fun fail(reason: String, note: String) = act("fail") { app.api().fail(stopId, reason, note.trim().ifEmpty { null }) }

    fun skip() = act("skip") { app.api().skip(stopId) }

    /** Give back an order the driver took from the Orders tab. */
    fun release() = act("give back") {
        app.api().releaseStop(stopId)
        app.onOrdersChanged()
    }

    private fun act(name: String, call: suspend () -> Unit) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                call()
                finished = true
            } catch (e: Exception) {
                app.onError("Stop: $name", e)
                actionError = e
            } finally {
                busy = false
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { StopViewModel(this[APPLICATION_KEY] as H2oApplication, createSavedStateHandle()) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StopScreen(onBack: () -> Unit, vm: StopViewModel = viewModel(factory = StopViewModel.Factory)) {
    LaunchedEffect(vm.finished) { if (vm.finished) onBack() }

    val snackbar = remember { SnackbarHostState() }
    vm.actionError?.let { error ->
        val message = errorText(error)
        LaunchedEffect(error) {
            snackbar.showSnackbar(message)
            vm.actionError = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text((vm.data as? Load.Ready)?.value?.second?.partner?.name.orEmpty()) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when (val state = vm.data) {
            Load.Loading -> LoadingBox(Modifier.padding(padding))
            is Load.Failed -> ErrorBox(state.error, onRetry = vm::load, modifier = Modifier.padding(padding))
            is Load.Ready -> {
                val (trip, stop) = state.value
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .imePadding()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CustomerCard(stop)
                    if (stop.canRelease) ReleaseButton(vm)
                    when {
                        !stop.isPending -> StopResult(stop)
                        !trip.isActive -> Text(stringResource(R.string.stop_start_trip_first), style = MaterialTheme.typography.bodyLarge)
                        else -> DeliveryForm(stop, vm)
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomerCard(stop: Stop) {
    val context = LocalContext.current
    val voipCall = rememberVoipCall()
    val online = LocalOnline.current
    val partner = stop.partner
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (partner.customerName.isNotBlank() && partner.customerName != partner.name) {
                Text(partner.customerName, style = MaterialTheme.typography.labelLarge)
            }
            Text(partner.address.ifBlank { "—" }, style = MaterialTheme.typography.bodyLarge)
            if (!partner.hasLocation) NoLocationLabel()
            val floor = if (partner.floor != 0) stringResource(R.string.stop_floor, partner.floor) else null
            val elevator = if (partner.floor != 0) {
                stringResource(if (partner.hasElevator) R.string.stop_elevator_yes else R.string.stop_elevator_no)
            } else null
            listOfNotNull(floor, elevator, timeWindowLabel(stop.timeWindow)).takeIf { it.isNotEmpty() }?.let {
                Text(it.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
            }
            Text(stringResource(R.string.stop_bottle_balance, partner.bottleBalance), style = MaterialTheme.typography.bodyMedium)
            if (stop.isPending && stop.emptiesExpected > 0) {
                Text(stringResource(R.string.stop_empties_expected, stop.emptiesExpected), style = MaterialTheme.typography.titleSmall)
            }
            if (stop.amountDue > 0) {
                Text(stringResource(R.string.stop_amount_due, formatMoney(stop.amountDue, stop.currency)), style = MaterialTheme.typography.titleSmall)
            }
            listOf(partner.deliveryNotes, stop.order?.note.orEmpty(), stop.note).filter { it.isNotBlank() }.distinct().forEach {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (partner.phone.isNotBlank()) {
                    // Through the office line, so the customer sees the company number.
                    OutlinedButton(onClick = { voipCall(partner.phone, partner.name) }, enabled = online) {
                        Icon(Icons.Filled.Call, contentDescription = null)
                        Text(stringResource(R.string.stop_call), Modifier.padding(start = 8.dp))
                    }
                    IconButton(onClick = { dial(context, partner.phone) }) {
                        Icon(Icons.Filled.Dialpad, contentDescription = stringResource(R.string.stop_call_phone))
                    }
                }
                OutlinedButton(onClick = {
                    if (!navigateTo(context, partner)) Toast.makeText(context, R.string.maps_no_app, Toast.LENGTH_LONG).show()
                }) {
                    Icon(Icons.Filled.Directions, contentDescription = null)
                    Text(stringResource(R.string.stop_navigate), Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

/** For an order the driver took themselves: give it back to the Orders tab. */
@Composable
private fun ReleaseButton(vm: StopViewModel) {
    var confirm by remember { mutableStateOf(false) }
    TextButton(onClick = { confirm = true }, enabled = !vm.busy && LocalOnline.current) { Text(stringResource(R.string.stop_release)) }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.stop_release_title)) },
            text = { Text(stringResource(R.string.stop_release_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    vm.release()
                }) { Text(stringResource(R.string.stop_release)) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun StopResult(stop: Stop) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stopStateLabel(stop.state), style = MaterialTheme.typography.titleMedium)
        stop.failureReason?.let { Text(failureReasonLabel(it)) }
        if (stop.failureNote.isNotBlank()) Text(stop.failureNote)
        stop.lines.forEach { Text("${formatQty(it.qtyDelivered)} × ${it.productName}") }
        if (stop.emptiesCollected > 0) Text(stringResource(R.string.stop_empties_collected, stop.emptiesCollected))
        stop.paymentMethod?.let { method ->
            Text(paymentMethodLabel(method) + if (stop.paymentAmount > 0) " · " + formatMoney(stop.paymentAmount, stop.currency) else "")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeliveryForm(stop: Stop, vm: StopViewModel) {
    val signature = rememberSignatureState()
    // Nothing can be sent while offline; the form keeps what was typed.
    val canAct = !vm.busy && LocalOnline.current
    var showFail by remember { mutableStateOf(false) }
    var confirmSkip by remember { mutableStateOf(false) }

    Text(stringResource(R.string.stop_deliver_title), style = MaterialTheme.typography.titleMedium)
    stop.lines.forEach { line ->
        Stepper(
            label = line.productName,
            value = vm.quantities[line.productId] ?: line.qtyPlanned,
            onChange = { vm.quantities[line.productId] = it },
        )
    }
    if (stop.emptiesProductId != null || stop.emptiesExpected > 0) {
        Stepper(
            label = stringResource(R.string.stop_empties),
            value = vm.empties.toDouble(),
            onChange = { vm.empties = it.toInt() },
        )
    }
    HorizontalDivider()

    Text(stringResource(R.string.stop_payment), style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PaymentMethod.all.forEach { method ->
            FilterChip(
                selected = vm.paymentMethod == method,
                onClick = { vm.paymentMethod = method },
                label = { Text(paymentMethodLabel(method)) },
            )
        }
    }
    if (vm.paymentMethod == PaymentMethod.CASH || vm.paymentMethod == PaymentMethod.CARD) {
        OutlinedTextField(
            value = vm.paymentAmount,
            onValueChange = { vm.paymentAmount = it },
            label = { Text(stringResource(R.string.stop_amount_collected)) },
            placeholder = { Text(formatMoney(stop.amountDue, stop.currency)) },
            supportingText = { Text(stringResource(R.string.stop_amount_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    OutlinedTextField(
        value = vm.note,
        onValueChange = { vm.note = it },
        label = { Text(stringResource(R.string.stop_note)) },
        modifier = Modifier.fillMaxWidth(),
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.stop_signature), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        TextButton(onClick = signature::clear, enabled = !signature.isEmpty) { Text(stringResource(R.string.action_clear)) }
    }
    SignaturePad(signature)

    Button(
        onClick = { vm.deliver(signature.toBase64Png()) },
        enabled = canAct,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.stop_deliver)) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { showFail = true }, enabled = canAct, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.stop_fail))
        }
        OutlinedButton(onClick = { confirmSkip = true }, enabled = canAct, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.stop_skip))
        }
    }

    if (showFail) {
        FailDialog(onDismiss = { showFail = false }, onConfirm = { reason, note ->
            showFail = false
            vm.fail(reason, note)
        })
    }
    if (confirmSkip) {
        AlertDialog(
            onDismissRequest = { confirmSkip = false },
            title = { Text(stringResource(R.string.stop_skip_title)) },
            text = { Text(stringResource(R.string.stop_skip_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmSkip = false
                    vm.skip()
                }) { Text(stringResource(R.string.stop_skip)) }
            },
            dismissButton = { TextButton(onClick = { confirmSkip = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun Stepper(label: String, value: Double, onChange: (Double) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        IconButton(onClick = { onChange((value - 1).coerceAtLeast(0.0)) }, enabled = value > 0) {
            Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.action_decrease))
        }
        Text(formatQty(value), style = MaterialTheme.typography.titleMedium)
        IconButton(onClick = { onChange(value + 1) }) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.action_increase))
        }
    }
}

@Composable
private fun FailDialog(onDismiss: () -> Unit, onConfirm: (reason: String, note: String) -> Unit) {
    var reason by remember { mutableStateOf(FailureReason.NOT_HOME) }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.stop_fail_title)) },
        text = {
            Column {
                FailureReason.all.forEach { option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = reason == option, onClick = { reason = option }, role = Role.RadioButton)
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = reason == option, onClick = null)
                        Text(failureReasonLabel(option), Modifier.padding(start = 8.dp))
                    }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(stringResource(R.string.stop_note)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(reason, note) }) { Text(stringResource(R.string.stop_fail)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private fun dial(context: Context, phone: String) {
    val number = phone.filter { it.isDigit() || it == '+' }
    context.startSafely(Intent(Intent.ACTION_DIAL, "tel:$number".toUri()))
}

package tr.h2open.driver.ui.call

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.util.Locale
import tr.h2open.driver.R
import tr.h2open.driver.voip.AudioRoute
import tr.h2open.driver.voip.CallInfo
import tr.h2open.driver.voip.CallPhase
import tr.h2open.driver.voip.CallService

private val Green = Color(0xFF2E7D32)
private val Red = Color(0xFFC62828)

@Composable
fun CallScreen(
    call: CallInfo,
    onAnswer: () -> Unit,
    onHangUp: () -> Unit,
    onToggleMute: () -> Unit,
    onSelectRoute: (AudioRoute) -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(48.dp))
            Text(
                call.name ?: call.number,
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            if (call.name != null) {
                Text(call.number, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
            Text(statusText(call), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

            Spacer(Modifier.weight(1f))

            when {
                call.phase == CallPhase.Incoming -> Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    RoundButton(Icons.Filled.CallEnd, stringResource(R.string.call_decline), Red, onHangUp)
                    RoundButton(Icons.Filled.Call, stringResource(R.string.call_answer), Green, onAnswer)
                }
                call.isLive -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        ToggleButton(
                            if (call.muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                            stringResource(R.string.call_mute), call.muted, onToggleMute,
                        )
                        ToggleButton(
                            Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.call_speaker),
                            call.route == AudioRoute.Speaker,
                        ) { onSelectRoute(if (call.route == AudioRoute.Speaker) AudioRoute.Earpiece else AudioRoute.Speaker) }
                        if (AudioRoute.Bluetooth in call.routes) {
                            ToggleButton(
                                Icons.Filled.Bluetooth, stringResource(R.string.call_bluetooth),
                                call.route == AudioRoute.Bluetooth,
                            ) { onSelectRoute(if (call.route == AudioRoute.Bluetooth) AudioRoute.Earpiece else AudioRoute.Bluetooth) }
                        }
                    }
                    Spacer(Modifier.height(32.dp))
                    RoundButton(Icons.Filled.CallEnd, stringResource(R.string.call_hang_up), Red, onHangUp)
                }
                else -> Icon(Icons.Filled.PhoneInTalk, contentDescription = null, Modifier.size(48.dp))
            }
            Spacer(Modifier.height(48.dp))
        }
    }
}

@Composable
private fun statusText(call: CallInfo): String {
    val connectedAt = call.connectedAtMillis
    if (call.phase == CallPhase.Active && connectedAt != null) {
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(connectedAt) {
            while (true) {
                now = System.currentTimeMillis()
                delay(1_000)
            }
        }
        val seconds = ((now - connectedAt) / 1000).coerceAtLeast(0)
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
    }
    return stringResource(CallService.phaseText(call))
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(72.dp),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = color, contentColor = Color.White),
        ) {
            Icon(icon, contentDescription = label, Modifier.size(32.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun ToggleButton(icon: ImageVector, label: String, checked: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalIconToggleButton(checked = checked, onCheckedChange = { onClick() }, modifier = Modifier.size(56.dp)) {
            Icon(icon, contentDescription = label)
        }
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

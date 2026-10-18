package tr.h2open.driver.ui.call

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import tr.h2open.driver.H2oApplication
import tr.h2open.driver.voip.CallActivity

/** Permissions a call needs: the microphone, and on Android 13+ the call notification. */
fun callPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    } else {
        arrayOf(Manifest.permission.RECORD_AUDIO)
    }

/**
 * Returns a function that calls a number through the office PBX, asking for
 * the microphone first when needed, and opens the call screen.
 */
@Composable
fun rememberVoipCall(): (number: String, name: String?) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<Pair<String, String?>?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val call = pending
        pending = null
        if (call != null && result[Manifest.permission.RECORD_AUDIO] == true) startCall(context, call.first, call.second)
    }
    return remember(context, launcher) {
        { number: String, name: String? ->
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                startCall(context, number, name)
            } else {
                pending = number to name
                launcher.launch(callPermissions())
            }
        }
    }
}

private fun startCall(context: Context, number: String, name: String?) {
    (context.applicationContext as H2oApplication).voip.startCall(number, name)
    context.startActivity(Intent(context, CallActivity::class.java))
}

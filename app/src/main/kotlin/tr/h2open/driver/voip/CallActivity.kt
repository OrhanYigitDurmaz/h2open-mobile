package tr.h2open.driver.voip

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tr.h2open.driver.H2oApplication
import tr.h2open.driver.ui.call.CallScreen
import tr.h2open.driver.ui.theme.H2OpenTheme

/** The full-screen call UI, shown over the lock screen while a call rings or runs. */
class CallActivity : ComponentActivity() {
    private val voip get() = (application as H2oApplication).voip

    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Answer either way: without the microphone the driver still hears the caller.
        voip.answer()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        handle(intent)
        setContent {
            H2OpenTheme {
                val call by voip.call.collectAsStateWithLifecycle()
                val current = call
                LaunchedEffect(current == null) { if (current == null) finish() }
                if (current != null) {
                    CallScreen(
                        call = current,
                        onAnswer = ::answer,
                        onHangUp = voip::hangup,
                        onToggleMute = voip::toggleMute,
                        onSelectRoute = voip::selectRoute,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent?.action == ACTION_ANSWER) {
            intent.action = null
            answer()
        }
    }

    private fun answer() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            voip.answer()
        } else {
            microphone.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    companion object {
        const val ACTION_ANSWER = "tr.h2open.driver.action.ANSWER"
    }
}

package tr.h2open.driver.voip

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import tr.h2open.driver.H2oApplication
import tr.h2open.driver.R

/**
 * Keeps the app alive for the length of a call (and of a push wake-up)
 * and shows the call notification: ringing with answer and decline, or
 * ongoing with hang up.
 */
class CallService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var foregroundType = -1

    private val voip get() = (application as H2oApplication).voip

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
        scope.launch {
            combine(voip.call, voip.wake) { call, wake -> call to wake }.collect { (call, wake) -> render(call, wake) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HANG_UP) voip.hangup()
        // startForegroundService() wants startForeground() soon, whatever the action.
        render(voip.call.value, voip.wake.value)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun render(call: CallInfo?, wake: Wake?) {
        if (call == null && wake == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundType = -1
            stopSelf()
            return
        }
        val notification = if (call != null) callNotification(call) else wakeNotification()
        // The microphone type is only allowed once the user is in the call (answered or dialled from the app).
        val wantsMic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && call != null && call.phase != CallPhase.Incoming &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or (if (wantsMic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        } else {
            0
        }
        if (type == foregroundType || (foregroundType != -1 && call?.phase == CallPhase.Ended)) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            ) {
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
            }
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIFICATION_ID, notification, type)
            else startForeground(NOTIFICATION_ID, notification)
            foregroundType = type
        } catch (e: RuntimeException) {
            // Not allowed from the background on this Android version: keep the type we have.
            Log.w(TAG, "startForeground($type) refused", e)
            if (foregroundType == -1) stopSelf()
        }
    }

    private fun callNotification(call: CallInfo): Notification {
        val title = call.name ?: call.number
        val open = activityIntent(this, null, 1)
        val builder = Notification.Builder(this, if (call.phase == CallPhase.Incoming) CHANNEL_INCOMING else CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_stat_call)
            .setContentTitle(title)
            .setContentText(getString(phaseText(call)))
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(call.isLive)
            .setContentIntent(open)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        call.connectedAtMillis?.takeIf { call.phase == CallPhase.Active }?.let {
            builder.setWhen(it).setUsesChronometer(true).setShowWhen(true)
        }
        val hangUp = PendingIntent.getService(
            this, 2, Intent(this, CallService::class.java).setAction(ACTION_HANG_UP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        if (call.phase == CallPhase.Incoming) {
            val answer = activityIntent(this, CallActivity.ACTION_ANSWER, 3)
            builder.setFullScreenIntent(open, true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setStyle(Notification.CallStyle.forIncomingCall(person(title), hangUp, answer))
            } else {
                builder.addAction(Notification.Action.Builder(null, getString(R.string.call_decline), hangUp).build())
                builder.addAction(Notification.Action.Builder(null, getString(R.string.call_answer), answer).build())
            }
        } else if (call.isLive) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setStyle(Notification.CallStyle.forOngoingCall(person(title), hangUp))
            } else {
                builder.addAction(Notification.Action.Builder(null, getString(R.string.call_hang_up), hangUp).build())
            }
        }
        return builder.build().apply {
            if (call.phase == CallPhase.Incoming) flags = flags or Notification.FLAG_INSISTENT
        }
    }

    private fun wakeNotification(): Notification =
        Notification.Builder(this, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_stat_call)
            .setContentTitle(getString(R.string.call_waking))
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .build()

    @RequiresApi(Build.VERSION_CODES.S)
    private fun person(name: String) = Person.Builder().setName(name).setImportant(true).build()

    companion object {
        private const val TAG = "H2oCallService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_INCOMING = "calls_incoming"
        private const val CHANNEL_ONGOING = "calls_ongoing"
        private const val ACTION_HANG_UP = "tr.h2open.driver.action.HANG_UP"

        fun start(context: Context) {
            val intent = Intent(context, CallService::class.java)
            try {
                context.startForegroundService(intent)
            } catch (e: RuntimeException) {
                // Started from the background without an exemption; the call still works while the app is open.
                Log.w(TAG, "Could not start the call service", e)
            }
        }

        fun createChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val ringing = NotificationChannel(
                CHANNEL_INCOMING, context.getString(R.string.channel_calls_incoming), NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build(),
                )
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 800, 800)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val ongoing = NotificationChannel(
                CHANNEL_ONGOING, context.getString(R.string.channel_calls_ongoing), NotificationManager.IMPORTANCE_LOW,
            )
            manager.createNotificationChannels(listOf(ringing, ongoing))
        }

        fun phaseText(call: CallInfo): Int = if (call.held && call.isLive) R.string.call_on_hold else when (call.phase) {
            CallPhase.Connecting -> R.string.call_connecting
            CallPhase.Ringing -> R.string.call_ringing
            CallPhase.Incoming -> R.string.call_incoming
            CallPhase.Active -> R.string.call_active
            CallPhase.Ended -> endText(call)
        }

        fun endText(call: CallInfo): Int = when (call.failure) {
            CallFailure.NotSetUp -> R.string.call_not_set_up
            CallFailure.Network -> R.string.error_network
            CallFailure.Other -> R.string.call_failed
            null -> when (call.endReason) {
                tr.h2open.driver.sip.EndReason.Busy -> R.string.call_busy
                tr.h2open.driver.sip.EndReason.Declined -> R.string.call_declined
                tr.h2open.driver.sip.EndReason.NoAnswer -> R.string.call_no_answer
                tr.h2open.driver.sip.EndReason.NotFound -> R.string.call_not_found
                tr.h2open.driver.sip.EndReason.CallerCancelled -> R.string.call_caller_hung_up
                tr.h2open.driver.sip.EndReason.ConnectionLost -> R.string.call_connection_lost
                tr.h2open.driver.sip.EndReason.Failed -> R.string.call_failed
                else -> R.string.call_ended
            }
        }

        private fun activityIntent(context: Context, action: String?, requestCode: Int): PendingIntent =
            PendingIntent.getActivity(
                context, requestCode,
                Intent(context, CallActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
    }
}

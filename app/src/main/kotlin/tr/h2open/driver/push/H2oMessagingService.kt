package tr.h2open.driver.push

import android.os.Handler
import android.os.Looper
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import tr.h2open.driver.H2oApplication

/**
 * Data messages from h2o_push: `incoming_call` and `call_cancelled`
 * (h2o_telephony), `order_open` and `order_claimed` (h2o_driver).
 */
// Lint's check predates FCM's installation ID API: onRegistered replaces the deprecated onNewToken.
@Suppress("MissingFirebaseInstanceTokenRefresh")
class H2oMessagingService : FirebaseMessagingService() {
    private val app get() = application as H2oApplication
    private val main = Handler(Looper.getMainLooper())

    /** After [Push.register], and whenever the installation ID changes. */
    override fun onRegistered(installationId: String) {
        app.registerPush(installationId)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        // Called on a Firebase thread; the call state lives on the main one.
        main.post {
            when (data["type"]) {
                "incoming_call" -> app.voip.onWake(data["caller"].orEmpty(), data["caller_name"])
                "call_cancelled" -> app.voip.onWakeCancelled()
                "order_open" -> {
                    OrderNotifications.showOpen(app, data)
                    app.onOrdersChanged()
                }
                "order_claimed" -> {
                    data["order_id"]?.toLongOrNull()?.let { OrderNotifications.cancel(app, it) }
                    app.onOrdersChanged()
                }
            }
        }
    }
}

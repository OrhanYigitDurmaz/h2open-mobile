package tr.h2open.driver.push

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import tr.h2open.driver.BuildConfig

/**
 * Firebase Cloud Messaging, set up from the `google-services.json` the
 * build read (see app/build.gradle.kts). Without it push is off and the
 * phone only rings while the app has a call going.
 */
object Push {
    private const val TAG = "H2Open.Push"

    val configured: Boolean get() = BuildConfig.FCM_APP_ID.isNotEmpty()

    fun init(context: Context) {
        if (!configured || FirebaseApp.getApps(context).isNotEmpty()) return
        val options = FirebaseOptions.Builder()
            .setApplicationId(BuildConfig.FCM_APP_ID)
            .setApiKey(BuildConfig.FCM_API_KEY)
            .setProjectId(BuildConfig.FCM_PROJECT_ID)
            .setGcmSenderId(BuildConfig.FCM_SENDER_ID)
            .build()
        runCatching { FirebaseApp.initializeApp(context, options) }.onFailure { Log.w(TAG, "Firebase init failed", it) }
    }

    /**
     * Asks FCM to register this install. The answer comes to
     * [H2oMessagingService.onRegistered] with the install's Firebase
     * installation ID (FID), which is what the server sends to.
     */
    fun register(context: Context) {
        if (!configured || FirebaseApp.getApps(context).isEmpty()) return
        FirebaseMessaging.getInstance().register().addOnFailureListener { Log.w(TAG, "FCM registration failed", it) }
    }
}

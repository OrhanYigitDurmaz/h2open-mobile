package tr.h2open.driver.push

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.suspendCancellableCoroutine
import tr.h2open.driver.BuildConfig
import kotlin.coroutines.resume

/**
 * Firebase Cloud Messaging, set up from the `google-services.json` the
 * build read (see app/build.gradle.kts). Without it push is off and the
 * phone only rings while the app has a call going.
 */
object Push {
    private const val TAG = "H2oPush"

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

    /** This install's FCM token, or null when push is off or Google Play services can't give one. */
    suspend fun token(context: Context): String? {
        if (!configured || FirebaseApp.getApps(context).isEmpty()) return null
        return suspendCancellableCoroutine { cont ->
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (!task.isSuccessful) Log.w(TAG, "No FCM token", task.exception)
                cont.resume(if (task.isSuccessful) task.result else null)
            }
        }
    }
}

package tr.h2open.driver.voip

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import tr.h2open.driver.R

/**
 * Hands our calls to Android's call system as self-managed calls, so the
 * phone treats them like phone calls: a GSM call that comes in holds ours
 * (and ours waits for it), the car kit's buttons answer and hang up, and the
 * audio goes where Android routes calls. Where the phone has no call system,
 * or refuses a call, the call goes on without it ([connection] stays null).
 */
class TelecomCalls(private val context: Context) {
    private val telecom = context.getSystemService(TelecomManager::class.java)
    private val handle = PhoneAccountHandle(ComponentName(context, H2oConnectionService::class.java), ACCOUNT_ID)
    private var registered = false

    /** The call system's side of the current call, once it has accepted it. */
    var connection: VoipConnection? = null
        internal set

    // Android 17 deprecates these in favour of the Jetpack core-telecom library;
    // the platform calls still work on every version the app supports.
    @Suppress("DEPRECATION")
    private fun register(): Boolean {
        if (registered) return true
        if (telecom == null || !context.packageManager.hasSystemFeature(PackageManager.FEATURE_CONNECTION_SERVICE)) return false
        registered = try {
            val account = PhoneAccount.builder(handle, context.getString(R.string.app_name))
                .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                .setSupportedUriSchemes(listOf(PhoneAccount.SCHEME_TEL))
                .build()
            telecom.registerPhoneAccount(account)
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "No self-managed calls on this phone", e)
            false
        }
        return registered
    }

    /** Tells the call system we are dialling [number]; it answers by creating the connection. */
    fun placeOutgoing(number: String, name: String?) {
        if (!register()) return
        try {
            if (!telecom.isOutgoingCallPermitted(handle)) return
            val extras = Bundle().apply {
                putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
                putBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS, Bundle().apply { putString(EXTRA_NAME, name) })
            }
            telecom.placeCall(Uri.fromParts(PhoneAccount.SCHEME_TEL, number, null), extras)
        } catch (e: SecurityException) {
            // MANAGE_OWN_CALLS is granted at install; this only happens if the phone refuses it.
            Log.w(TAG, "No permission to hand the call to the call system", e)
        } catch (e: RuntimeException) {
            Log.w(TAG, "The call system refused the outgoing call", e)
        }
    }

    /** Tells the call system [number] is calling us; it answers by creating the connection. */
    fun addIncoming(number: String, name: String?) {
        if (!register()) return
        try {
            if (!telecom.isIncomingCallPermitted(handle)) return
            val extras = Bundle().apply {
                putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, Uri.fromParts(PhoneAccount.SCHEME_TEL, number, null))
                putBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS, Bundle().apply { putString(EXTRA_NAME, name) })
            }
            telecom.addNewIncomingCall(handle, extras)
        } catch (e: RuntimeException) {
            Log.w(TAG, "The call system refused the incoming call", e)
        }
    }

    companion object {
        private const val TAG = "H2oTelecom"
        private const val ACCOUNT_ID = "h2open_pbx"
        const val EXTRA_NAME = "tr.h2open.driver.extra.NAME"
    }
}

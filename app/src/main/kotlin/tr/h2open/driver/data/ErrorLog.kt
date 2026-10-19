package tr.h2open.driver.data

import android.util.Log
import tr.h2open.driver.api.ApiException
import tr.h2open.driver.api.BadResponseException
import tr.h2open.driver.api.NetworkException

/**
 * Every error the driver sees also goes to logcat with its stack trace, under
 * tags starting with "H2Open" (`adb logcat | grep H2Open`), so a report can
 * name exactly what failed.
 */
object ErrorLog {
    const val TAG = "H2Open"

    /** Logs [error] as the failure of [what] (a screen and an action, like "Stop: deliver"). */
    fun log(what: String, error: Throwable) {
        val extra = when (error) {
            is ApiException -> error.responseBody?.let { "\nOdoo answered: $it" }
            is BadResponseException -> "\nOdoo answered: ${error.responseBody}"
            else -> null
        }
        Log.e(TAG, "$what failed: ${describe(error)}${extra.orEmpty()}", error)
    }

    /**
     * One line naming what failed and how, for the screen and the log:
     * `HTTP 422 POST driver/trips/3/close: Trip still has pending stops`,
     * `GET driver/trips: UnknownHostException: Unable to resolve host "odoo.example"`.
     */
    fun describe(error: Throwable): String = when (error) {
        is ApiException -> listOfNotNull("HTTP ${error.status}", error.request).joinToString(" ") + ": ${error.message}"
        is NetworkException -> error.message.orEmpty()
        is BadResponseException -> error.message.orEmpty()
        else -> generateSequence(error) { it.cause.takeIf { cause -> cause !== it } }
            .take(3)
            .joinToString(" ← ") { "${it.javaClass.simpleName}: ${it.message}" }
    }
}

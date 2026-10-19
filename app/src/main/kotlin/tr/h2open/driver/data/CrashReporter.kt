package tr.h2open.driver.data

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tr.h2open.driver.BuildConfig
import tr.h2open.driver.api.CrashReport
import tr.h2open.driver.api.H2oApi
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant

/**
 * Saves a crash on the phone as it happens, and sends the saved ones to
 * Odoo (Configuration > App Crashes) once the app runs signed in and online.
 */
object CrashReporter {
    private const val TAG = "H2oCrash"
    private const val MAX_SAVED = 20

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { save(appContext, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun dir(context: Context) = File(context.filesDir, "crashes")

    private fun save(context: Context, error: Throwable) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val report = CrashReport(
            stackTrace = trace,
            app = "driver",
            appVersion = BuildConfig.VERSION_NAME,
            device = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            osVersion = Build.VERSION.RELEASE,
            occurredAt = Instant.now().toString(),
        )
        val dir = dir(context).apply { mkdirs() }
        File(dir, "${System.currentTimeMillis()}.json").writeText(H2oApi.JSON.encodeToString(CrashReport.serializer(), report))
        // A crash on every start must not fill the phone.
        dir.listFiles()?.sortedByDescending { it.name }?.drop(MAX_SAVED)?.forEach { it.delete() }
    }

    /** Sends the saved crash reports with [api]; they are deleted once Odoo has them. */
    suspend fun sendSaved(context: Context, api: H2oApi) {
        val files = withContext(Dispatchers.IO) { dir(context).listFiles()?.sortedBy { it.name }.orEmpty() }
        if (files.isEmpty()) return
        val reports = withContext(Dispatchers.IO) {
            files.mapNotNull { file ->
                runCatching { H2oApi.JSON.decodeFromString(CrashReport.serializer(), file.readText()) }
                    .onFailure { file.delete() }
                    .getOrNull()
                    ?.let { file to it }
            }
        }
        if (reports.isEmpty()) return
        try {
            api.sendCrashes(reports.map { it.second })
            withContext(Dispatchers.IO) { reports.forEach { it.first.delete() } }
        } catch (e: Exception) {
            // Kept for the next try.
            Log.w(TAG, "Could not send crash reports", e)
        }
    }
}

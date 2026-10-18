package tr.h2open.driver.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tr.h2open.driver.api.H2oApi
import tr.h2open.driver.api.UserProfile
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A signed-in driver: the Odoo server, its app token and the profile it returned. */
data class Session(val serverUrl: String, val token: String, val user: UserProfile)

/**
 * Keeps the session across restarts. The token is encrypted with a key that
 * never leaves the Android Keystore, so a copy of the app's files is useless.
 */
class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    private val _session = MutableStateFlow(load())
    val session: StateFlow<Session?> = _session.asStateFlow()

    /** The last server signed in to, prefilled on the login screen. */
    val lastServerUrl: String get() = prefs.getString(KEY_SERVER, null).orEmpty()

    fun save(session: Session) {
        prefs.edit {
            putString(KEY_SERVER, session.serverUrl)
            putString(KEY_TOKEN, encrypt(session.token))
            putString(KEY_USER, H2oApi.JSON.encodeToString(UserProfile.serializer(), session.user))
        }
        _session.value = session
    }

    /** Forgets the token (the server URL stays for the next sign-in). */
    fun clear() {
        prefs.edit {
            remove(KEY_TOKEN)
            remove(KEY_USER)
        }
        _session.value = null
    }

    private fun load(): Session? {
        val server = prefs.getString(KEY_SERVER, null) ?: return null
        val token = prefs.getString(KEY_TOKEN, null)?.let(::decrypt) ?: return null
        val user = prefs.getString(KEY_USER, null)?.let {
            runCatching { H2oApi.JSON.decodeFromString(UserProfile.serializer(), it) }.getOrNull()
        } ?: return null
        return Session(server, token, user)
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(plain.toByteArray())
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    /** Null when the key is gone (app data restored elsewhere, keystore reset): sign in again. */
    private fun decrypt(stored: String): String? = runCatching {
        val sealed = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, IV_SIZE))
        String(cipher.doFinal(sealed, IV_SIZE, sealed.size - IV_SIZE))
    }.getOrNull()

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "h2o_session"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val KEY_SERVER = "server_url"
        const val KEY_TOKEN = "token"
        const val KEY_USER = "user"
    }
}

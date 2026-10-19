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
import tr.h2open.driver.api.TokenStore
import tr.h2open.driver.api.Tokens
import tr.h2open.driver.api.UserProfile
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A signed-in driver: the Odoo server and the profile it returned. */
data class Session(val serverUrl: String, val user: UserProfile)

/**
 * Keeps the session and its tokens across restarts. The tokens are encrypted
 * with a key that never leaves the Android Keystore, so a copy of the app's
 * files is useless. [clear] (signing out, or the server refusing a refresh)
 * sends the app back to the login screen.
 */
class SessionStore(context: Context) : TokenStore {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    private val _session = MutableStateFlow(loadSession())
    val session: StateFlow<Session?> = _session.asStateFlow()

    /** The last server signed in to, prefilled on the login screen. */
    val lastServerUrl: String get() = prefs.getString(KEY_SERVER, null).orEmpty()

    /** Called after a successful login, once [save] stored its tokens. */
    fun signedIn(session: Session) {
        prefs.edit {
            putString(KEY_SERVER, session.serverUrl)
            putString(KEY_USER, H2oApi.JSON.encodeToString(UserProfile.serializer(), session.user))
        }
        _session.value = session
    }

    @Synchronized
    override fun load(): Tokens? {
        val access = prefs.getString(KEY_ACCESS, null)?.let(::decrypt) ?: return null
        return Tokens(
            accessToken = access,
            refreshToken = prefs.getString(KEY_REFRESH, null)?.let(::decrypt),
            accessExpiresAtMillis = prefs.getLong(KEY_ACCESS_EXPIRES, 0).takeIf { it > 0 },
            refreshExpiresAtMillis = prefs.getLong(KEY_REFRESH_EXPIRES, 0).takeIf { it > 0 },
        )
    }

    /** Written synchronously: a rotated refresh token must not be lost if the app dies right after. */
    @Synchronized
    override fun save(tokens: Tokens) {
        prefs.edit(commit = true) {
            putString(KEY_ACCESS, encrypt(tokens.accessToken))
            tokens.refreshToken?.let { putString(KEY_REFRESH, encrypt(it)) } ?: remove(KEY_REFRESH)
            putLong(KEY_ACCESS_EXPIRES, tokens.accessExpiresAtMillis ?: 0)
            putLong(KEY_REFRESH_EXPIRES, tokens.refreshExpiresAtMillis ?: 0)
        }
    }

    /** Forgets the tokens and the user (the server URL stays for the next sign-in). */
    @Synchronized
    override fun clear() {
        prefs.edit(commit = true) {
            listOf(KEY_ACCESS, KEY_REFRESH, KEY_ACCESS_EXPIRES, KEY_REFRESH_EXPIRES, KEY_USER, KEY_LEGACY_TOKEN).forEach { remove(it) }
        }
        _session.value = null
    }

    private fun loadSession(): Session? {
        val server = prefs.getString(KEY_SERVER, null) ?: return null
        if (load() == null) return null
        val user = prefs.getString(KEY_USER, null)?.let {
            runCatching { H2oApi.JSON.decodeFromString(UserProfile.serializer(), it) }
                .onFailure { e -> ErrorLog.log("Read the saved profile (sign in again)", e) }
                .getOrNull()
        } ?: return null
        return Session(server, user)
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
    }.onFailure { ErrorLog.log("Decrypt the saved tokens (sign in again)", it) }.getOrNull()

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "h2o_session"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val KEY_SERVER = "server_url"
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_ACCESS_EXPIRES = "access_expires_at"
        const val KEY_REFRESH_EXPIRES = "refresh_expires_at"
        /** The single token of the first test builds: dropped, those installs sign in again. */
        const val KEY_LEGACY_TOKEN = "token"
        const val KEY_USER = "user"
    }
}

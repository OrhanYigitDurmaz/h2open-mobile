package tr.h2open.driver.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The device's tokens. The access token goes on every request and lives about
 * a day; the refresh token gets a new pair and lives about 90 days from its
 * last use, so a driver who works every week never signs in again.
 * Expiry times are on this device's clock (receipt time + `*_expires_in`),
 * so a phone with the wrong time still refreshes when it should.
 */
data class Tokens(
    val accessToken: String,
    /** Null when the server predates refresh tokens: sign in again when the access token expires. */
    val refreshToken: String?,
    val accessExpiresAtMillis: Long?,
    val refreshExpiresAtMillis: Long?,
)

/** Where the app keeps [Tokens]; on Android, encrypted with a Keystore key. */
interface TokenStore {
    fun load(): Tokens?
    fun save(tokens: Tokens)
    /** The server refused the tokens for good: the driver has to sign in again. */
    fun clear()
}

/** The token part of `login` and `token/refresh` answers (h2o_base). */
@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("access_expires_in") val accessExpiresIn: Long? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("refresh_expires_in") val refreshExpiresIn: Long? = null,
    /** Older servers answer only this, a 30-day key with no refresh. */
    val token: String? = null,
) {
    fun toTokens(nowMillis: Long): Tokens = Tokens(
        accessToken = accessToken ?: token ?: error("No token in the answer"),
        refreshToken = refreshToken,
        accessExpiresAtMillis = accessExpiresIn?.let { nowMillis + it * 1000 },
        refreshExpiresAtMillis = refreshExpiresIn?.let { nowMillis + it * 1000 },
    )
}

@Serializable
data class RefreshRequest(@SerialName("refresh_token") val refreshToken: String)

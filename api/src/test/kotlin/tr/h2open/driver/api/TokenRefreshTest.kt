package tr.h2open.driver.api

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class TokenRefreshTest {
    private lateinit var server: MockWebServer
    private var now = 1_000_000L
    private val store = MemoryTokenStore()
    private lateinit var api: H2oApi

    /** Server-side state: the valid access and refresh tokens, and how many refreshes ran. */
    private var validAccess = "a1"
    private var validRefresh = "r1"
    private var refreshAnswer: Int = 200
    private val refreshes = AtomicInteger()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/h2o/api/v1/login" -> json("{${tokenFields("a1", "r1")}, \"user\": $USER}")
                "/h2o/api/v1/token/refresh" -> synchronized(this) {
                    val sent = request.body.readUtf8()
                    if (refreshAnswer != 200 || !sent.contains("\"$validRefresh\"")) {
                        error401("Invalid refresh token")
                    } else {
                        val n = refreshes.incrementAndGet() + 1
                        validAccess = "a$n"
                        validRefresh = "r$n"
                        json("{${tokenFields(validAccess, validRefresh)}}")
                    }
                }
                else -> if (request.getHeader("Authorization") == "Bearer $validAccess") json(USER) else error401("Invalid apikey")
            }
        }
        server.start()
        api = H2oApi(server.url("/").toString(), store, clock = { now })
    }

    @After
    fun tearDown() = server.shutdown()

    private fun tokenFields(access: String, refresh: String) =
        """"access_token": "$access", "access_expires_in": 86400, "access_expires_at": "2026-10-19T15:48:00Z",
            "refresh_token": "$refresh", "refresh_expires_in": 7776000"""

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun error401(message: String) = MockResponse().setResponseCode(401)
        .setHeader("Content-Type", "application/json").setBody("""{"message": "$message"}""")

    @Test
    fun `login keeps both tokens with local expiry times`() = runTest {
        api.login("ali", "pw", "Pixel")
        assertEquals(Tokens("a1", "r1", now + 86_400_000, now + 7_776_000_000), store.tokens)
    }

    @Test
    fun `calls use the access token until it is close to expiring`() = runTest {
        api.login("ali", "pw", "Pixel")
        now += 23 * 3_600_000L
        api.me()
        assertEquals(0, refreshes.get())

        now += 3_600_000L - 60_000 // a minute left
        api.me()
        assertEquals(1, refreshes.get())
        assertEquals("a2", store.tokens?.accessToken)
        assertEquals("r2", store.tokens?.refreshToken)
    }

    @Test
    fun `a 401 refreshes once and retries`() = runTest {
        api.login("ali", "pw", "Pixel")
        validAccess = "revoked-on-server" // e.g. the server lost the key
        // The refresh still works with r1; the retry then uses the new key.
        assertEquals("ali", api.me().login)
        assertEquals(1, refreshes.get())
    }

    @Test
    fun `concurrent 401s share one refresh`() = runTest {
        api.login("ali", "pw", "Pixel")
        validAccess = "revoked-on-server"
        val results = (1..5).map { async { api.me() } }.awaitAll()
        assertEquals(5, results.size)
        assertEquals(1, refreshes.get())
    }

    @Test
    fun `a refused refresh signs out`() = runTest {
        api.login("ali", "pw", "Pixel")
        validAccess = "revoked-on-server"
        refreshAnswer = 401
        try {
            api.me()
            fail("expected UnauthorizedException")
        } catch (_: UnauthorizedException) {
        }
        assertTrue(store.cleared)
    }

    @Test
    fun `network errors during a refresh keep the tokens`() = runTest {
        api.login("ali", "pw", "Pixel")
        now += 86_400_000
        server.shutdown()
        try {
            api.me()
            fail("expected an IOException")
        } catch (e: java.io.IOException) {
            assertFalse(e is UnauthorizedException)
        }
        assertFalse(store.cleared)
        assertEquals("r1", store.tokens?.refreshToken)
    }

    private companion object {
        const val USER = """{"id": 7, "name": "Ali", "login": "ali", "roles": ["driver"]}"""
    }
}

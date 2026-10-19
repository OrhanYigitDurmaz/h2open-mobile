package tr.h2open.driver.api

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class H2oApiTest {
    private lateinit var server: MockWebServer
    private val store = MemoryTokenStore(Tokens("secret", null, null, null))
    private lateinit var api: H2oApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = H2oApi(server.url("/").toString(), store)
    }

    @After
    fun tearDown() = server.close()

    private fun json(body: String, code: Int = 200) =
        MockResponse.Builder().code(code).setHeader("Content-Type", "application/json").body(body).build()

    @Test
    fun `login posts credentials without a token`() = runTest {
        server.enqueue(json("""{"token": "abc", "user": {"id": 7, "name": "Ali", "login": "ali",
            "lang": "tr_TR", "tz": false, "company": {"id": 1, "name": "H2Open"}, "roles": ["driver"]}}"""))
        val user = api.login("ali", "pw", "Pixel 8")
        // An older server: one key, nothing to refresh with.
        assertEquals(Tokens("abc", null, null, null), store.tokens)
        assertTrue(user.isDriver)
        assertNull(user.tz)

        val req = server.takeRequest()
        assertEquals("/h2o/api/v1/login", req.target)
        assertNull(req.headers["Authorization"])
        assertEquals("""{"login":"ali","password":"pw","device_name":"Pixel 8"}""", req.body!!.utf8())
    }

    @Test
    fun `trips sends the bearer token and date`() = runTest {
        server.enqueue(json("""[{"id": 3, "name": "TRIP/0003", "date": "2026-10-18", "state": "planned",
            "route": {"id": 1, "name": "Kadıköy"}, "vehicle": {"id": 2, "name": "Van", "license_plate": false},
            "stop_count": 4, "pending_count": 4, "delivered_count": 0, "failed_count": 0,
            "planned_bottle_qty": 12, "load_qty": 0, "cash_expected": 450.0, "currency": "TRY", "note": ""}]"""))
        val trips = api.trips("2026-10-18")
        assertEquals(1, trips.size)
        assertEquals("Kadıköy", trips[0].route?.name)
        assertNull(trips[0].vehicle?.licensePlate)
        assertTrue(trips[0].canStart)
        assertFalse(trips[0].canClose)

        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/h2o/api/v1/driver/trips?date=2026-10-18", req.target)
        assertEquals("Bearer secret", req.headers["Authorization"])
    }

    @Test
    fun `stop empties expected wins over the order's`() = runTest {
        server.enqueue(json(TRIP_DETAIL.replace("\"empties_collected\": 0,", "\"empties_collected\": 0, \"empties_expected\": 5,")))
        assertEquals(5, api.trip(3).stops.single().emptiesExpected)
    }

    @Test
    fun `crash reports`() = runTest {
        server.enqueue(json("""{"received": 1}"""))
        val sent = api.sendCrashes(listOf(CrashReport("java.lang.IllegalStateException: boom", appVersion = "0.2.0",
            device = "Samsung SM-A155F", osVersion = "15", occurredAt = "2026-10-19T08:30:00Z")))
        assertEquals(1, sent)
        val req = server.takeRequest()
        assertEquals("/h2o/api/v1/crashes", req.target)
        assertEquals("Bearer secret", req.headers["Authorization"])
        assertEquals("""{"reports":[{"stack_trace":"java.lang.IllegalStateException: boom","app_version":"0.2.0",""" +
            """"device":"Samsung SM-A155F","os_version":"15","occurred_at":"2026-10-19T08:30:00Z"}]}""", req.body!!.utf8())
    }

    @Test
    fun `sip credentials`() = runTest {
        server.enqueue(json("""{"username": "1001", "password": "pw", "domain": "pbx.h2open.com.tr",
            "wss_url": "wss://vps.h2open.com.tr/ws", "expires_at": "2026-10-18T22:00:00Z", "ice_servers": []}"""))
        val creds = api.sipCredentials()
        assertEquals("wss://vps.h2open.com.tr/ws", creds.wssUrl)
        assertEquals(java.time.Instant.parse("2026-10-18T22:00:00Z").toEpochMilli(), creds.expiresAtMillis)
        assertEquals("POST", server.takeRequest().method)
    }

    @Test
    fun `sip credentials with unset settings`() = runTest {
        server.enqueue(json("""{"username": "1001", "password": "pw", "domain": false, "wss_url": false,
            "expires_at": "2026-10-18T22:00:00Z", "ice_servers": []}"""))
        val creds = api.sipCredentials()
        assertNull(creds.domain)
        assertNull(creds.wssUrl)
    }

    @Test
    fun `trip detail parses stops`() = runTest {
        server.enqueue(json(TRIP_DETAIL))
        val trip = api.trip(3)
        val stop = trip.stops.single()
        assertEquals("Moda Cad. 12, Daire 4, 34710 İstanbul", stop.partner.address)
        assertEquals(3, stop.partner.floor)
        assertEquals(2, stop.order?.emptiesExpected)
        // No stop-level field in this payload: falls back to the order's.
        assertEquals(2, stop.emptiesExpected)
        assertEquals(2.0, stop.lines[0].qtyPlanned, 0.0)
        assertNull(stop.paymentMethod)
        assertNull(stop.doneAt)
        assertTrue(stop.isPending)
    }

    @Test
    fun `deliver leaves out unset fields`() = runTest {
        server.enqueue(json(STOP_DELIVERED))
        val stop = api.deliver(11, DeliverRequest(
            lines = listOf(DeliveredLine(5, 2.0)),
            emptiesCollected = 2,
            paymentMethod = PaymentMethod.CASH,
        ))
        assertEquals(StopState.DELIVERED, stop.state)
        val body = server.takeRequest().body!!.utf8()
        assertEquals("""{"lines":[{"product_id":5,"qty_delivered":2.0}],"empties_collected":2,"payment_method":"cash"}""", body)
    }

    @Test
    fun `wrong state surfaces the Odoo message`() = runTest {
        server.enqueue(json("""{"name": "odoo.exceptions.UserError", "message": "Trip TRIP/0003 still has pending stops.",
            "arguments": ["Trip TRIP/0003 still has pending stops."], "context": {}}""", code = 422))
        try {
            api.closeTrip(3)
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals(422, e.status)
            assertEquals("Trip TRIP/0003 still has pending stops.", e.message)
        }
    }

    @Test
    fun `errors name the call that failed`() = runTest {
        server.enqueue(json("""{"message": "boom"}""", code = 500))
        try {
            api.deliver(12, DeliverRequest(lines = emptyList(), emptiesCollected = 0, paymentMethod = PaymentMethod.CASH))
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals(500, e.status)
            assertEquals("POST driver/stops/12/deliver", e.request)
            assertEquals("""{"message": "boom"}""", e.responseBody)
        }
    }

    @Test
    fun `an answer the app cannot read is a BadResponseException`() = runTest {
        server.enqueue(json("""[1, 2]"""))
        try {
            api.trip(3)
            fail("expected BadResponseException")
        } catch (e: BadResponseException) {
            assertEquals("GET driver/trips/3", e.request)
            assertEquals("[1, 2]", e.responseBody)
        }
    }

    @Test
    fun `no answer at all is a NetworkException naming the call`() = runTest {
        server.close()
        try {
            api.trips()
            fail("expected NetworkException")
        } catch (e: NetworkException) {
            assertEquals("GET driver/trips", e.request)
            assertTrue(e.cause is java.io.IOException)
        }
    }

    @Test
    fun `werkzeug errors lose their status prefix`() {
        assertEquals("Stop not found", H2oApi.errorMessage("""{"message": "404 Not Found: Stop not found"}"""))
        assertEquals("Only drivers can use the driver app",
            H2oApi.errorMessage("<!doctype html><title>403 Forbidden</title><h1>Forbidden</h1><p>Only drivers can use the driver app</p>"))
        assertNull(H2oApi.errorMessage(""))
    }

    @Test
    fun `401 without a refresh token means signed out`() = runTest {
        server.enqueue(json("""{"message": "Invalid apikey"}""", code = 401))
        try {
            api.trips()
            fail("expected UnauthorizedException")
        } catch (_: UnauthorizedException) {
        }
        assertTrue(store.cleared)
        assertEquals(1, server.requestCount)
    }

    @Test(expected = UnauthorizedException::class)
    fun `no token never calls the server`() = runTest {
        store.tokens = null
        try {
            api.trips()
        } finally {
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `open orders and claiming one`() = runTest {
        server.enqueue(json("""[{"id": 9, "name": "S00009", "delivery_date": "2026-10-18", "time_window": "any",
            "zone": {"id": 1, "name": "Moda"}, "my_zone": true,
            "partner": {"id": 21, "name": "Ayşe Yılmaz", "street": "Moda Cad. 12", "city": "İstanbul", "zip": "34710"},
            "note": "", "empties_expected": 2, "payment_mode": null, "amount_total": 100.0, "currency": "TRY",
            "lines": [{"product_id": 5, "product_name": "19 L Damacana", "product_kind": "water_returnable",
                "uom": "Units", "qty": 2.0}]}]"""))
        val order = api.openOrders().single()
        assertTrue(order.myZone)
        assertEquals("Moda Cad. 12, 34710 İstanbul", order.partner.address)
        assertEquals(2.0, order.lines.single().qty, 0.0)
        assertEquals("/h2o/api/v1/driver/orders", server.takeRequest().target)

        val claimedStop = STOP_PENDING.replace(""""done_at": false""", """"done_at": false, "claimed": true""")
        server.enqueue(json("""{"trip": ${TRIP_DETAIL.replace("\"stops\": [$STOP_PENDING]", "\"stops\": []")},
            "stop": $claimedStop}"""))
        val result = api.claimOrder(9)
        assertEquals(3, result.trip.id)
        assertTrue(result.stop.canRelease)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/h2o/api/v1/driver/orders/9/claim", req.target)

        server.enqueue(json("""{"message": "This order was already taken"}""", code = 409))
        try {
            api.claimOrder(9)
            fail("expected a conflict")
        } catch (e: ApiException) {
            assertEquals(409, e.status)
            assertEquals("This order was already taken", e.message)
        }
    }

    @Test
    fun `planned stops cannot be given back`() = runTest {
        server.enqueue(json(TRIP_DETAIL))
        assertFalse(api.trip(3).stops.single().canRelease)
    }

    @Test
    fun `base url is normalized`() {
        assertEquals("https://odoo.h2open.com.tr/", H2oApi.normalizeBaseUrl(" odoo.h2open.com.tr "))
        assertEquals("http://10.0.2.2:8069/", H2oApi.normalizeBaseUrl("http://10.0.2.2:8069"))
        assertEquals("https://192.168.12.1:8069/", H2oApi.normalizeBaseUrl("192.168.12.1:8069"))
        assertEquals("http://192.168.12.1:8069/", H2oApi.normalizeBaseUrl("http://192.168.12.1:8069/"))
        assertEquals("HTTPS://Odoo.example.com/", H2oApi.normalizeBaseUrl("HTTPS://Odoo.example.com"))
    }

    companion object {
        val STOP_PENDING = """{"id": 11, "trip_id": 3, "sequence": 1, "state": "pending",
            "order": {"id": 9, "name": "S00009", "note": "", "empties_expected": 2},
            "time_window": "morning",
            "partner": {"id": 21, "name": "Ayşe Yılmaz", "customer_name": "Ayşe Yılmaz", "customer_type": "home",
                "street": "Moda Cad. 12", "street2": "Daire 4", "city": "İstanbul", "zip": "34710", "state": "İstanbul",
                "phone": "+90 532 000 00 00", "floor": 3, "has_elevator": false, "delivery_notes": "Ring twice",
                "latitude": 40.98, "longitude": 29.03, "bottle_balance": 2},
            "zone": {"id": 1, "name": "Moda"},
            "lines": [{"id": 31, "product_id": 5, "product_name": "19 L Damacana", "product_kind": "water",
                "uom": "Units", "qty_planned": 2.0, "qty_delivered": 0.0, "price_unit": 50.0}],
            "empties_product_id": 6, "empties_collected": 0, "payment_mode": "cash", "payment_method": false,
            "amount_due": 100.0, "payment_amount": 0.0, "currency": "TRY", "failure_reason": null,
            "failure_note": "", "note": "", "done_at": false}"""

        val STOP_DELIVERED = STOP_PENDING.replace(""""state": "pending"""", """"state": "delivered"""")

        val TRIP_DETAIL = """{"id": 3, "name": "TRIP/0003", "date": "2026-10-18", "state": "on_road",
            "route": null, "vehicle": null, "stop_count": 1, "pending_count": 1, "delivered_count": 0,
            "failed_count": 0, "planned_bottle_qty": 2, "load_qty": 2, "cash_expected": 100.0,
            "currency": "TRY", "note": "", "stops": [$STOP_PENDING]}"""
    }
}

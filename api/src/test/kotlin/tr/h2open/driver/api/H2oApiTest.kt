package tr.h2open.driver.api

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
    private var token: String? = "secret"
    private lateinit var api: H2oApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = H2oApi(server.url("/").toString(), { token })
    }

    @After
    fun tearDown() = server.shutdown()

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Test
    fun `login posts credentials without a token`() = runTest {
        server.enqueue(json("""{"token": "abc", "user": {"id": 7, "name": "Ali", "login": "ali",
            "lang": "tr_TR", "tz": false, "company": {"id": 1, "name": "H2Open"}, "roles": ["driver"]}}"""))
        val res = api.login("ali", "pw", "Pixel 8")
        assertEquals("abc", res.token)
        assertTrue(res.user.isDriver)
        assertNull(res.user.tz)

        val req = server.takeRequest()
        assertEquals("/h2o/api/v1/login", req.path)
        assertNull(req.getHeader("Authorization"))
        assertEquals("""{"login":"ali","password":"pw","device_name":"Pixel 8"}""", req.body.readUtf8())
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
        assertEquals("/h2o/api/v1/driver/trips?date=2026-10-18", req.path)
        assertEquals("Bearer secret", req.getHeader("Authorization"))
    }

    @Test
    fun `trip detail parses stops`() = runTest {
        server.enqueue(json(TRIP_DETAIL))
        val trip = api.trip(3)
        val stop = trip.stops.single()
        assertEquals("Moda Cad. 12, Daire 4, 34710 İstanbul", stop.partner.address)
        assertEquals(3, stop.partner.floor)
        assertEquals(2, stop.order?.emptiesExpected)
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
        val body = server.takeRequest().body.readUtf8()
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
    fun `werkzeug errors lose their status prefix`() {
        assertEquals("Stop not found", H2oApi.errorMessage("""{"message": "404 Not Found: Stop not found"}"""))
        assertEquals("Only drivers can use the driver app",
            H2oApi.errorMessage("<!doctype html><title>403 Forbidden</title><h1>Forbidden</h1><p>Only drivers can use the driver app</p>"))
        assertNull(H2oApi.errorMessage(""))
    }

    @Test(expected = UnauthorizedException::class)
    fun `401 means signed out`() = runTest {
        server.enqueue(json("""{"message": "Invalid apikey"}""", code = 401))
        api.trips()
    }

    @Test(expected = UnauthorizedException::class)
    fun `no token never calls the server`() = runTest {
        token = null
        try {
            api.trips()
        } finally {
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `base url is normalized`() {
        assertEquals("https://odoo.h2open.com.tr/", H2oApi.normalizeBaseUrl(" odoo.h2open.com.tr "))
        assertEquals("http://10.0.2.2:8069/", H2oApi.normalizeBaseUrl("http://10.0.2.2:8069"))
        assertEquals("http://192.168.12.1:8069/", H2oApi.normalizeBaseUrl("192.168.12.1:8069"))
        assertEquals("http://localhost:8069/", H2oApi.normalizeBaseUrl("localhost:8069"))
        assertEquals("https://8.8.8.8/", H2oApi.normalizeBaseUrl("8.8.8.8"))
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

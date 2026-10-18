package tr.h2open.driver.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

// Shapes of the h2o_base and h2o_driver JSON API (SPEC.md in h2open-modules).

/** Odoo sends `false` for an empty char field it didn't clean up; read it as null. */
object OdooStringSerializer : KSerializer<String?> {
    override val descriptor = PrimitiveSerialDescriptor("OdooString", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String? {
        val element = (decoder as JsonDecoder).decodeJsonElement()
        if (element is JsonNull) return null
        val primitive = element as? JsonPrimitive ?: return null
        if (!primitive.isString && primitive.booleanOrNull == false) return null
        return primitive.content
    }

    override fun serialize(encoder: Encoder, value: String?) {
        if (value == null) encoder.encodeNull() else encoder.encodeString(value)
    }
}

@Serializable
data class Ref(val id: Long, val name: String)

@Serializable
data class UserProfile(
    val id: Long,
    val name: String,
    val login: String,
    @Serializable(OdooStringSerializer::class) val lang: String? = null,
    @Serializable(OdooStringSerializer::class) val tz: String? = null,
    val company: Ref? = null,
    val roles: List<String> = emptyList(),
) {
    // Dispatchers and managers imply the driver group, so they carry "driver" too.
    val isDriver get() = "driver" in roles
}

@Serializable
data class LoginRequest(
    val login: String,
    val password: String,
    @SerialName("device_name") val deviceName: String,
)

/** The user part of the login answer; the tokens are read as [TokenResponse]. */
@Serializable
data class LoginResponse(val user: UserProfile)

object TripState {
    const val PLANNED = "planned"
    const val LOADED = "loaded"
    const val ON_ROAD = "on_road"
    const val CLOSED = "closed"
    const val CANCELLED = "cancelled"
}

object StopState {
    const val PENDING = "pending"
    const val DELIVERED = "delivered"
    const val FAILED = "failed"
    const val SKIPPED = "skipped"
}

object PaymentMethod {
    const val CASH = "cash"
    const val CARD = "card"
    const val ACCOUNT = "account"
    const val NONE = "none"
    val all = listOf(CASH, CARD, ACCOUNT, NONE)
}

object FailureReason {
    const val NOT_HOME = "not_home"
    const val REFUSED = "refused"
    const val NO_ACCESS = "no_access"
    const val NO_PAYMENT = "no_payment"
    const val OTHER = "other"
    val all = listOf(NOT_HOME, REFUSED, NO_ACCESS, NO_PAYMENT, OTHER)
}

@Serializable
data class Vehicle(
    val id: Long,
    val name: String,
    @SerialName("license_plate") @Serializable(OdooStringSerializer::class) val licensePlate: String? = null,
)

@Serializable
data class Trip(
    val id: Long,
    val name: String,
    val date: String,
    val state: String,
    val route: Ref? = null,
    val vehicle: Vehicle? = null,
    @SerialName("stop_count") val stopCount: Int = 0,
    @SerialName("pending_count") val pendingCount: Int = 0,
    @SerialName("delivered_count") val deliveredCount: Int = 0,
    @SerialName("failed_count") val failedCount: Int = 0,
    @SerialName("planned_bottle_qty") val plannedBottleQty: Int = 0,
    @SerialName("load_qty") val loadQty: Int = 0,
    @SerialName("cash_expected") val cashExpected: Double = 0.0,
    val currency: String = "TRY",
    val note: String = "",
    /** Only in trip detail responses. */
    val stops: List<Stop> = emptyList(),
) {
    val canStart get() = state == TripState.PLANNED || state == TripState.LOADED
    val canClose get() = (state == TripState.LOADED || state == TripState.ON_ROAD) && pendingCount == 0
    val isActive get() = state == TripState.ON_ROAD
}

@Serializable
data class StopOrder(
    val id: Long,
    val name: String,
    val note: String = "",
    @SerialName("empties_expected") val emptiesExpected: Int = 0,
)

@Serializable
data class Partner(
    val id: Long,
    val name: String,
    @SerialName("customer_name") val customerName: String = "",
    @SerialName("customer_type") val customerType: String? = null,
    val street: String = "",
    val street2: String = "",
    val city: String = "",
    val zip: String = "",
    val state: String = "",
    val phone: String = "",
    val floor: Int = 0,
    @SerialName("has_elevator") val hasElevator: Boolean = false,
    @SerialName("delivery_notes") val deliveryNotes: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    @SerialName("bottle_balance") val bottleBalance: Int = 0,
) {
    val address get() = listOf(street, street2, listOf(zip, city).filter { it.isNotBlank() }.joinToString(" "))
        .filter { it.isNotBlank() }
        .joinToString(", ")
}

@Serializable
data class StopLine(
    val id: Long,
    @SerialName("product_id") val productId: Long,
    @SerialName("product_name") val productName: String,
    @SerialName("product_kind") val productKind: String? = null,
    val uom: String = "",
    @SerialName("qty_planned") val qtyPlanned: Double = 0.0,
    @SerialName("qty_delivered") val qtyDelivered: Double = 0.0,
    @SerialName("price_unit") val priceUnit: Double = 0.0,
)

@Serializable
data class Stop(
    val id: Long,
    @SerialName("trip_id") val tripId: Long,
    val sequence: Int = 0,
    val state: String,
    val order: StopOrder? = null,
    @SerialName("time_window") val timeWindow: String = "any",
    val partner: Partner,
    val zone: Ref? = null,
    val lines: List<StopLine> = emptyList(),
    @SerialName("empties_product_id") val emptiesProductId: Long? = null,
    @SerialName("empties_collected") val emptiesCollected: Int = 0,
    @SerialName("payment_mode") val paymentMode: String? = null,
    @SerialName("payment_method") @Serializable(OdooStringSerializer::class) val paymentMethod: String? = null,
    @SerialName("amount_due") val amountDue: Double = 0.0,
    @SerialName("payment_amount") val paymentAmount: Double = 0.0,
    val currency: String = "TRY",
    @SerialName("failure_reason") val failureReason: String? = null,
    @SerialName("failure_note") val failureNote: String = "",
    val note: String = "",
    @SerialName("done_at") @Serializable(OdooStringSerializer::class) val doneAt: String? = null,
) {
    val isPending get() = state == StopState.PENDING
}

@Serializable
data class DeliveredLine(
    @SerialName("product_id") val productId: Long,
    @SerialName("qty_delivered") val qtyDelivered: Double,
)

/** Body of `POST stops/<id>/deliver`; null fields are left out and take the server's defaults. */
@Serializable
data class DeliverRequest(
    val lines: List<DeliveredLine>? = null,
    @SerialName("empties_collected") val emptiesCollected: Int? = null,
    @SerialName("payment_method") val paymentMethod: String? = null,
    @SerialName("payment_amount") val paymentAmount: Double? = null,
    /** PNG, base64. */
    val signature: String? = null,
    /** JPEG, base64. */
    val photo: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val note: String? = null,
)

@Serializable
data class FailRequest(val reason: String, val note: String? = null)

@Serializable
data class PositionRequest(
    val latitude: Double,
    val longitude: Double,
    @SerialName("trip_id") val tripId: Long,
)

@Serializable
data class ReorderRequest(
    @SerialName("trip_id") val tripId: Long,
    @SerialName("stop_ids") val stopIds: List<Long>,
)

@Serializable
data class PushRegisterRequest(
    val token: String,
    val platform: String = "android",
    val app: String = "driver",
    @SerialName("device_name") val deviceName: String,
)

@Serializable
data class PushUnregisterRequest(val token: String)

/** Short-lived SIP login from `POST sip/credentials`; asking again replaces the password. */
@Serializable
data class SipCredentials(
    val username: String,
    val password: String,
    @Serializable(with = OdooStringSerializer::class) val domain: String? = null,
    @SerialName("wss_url") @Serializable(with = OdooStringSerializer::class) val wssUrl: String? = null,
    @SerialName("expires_at") @Serializable(with = OdooStringSerializer::class) val expiresAt: String? = null,
) {
    /** [expiresAt] (`2026-10-18T22:00:00Z`) in epoch millis, or null. */
    val expiresAtMillis: Long?
        get() = expiresAt?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
}

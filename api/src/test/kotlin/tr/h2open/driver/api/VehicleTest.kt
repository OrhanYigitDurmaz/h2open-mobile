package tr.h2open.driver.api

import org.junit.Assert.assertEquals
import org.junit.Test

class VehicleTest {
    @Test
    fun `plate is not repeated when the name already has it`() {
        assertEquals("34 H2O 001: Ford/Transit", Vehicle(1, "34 H2O 001: Ford/Transit", "34 H2O 001").label)
    }

    @Test
    fun `plate is added when the name lacks it`() {
        assertEquals("34 H2O 001 · Van 1", Vehicle(1, "Van 1", "34 H2O 001").label)
        assertEquals("Van 1", Vehicle(1, "Van 1", null).label)
    }
}

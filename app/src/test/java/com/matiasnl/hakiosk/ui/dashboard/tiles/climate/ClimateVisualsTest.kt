package com.matiasnl.hakiosk.ui.dashboard.tiles.climate

import com.matiasnl.hakiosk.data.ha.domain.HvacAction
import com.matiasnl.hakiosk.data.ha.domain.HvacMode
import com.matiasnl.hakiosk.ui.dashboard.tiles.TileSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class ClimateVisualsTest {
    private val rioplatense = Locale.forLanguageTag("es-AR")

    @Test
    fun `temperatures keep at most one decimal in the locale's format`() {
        assertEquals("24,5°", formatTemperature(24.5, rioplatense))
        assertEquals("22°", formatTemperature(22.0, rioplatense))
        assertEquals("21,3°", formatTemperature(21.26, rioplatense))
        assertEquals("-3°", formatTemperature(-3.0, rioplatense))
    }

    @Test
    fun `the target reads as a single setpoint or a range`() {
        assertEquals("22°", climateTargetText(TileSummary.Climate(HvacMode.COOL, targetTemperature = 22.0), rioplatense))
        assertEquals(
            "20°–24,5°",
            climateTargetText(TileSummary.Climate(HvacMode.HEAT_COOL, targetTemperatureLow = 20.0, targetTemperatureHigh = 24.5), rioplatense),
        )
        assertNull(climateTargetText(TileSummary.Climate(HvacMode.FAN_ONLY), rioplatense))
    }

    @Test
    fun `off and unknown tiles have no tint`() {
        assertNull(TileSummary.Climate(HvacMode.OFF, HvacAction.OFF).tint())
        assertNull(TileSummary.Climate(null).tint())
    }

    @Test
    fun `the action picks the tint when running, the mode otherwise`() {
        val coolIdle = TileSummary.Climate(HvacMode.COOL, HvacAction.IDLE)
        val coolRunning = TileSummary.Climate(HvacMode.COOL, HvacAction.COOLING)
        val autoHeating = TileSummary.Climate(HvacMode.AUTO, HvacAction.HEATING)
        val heat = TileSummary.Climate(HvacMode.HEAT)

        assertNotNull(coolIdle.tint())
        assertEquals(coolIdle.tint(), coolRunning.tint())
        assertEquals(heat.tint(), autoHeating.tint())
        assertNotEquals(coolIdle.tint(), heat.tint())
    }

    @Test
    fun `idle devices get a faint tint, running or silent ones a strong one`() {
        assertFalse(TileSummary.Climate(HvacMode.COOL, HvacAction.IDLE).isEmphasized)
        assertTrue(TileSummary.Climate(HvacMode.COOL, HvacAction.COOLING).isEmphasized)
        assertTrue(TileSummary.Climate(HvacMode.COOL, null).isEmphasized)
    }

    @Test
    fun `integration-specific mode values are prettified`() {
        assertEquals("High speed", prettifyModeValue("high_speed", rioplatense))
    }
}

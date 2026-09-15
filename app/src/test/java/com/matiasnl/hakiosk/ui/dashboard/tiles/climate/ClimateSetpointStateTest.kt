package com.matiasnl.hakiosk.ui.dashboard.tiles.climate

import com.matiasnl.hakiosk.data.ha.domain.HvacMode
import com.matiasnl.hakiosk.ui.dashboard.tiles.TileSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ClimateSetpointStateTest {
    private val single = TileSummary.Climate(
        HvacMode.COOL,
        targetTemperature = 22.0,
        minTemperature = 16.0,
        maxTemperature = 30.0,
        temperatureStep = 1.0,
    )

    private val range = TileSummary.Climate(
        HvacMode.HEAT_COOL,
        targetTemperatureLow = 20.0,
        targetTemperatureHigh = 21.0,
    )

    private fun TestScope.state(summary: TileSummary.Climate, commits: MutableList<ClimateSetpoint>): ClimateSetpointState {
        val scope = CoroutineScope(Job(backgroundScope.coroutineContext[Job]) + UnconfinedTestDispatcher(testScheduler))
        return ClimateSetpointState(scope, summary = { summary }, onCommit = { commits += it })
    }

    @Test
    fun `taps show at once and commit one setpoint after the delay`() = runTest {
        val commits = mutableListOf<ClimateSetpoint>()
        val state = state(single, commits)

        state.step(SetpointEnd.TARGET, 1)
        state.step(SetpointEnd.TARGET, 1)

        assertEquals(24.0, state.targetTemperature!!, 0.0)
        assertTrue(commits.isEmpty())

        advanceTimeBy(SETPOINT_COMMIT_DELAY_MILLIS + 1)

        assertEquals(listOf<ClimateSetpoint>(ClimateSetpoint.Single(24.0)), commits)
    }

    @Test
    fun `flush commits a waiting tap right away and only once`() = runTest {
        val commits = mutableListOf<ClimateSetpoint>()
        val state = state(single, commits)

        state.step(SetpointEnd.TARGET, -1)
        state.flush()
        advanceTimeBy(SETPOINT_COMMIT_DELAY_MILLIS + 1)
        state.flush()

        assertEquals(listOf<ClimateSetpoint>(ClimateSetpoint.Single(21.0)), commits)
    }

    @Test
    fun `the limits stop the buttons and commit nothing`() = runTest {
        val commits = mutableListOf<ClimateSetpoint>()
        val state = state(single.copy(targetTemperature = 30.0), commits)

        assertFalse(state.canStep(SetpointEnd.TARGET, 1))
        state.step(SetpointEnd.TARGET, 1)
        state.flush()

        assertTrue(commits.isEmpty())
    }

    @Test
    fun `a range keeps low at or below high and commits both ends`() = runTest {
        val commits = mutableListOf<ClimateSetpoint>()
        val state = state(range, commits)

        state.step(SetpointEnd.LOW, 1)
        state.step(SetpointEnd.LOW, 1)
        assertFalse(state.canStep(SetpointEnd.LOW, 1))
        state.step(SetpointEnd.HIGH, 1)
        state.flush()

        assertEquals(listOf<ClimateSetpoint>(ClimateSetpoint.Range(21.0, 21.5)), commits)
    }

    @Test
    fun `a range tile has no single setpoint to move`() = runTest {
        val state = state(range, mutableListOf())

        assertFalse(state.canStep(SetpointEnd.TARGET, 1))
    }
}

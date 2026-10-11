package com.example.parkinson.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** SYNTHETIC paired events for unit tests only. */
class SensorTimeSynchronizerTest {

    private val sync = SensorTimeSynchronizer()

    @Test
    fun knownOffsetIsRecoveredWithZeroUncertaintyWhenPairsAgree() {
        // Sensor events every 100 ms; camera sees the same events 250 ms later.
        val sensor = (0 until 20).map { it * 100_000_000L }
        val camera = sensor.map { it / 1e6 + 250.0 }
        val result = sync.estimate(sensor, camera)
        val offset = (result as SensorTimeSynchronizer.Result.Mapped).offset
        assertEquals(250.0, offset.offsetMs, 1e-9)
        assertEquals(0.0, offset.uncertaintyMs, 1e-9)
        assertEquals(20, offset.pairCount)
        assertEquals(1_250.0, sync.toCameraMs(offset, 1_000_000_000L), 1e-9)
    }

    @Test
    fun jitteredPairsGiveAnOffsetWithStatedUncertainty() {
        val sensor = (0 until 40).map { it * 100_000_000L }
        // Camera latency jitters between 240 and 260 ms.
        val camera = sensor.mapIndexed { i, ns -> ns / 1e6 + if (i % 2 == 0) 240.0 else 260.0 }
        val offset = (sync.estimate(sensor, camera) as SensorTimeSynchronizer.Result.Mapped).offset
        assertEquals(250.0, offset.offsetMs, 1.0)
        assertTrue(offset.uncertaintyMs in 5.0..15.0)
    }

    @Test
    fun largeUncertaintyMakesTheMappingUnavailable() {
        val sensor = (0 until 40).map { it * 100_000_000L }
        // Latency varies from 0 to 400 ms: the offset cannot be trusted.
        val camera = sensor.mapIndexed { i, ns -> ns / 1e6 + (i * 10.0) }
        val result = sync.estimate(sensor, camera)
        assertTrue(result is SensorTimeSynchronizer.Result.Unavailable)
    }

    @Test
    fun tooFewPairsIsUnavailable() {
        val result = sync.estimate(listOf(0L, 100_000_000L), listOf(250.0, 350.0))
        assertTrue(result is SensorTimeSynchronizer.Result.Unavailable)
    }

    @Test
    fun unpairedEventsAreUnavailable() {
        val result = sync.estimate(listOf(0L, 100_000_000L, 200_000_000L, 300_000_000L, 400_000_000L), listOf(1.0))
        assertTrue(result is SensorTimeSynchronizer.Result.Unavailable)
    }
}

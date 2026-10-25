package com.example.adpfboost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SustainedRunTrackerTest {
    @Test
    fun computesPercentilesAndMissedIntervalsAcrossRawFrames() {
        val tracker = SustainedRunTracker()
        tracker.start(0L)
        for (second in 1L..20L) {
            val interval = if (second <= 10L) 10f else 20f
            tracker.record(
                SustainedRunTracker.Sample(
                    elapsedSeconds = second,
                    fps = if (second <= 10L) 100f else 50f,
                    frameIntervalsMs = List(10) { interval },
                    thermalHeadroom = null,
                    thermalStatus = 1
                )
            )
        }

        val summary = tracker.summary(20_000L, 60)
        assertNotNull(summary)
        assertTrue(summary!!.comparisonReady)
        assertEquals(100f, summary.baselineFps!!, 0.001f)
        assertEquals(50f, summary.recentFps!!, 0.001f)
        assertEquals(50f, summary.fpsDropPct!!, 0.001f)
        assertEquals(0f, summary.baselineMissedPct!!, 0.001f)
        assertEquals(100f, summary.recentMissedPct!!, 0.001f)
        assertEquals(10f, summary.baselineP95Ms!!, 0.001f)
        assertEquals(20f, summary.recentP95Ms!!, 0.001f)
    }

    @Test
    fun comparisonIsNotReadyBeforeTwentySeconds() {
        val tracker = SustainedRunTracker()
        tracker.start(0L)
        for (second in 1L..15L) {
            tracker.record(SustainedRunTracker.Sample(second, 60f, List(5) { 16f }, null, null))
        }
        val summary = tracker.summary(15_000L, 60)
        assertNotNull(summary)
        assertFalse(summary!!.comparisonReady)
        assertTrue(summary.assessment.contains("at least 20 seconds"))
    }

    @Test
    fun targetBudgetChangesMissedFrameRate() {
        val tracker = SustainedRunTracker()
        tracker.start(0L)
        for (second in 1L..20L) {
            tracker.record(SustainedRunTracker.Sample(second, 60f, List(10) { 12f }, null, null))
        }
        val at60 = tracker.summary(20_000L, 60)!!
        val at120 = tracker.summary(20_000L, 120)!!
        assertEquals(0f, at60.recentMissedPct!!, 0.001f)
        assertEquals(100f, at120.recentMissedPct!!, 0.001f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsupportedTarget() {
        SustainedRunTracker().summary(0L, 75)
    }
}

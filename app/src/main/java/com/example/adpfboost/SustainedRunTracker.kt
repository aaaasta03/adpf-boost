package com.example.adpfboost

import kotlin.math.ceil
import kotlin.math.max

/**
 * Retains timestamped per-second frame intervals and reports descriptive statistics.
 * These are this app's own Choreographer callback intervals, not another app's render data.
 */
class SustainedRunTracker {
    data class Sample(
        val elapsedSeconds: Long,
        val fps: Float,
        val frameIntervalsMs: List<Float>,
        val thermalHeadroom: Float?,
        val thermalStatus: Int?
    ) {
        val observedFrames: Int get() = frameIntervalsMs.count { it.isFinite() && it > 0f }
    }

    data class Summary(
        val elapsedSeconds: Long,
        val sampleCount: Int,
        val baselineFps: Float?,
        val recentFps: Float?,
        val fpsDropPct: Float?,
        val baselineMissedPct: Float?,
        val recentMissedPct: Float?,
        val baselineP95Ms: Float?,
        val recentP95Ms: Float?,
        val recentHeadroom: Float?,
        val peakThermalStatus: Int?,
        val comparisonReady: Boolean,
        val assessment: String
    )

    private val samples = ArrayList<Sample>()
    private var startMs: Long? = null
    private var stopMs: Long? = null

    @Synchronized
    fun start(nowMs: Long) {
        samples.clear()
        startMs = nowMs
        stopMs = null
    }

    @Synchronized
    fun record(sample: Sample) {
        if (startMs == null || stopMs != null) return
        if (samples.lastOrNull()?.elapsedSeconds == sample.elapsedSeconds) return
        samples.add(sample.copy(frameIntervalsMs = sample.frameIntervalsMs.toList()))
        // A two-hour session at one sample/second is a generous bound for diagnostics.
        if (samples.size > 7200) samples.removeAt(0)
    }

    @Synchronized
    fun stop(nowMs: Long) {
        val start = startMs ?: return
        if (stopMs == null) stopMs = max(start, nowMs)
    }

    @Synchronized
    fun summary(nowMs: Long, targetFps: Int): Summary? {
        require(targetFps in SUPPORTED_TARGETS) { "Target FPS must be one of $SUPPORTED_TARGETS" }
        val start = startMs ?: return null
        val elapsed = max(0L, ((stopMs ?: nowMs) - start) / 1000L)
        val baseline = samples.filter { it.elapsedSeconds in 1L..10L }
        val recent = samples.filter { it.elapsedSeconds > elapsed - 10L && it.elapsedSeconds <= elapsed }
        val ready = elapsed >= 20L && baseline.size >= 5 && recent.size >= 5

        val baseFps = baseline.averageFinite { it.fps }
        val recentFps = recent.averageFinite { it.fps }
        val frameBudgetMs = 1000f / targetFps
        val baselineIntervals = baseline.flatMap { it.frameIntervalsMs }.filter { it.isFinite() && it > 0f }
        val recentIntervals = recent.flatMap { it.frameIntervalsMs }.filter { it.isFinite() && it > 0f }
        val baseMissed = missedPercentage(baselineIntervals, frameBudgetMs)
        val recentMissed = missedPercentage(recentIntervals, frameBudgetMs)
        val baseP95 = percentile(baselineIntervals, 0.95)
        val recentP95 = percentile(recentIntervals, 0.95)
        val headroom = recent.mapNotNull { it.thermalHeadroom?.takeIf { value -> value.isFinite() && value >= 0f } }
            .averageOrNull()
        val peakStatus = samples.mapNotNull { it.thermalStatus }.maxOrNull()
        val drop = if (baseFps != null && recentFps != null && baseFps > 0f) {
            (baseFps - recentFps) * 100f / baseFps
        } else null

        val assessment = when {
            !ready -> "Run at least 20 seconds and collect 5+ samples in both windows."
            drop != null && drop >= 15f && ((headroom ?: 0f) >= 0.8f || (peakStatus ?: 0) >= 3) ->
                "Possible thermal-associated decline (${drop.toInt()}% FPS drop). Correlation is not proof of cause."
            drop != null && drop >= 15f ->
                "Observed callback pacing declined ${drop.toInt()}%; thermal readings do not establish the cause."
            (recentMissed ?: 0f) >= 10f ->
                "Many frame intervals exceeded the ${targetFps} FPS budget; repeat under the same display and workload conditions."
            else -> "No major decline detected in this app's callback pacing. Real-game performance may differ."
        }

        return Summary(
            elapsedSeconds = elapsed,
            sampleCount = samples.size,
            baselineFps = baseFps,
            recentFps = recentFps,
            fpsDropPct = drop,
            baselineMissedPct = baseMissed,
            recentMissedPct = recentMissed,
            baselineP95Ms = baseP95,
            recentP95Ms = recentP95,
            recentHeadroom = headroom,
            peakThermalStatus = peakStatus,
            comparisonReady = ready,
            assessment = assessment
        )
    }

    private fun missedPercentage(intervals: List<Float>, frameBudgetMs: Float): Float? {
        if (intervals.isEmpty()) return null
        return intervals.count { it > frameBudgetMs } * 100f / intervals.size
    }

    /** Nearest-rank percentile (ceil(p * n) - 1), calculated across individual intervals. */
    internal fun percentile(values: List<Float>, p: Double): Float? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val index = (ceil(p * sorted.size).toInt() - 1).coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    private fun List<Sample>.averageFinite(selector: (Sample) -> Float): Float? =
        map(selector).filter { it.isFinite() }.averageOrNull()

    private fun List<Float>.averageOrNull(): Float? =
        if (isEmpty()) null else average().toFloat()

    companion object {
        val SUPPORTED_TARGETS = setOf(60, 90, 120)
    }
}

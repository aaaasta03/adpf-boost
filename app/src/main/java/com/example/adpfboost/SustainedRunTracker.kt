package com.example.adpfboost

import kotlin.math.max

/** Descriptive per-second comparison; it does not infer causation. */
class SustainedRunTracker {
    data class Sample(val elapsedSeconds: Long, val fps: Float, val missedFramePct: Float, val p95FrameMs: Float, val thermalHeadroom: Float?, val thermalStatus: Int?)
    data class Summary(val elapsedSeconds: Long, val sampleCount: Int, val baselineFps: Float?, val recentFps: Float?, val fpsDropPct: Float?, val baselineMissedPct: Float?, val recentMissedPct: Float?, val recentP95Ms: Float?, val recentHeadroom: Float?, val peakThermalStatus: Int?, val comparisonReady: Boolean, val assessment: String)
    private val samples = ArrayList<Sample>()
    private var startMs: Long? = null
    private var stopMs: Long? = null

    @Synchronized fun start(nowMs: Long) { samples.clear(); startMs = nowMs; stopMs = null }
    @Synchronized fun record(sample: Sample) {
        if (startMs == null || stopMs != null) return
        if (samples.lastOrNull()?.elapsedSeconds == sample.elapsedSeconds) return
        samples.add(sample)
        if (samples.size > 7200) samples.removeAt(0)
    }
    @Synchronized fun stop(nowMs: Long) { val start = startMs ?: return; if (stopMs == null) stopMs = max(start, nowMs) }

    @Synchronized fun summary(nowMs: Long): Summary? {
        val start = startMs ?: return null
        val elapsed = max(0L, ((stopMs ?: nowMs) - start) / 1000L)
        val baseline = samples.filter { it.elapsedSeconds in 1L..10L }
        val recent = samples.filter { it.elapsedSeconds > elapsed - 10L && it.elapsedSeconds <= elapsed }
        val ready = elapsed >= 20L && baseline.size >= 5 && recent.size >= 5
        val baseFps = baseline.avg { it.fps }; val recentFps = recent.avg { it.fps }
        val baseMiss = baseline.avg { it.missedFramePct }; val recentMiss = recent.avg { it.missedFramePct }
        val recentP95 = recent.avg { it.p95FrameMs }
        val headroom = recent.mapNotNull { it.thermalHeadroom?.takeIf { v -> v.isFinite() } }.averageOrNull()
        val peakStatus = samples.mapNotNull { it.thermalStatus }.maxOrNull()
        val drop = if (baseFps != null && recentFps != null && baseFps > 0f) (baseFps - recentFps) * 100f / baseFps else null
        val assessment = when {
            !ready -> "Run at least 20 seconds and collect 5+ samples in both windows."
            drop != null && drop >= 15f && ((headroom ?: 0f) >= 0.8f || (peakStatus ?: 0) >= 3) -> "Possible thermal-associated decline (${drop.toInt()}% FPS drop). Correlation is not proof of cause."
            drop != null && drop >= 15f -> "Sustained loop performance declined ${drop.toInt()}%; thermal readings do not establish the cause."
            (recentMiss ?: 0f) >= 10f -> "Many frame budgets missed in the recent window; lower the test target and retest."
            else -> "No major decline detected in this synthetic loop. Real-game performance may differ."
        }
        return Summary(elapsed, samples.size, baseFps, recentFps, drop, baseMiss, recentMiss, recentP95, headroom, peakStatus, ready, assessment)
    }
    private fun List<Sample>.avg(selector: (Sample) -> Float): Float? = map(selector).filter { it.isFinite() }.averageOrNull()
    private fun List<Float>.averageOrNull(): Float? = if (isEmpty()) null else average().toFloat()
}

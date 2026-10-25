package com.example.adpfboost

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import kotlin.math.max

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val tracker = SustainedRunTracker()
    private lateinit var output: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private var running = false
    private var startedAt = 0L
    private var lastTick = 0L
    private val frameTimes = ArrayDeque<Float>()
    private val targetFrameMs = 1000f / 60f

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.elapsedRealtime()
            val delta = max(1L, now - lastTick)
            lastTick = now
            frameTimes.addLast(delta.toFloat())
            while (frameTimes.size > 120) frameTimes.removeFirst()
            val sorted = frameTimes.toList().sorted()
            val p95 = sorted[((sorted.size - 1) * 0.95f).toInt()]
            val thermal = readThermal()
            tracker.record(SustainedRunTracker.Sample((now - startedAt) / 1000L, 1000f / delta.toFloat(), if (delta > targetFrameMs) 100f else 0f, p95, thermal.first, thermal.second))
            render("RUNNING", now)
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24); setBackgroundColor(0xFF101820.toInt()) }
        root.addView(TextView(this).apply { text = "ADPF Performance Lab\nSustained diagnostics"; textSize = 24f; setTextColor(0xFFFFFFFF.toInt()) })
        output = TextView(this).apply { textSize = 15f; setTextColor(0xFFE4EDF5.toInt()); setPadding(0, 20, 0, 20) }
        startButton = Button(this).apply { text = "Start 60 FPS diagnostic"; setOnClickListener { startRun() } }
        stopButton = Button(this).apply { text = "Stop and summarize"; isEnabled = false; setOnClickListener { stopRun() } }
        root.addView(output); root.addView(startButton); root.addView(stopButton)
        setContentView(ScrollView(this).apply { addView(root) })
        render("READY", SystemClock.elapsedRealtime())
    }

    private fun startRun() {
        if (running) return
        running = true; startedAt = SystemClock.elapsedRealtime(); lastTick = startedAt
        frameTimes.clear(); tracker.start(startedAt); startButton.isEnabled = false; stopButton.isEnabled = true
        handler.post(tick)
    }
    private fun stopRun() {
        if (!running) return
        running = false; handler.removeCallbacks(tick); val now = SystemClock.elapsedRealtime(); tracker.stop(now)
        startButton.isEnabled = true; stopButton.isEnabled = false; render("STOPPED", now)
    }

    private fun render(state: String, now: Long) {
        val summary = tracker.summary(now); val thermal = readThermal()
        val elapsed = if (running) (now - startedAt) / 1000L else summary?.elapsedSeconds ?: 0L
        val b = StringBuilder()
        b.append("State: ").append(state).append("\nElapsed: ").append(elapsed).append(" s\n")
        b.append(String.format(Locale.US, "Target budget: %.2f ms (60 FPS)\n", targetFrameMs))
        b.append("Samples: ").append(summary?.sampleCount ?: 0).append("\n")
        b.append("Thermal status: ").append(thermal.second?.let(::thermalName) ?: "unavailable").append("\n")
        b.append("Thermal headroom: ").append(thermal.first?.let { String.format(Locale.US, "%.2f", it) } ?: "unavailable").append("\n\n")
        if (summary != null) {
            b.append("Baseline FPS (first 10 s): ").append(fmt(summary.baselineFps)).append("\n")
            b.append("Recent FPS (last 10 s): ").append(fmt(summary.recentFps)).append("\n")
            b.append("FPS drop: ").append(summary.fpsDropPct?.let { String.format(Locale.US, "%.1f%%", it) } ?: "n/a").append("\n")
            b.append("Baseline missed budgets: ").append(fmtPct(summary.baselineMissedPct)).append("\n")
            b.append("Recent missed budgets: ").append(fmtPct(summary.recentMissedPct)).append("\n")
            b.append("Recent p95 frame time: ").append(summary.recentP95Ms?.let { String.format(Locale.US, "%.2f ms", it) } ?: "n/a").append("\n")
            b.append("Peak thermal status: ").append(summary.peakThermalStatus?.let(::thermalName) ?: "unavailable").append("\n\n")
            b.append("Assessment: ").append(summary.assessment).append("\n")
        }
        b.append("\nCAVEAT: scheduled synthetic loop, not a game's actual render FPS. Timing includes Android scheduling delays.")
        output.text = b.toString()
    }

    private fun readThermal(): Pair<Float?, Int?> {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val status = if (Build.VERSION.SDK_INT >= 29) runCatching { pm.currentThermalStatus }.getOrNull() else null
        val headroom = if (Build.VERSION.SDK_INT >= 30) runCatching { pm.getThermalHeadroom(10) }.getOrNull()?.takeIf { it.isFinite() && it >= 0f } else null
        return headroom to status
    }
    private fun thermalName(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "none"; PowerManager.THERMAL_STATUS_LIGHT -> "light"
        PowerManager.THERMAL_STATUS_MODERATE -> "moderate"; PowerManager.THERMAL_STATUS_SEVERE -> "severe"
        PowerManager.THERMAL_STATUS_CRITICAL -> "critical"; PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"; else -> "unknown($status)"
    }
    private fun fmt(v: Float?): String = v?.let { String.format(Locale.US, "%.1f", it) } ?: "n/a"
    private fun fmtPct(v: Float?): String = v?.let { String.format(Locale.US, "%.1f%%", it) } ?: "n/a"
    override fun onDestroy() { running = false; handler.removeCallbacks(tick); super.onDestroy() }
}

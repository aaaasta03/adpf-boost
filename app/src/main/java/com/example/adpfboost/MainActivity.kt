package com.example.adpfboost

import android.app.Activity
import android.app.GameManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PerformanceHintManager
import android.os.PowerManager
import android.os.SystemClock
import android.view.Choreographer
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.RadioButton
import java.util.Locale

/**
 * Measures this app's own Choreographer callback intervals, not another game's renderer.
 * Target FPS is an analysis budget; it does not cap or boost the app's frame rate.
 */
class MainActivity : Activity(), Choreographer.FrameCallback {
    private val handler = Handler(Looper.getMainLooper())
    private val tracker = SustainedRunTracker()
    private val powerManager: PowerManager by lazy {
        getSystemService(Context.POWER_SERVICE) as PowerManager
    }

    private lateinit var output: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var targetGroup: RadioGroup
    private lateinit var sustainedButton: Button

    private var running = false
    private var startedAt = 0L
    private var sampleWindowStart = 0L
    private var lastFrameNanos = 0L
    private var frameCount = 0
    private val windowFrameTimesMs = ArrayList<Float>()
    private var targetFps = 60
    private val targetFrameMs get() = 1000f / targetFps

    private var currentThermalStatus: Int? = null
    private var currentThermalHeadroom: Float? = null
    private var thermalListenerRegistered = false
    private var sustainedModeEnabled = false

    private val thermalStatusListener = PowerManager.OnThermalStatusChangedListener { status ->
        currentThermalStatus = status
        if (this::output.isInitialized) {
            render(if (running) "RUNNING" else "READY", SystemClock.elapsedRealtime())
        }
    }

    private val sampleTick = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.elapsedRealtime()
            val windowMs = (now - sampleWindowStart).coerceAtLeast(1L).toFloat()
            val fps = frameCount * 1000f / windowMs

            // Headroom forecasts are sampled only once per second; some devices return NaN
            // when polled too frequently or when forecasting is unsupported.
            refreshThermalHeadroom()
            tracker.record(SustainedRunTracker.Sample(
                elapsedSeconds = (now - startedAt) / 1000L,
                fps = fps,
                frameIntervalsMs = windowFrameTimesMs.toList(),
                thermalHeadroom = currentThermalHeadroom,
                thermalStatus = currentThermalStatus
            ))
            frameCount = 0
            windowFrameTimesMs.clear()
            sampleWindowStart = now
            render("RUNNING", now)
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(0xFF101820.toInt())
        }
        root.addView(TextView(this).apply {
            text = "ADPF Performance Lab\nPlatform diagnostics v1.2"
            textSize = 24f
            setTextColor(0xFFFFFFFF.toInt())
        })
        root.addView(TextView(this).apply {
            text = "Frame-budget target (analysis only):"
            textSize = 15f
            setTextColor(0xFFE4EDF5.toInt())
            setPadding(0, 16, 0, 4)
        })
        targetGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            listOf(60, 90, 120).forEach { fps ->
                addView(RadioButton(this@MainActivity).apply {
                    id = fps
                    text = "$fps FPS"
                    isChecked = fps == targetFps
                    setTextColor(0xFFE4EDF5.toInt())
                    setOnClickListener {
                        if (!running) {
                            targetFps = fps
                            render("READY", SystemClock.elapsedRealtime())
                        }
                    }
                })
            }
        }
        root.addView(targetGroup)

        sustainedButton = Button(this).apply {
            text = sustainedButtonLabel()
            setOnClickListener { setSustainedMode(!sustainedModeEnabled) }
        }
        root.addView(sustainedButton)

        output = TextView(this).apply {
            textSize = 15f
            setTextColor(0xFFE4EDF5.toInt())
            setPadding(0, 20, 0, 20)
        }
        startButton = Button(this).apply {
            text = "Start frame-pacing test"
            setOnClickListener { startRun() }
        }
        stopButton = Button(this).apply {
            text = "Stop and summarize"
            isEnabled = false
            setOnClickListener { stopRun() }
        }
        root.addView(output)
        root.addView(startButton)
        root.addView(stopButton)
        setContentView(ScrollView(this).apply { addView(root) })
        render("READY", SystemClock.elapsedRealtime())
    }

    override fun onResume() {
        super.onResume()
        registerThermalListener()
        if (sustainedModeEnabled && Build.VERSION.SDK_INT >= 24) {
            window.setSustainedPerformanceMode(true)
        }
        if (this::output.isInitialized) render(if (running) "RUNNING" else "READY", SystemClock.elapsedRealtime())
    }

    override fun onPause() {
        // Avoid counting backgrounded callbacks as visible frame pacing.
        if (running) stopRun()
        unregisterThermalListener()
        super.onPause()
    }

    private fun registerThermalListener() {
        if (Build.VERSION.SDK_INT < 29 || thermalListenerRegistered) return
        currentThermalStatus = runCatching { powerManager.currentThermalStatus }.getOrNull()
        runCatching {
            powerManager.addThermalStatusListener(mainExecutor, thermalStatusListener)
            thermalListenerRegistered = true
        }
    }

    private fun unregisterThermalListener() {
        if (Build.VERSION.SDK_INT < 29 || !thermalListenerRegistered) return
        runCatching { powerManager.removeThermalStatusListener(thermalStatusListener) }
        thermalListenerRegistered = false
    }

    private fun refreshThermalHeadroom() {
        currentThermalHeadroom = if (Build.VERSION.SDK_INT >= 30) {
            runCatching { powerManager.getThermalHeadroom(10) }
                .getOrNull()
                ?.takeIf { it.isFinite() && it >= 0f }
        } else {
            null
        }
    }

    private fun setSustainedMode(enabled: Boolean) {
        if (running) return
        if (Build.VERSION.SDK_INT >= 24) {
            // This request applies to this Activity's window only; OEM effect may vary.
            window.setSustainedPerformanceMode(enabled)
            sustainedModeEnabled = enabled
            sustainedButton.text = sustainedButtonLabel()
            render("READY", SystemClock.elapsedRealtime())
        }
    }

    private fun sustainedButtonLabel(): String =
        if (sustainedModeEnabled) "Disable sustained mode for this app"
        else "Enable sustained mode for this app"

    private fun startRun() {
        if (running) return
        targetFps = when (targetGroup.checkedRadioButtonId) {
            90 -> 90
            120 -> 120
            else -> 60
        }
        running = true
        startedAt = SystemClock.elapsedRealtime()
        sampleWindowStart = startedAt
        lastFrameNanos = 0L
        frameCount = 0
        windowFrameTimesMs.clear()
        tracker.start(startedAt)
        startButton.isEnabled = false
        stopButton.isEnabled = true
        sustainedButton.isEnabled = false
        for (i in 0 until targetGroup.childCount) targetGroup.getChildAt(i).isEnabled = false
        Choreographer.getInstance().postFrameCallback(this)
        handler.postDelayed(sampleTick, 1000L)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        if (lastFrameNanos != 0L) {
            val deltaMs = (frameTimeNanos - lastFrameNanos) / 1_000_000f
            if (deltaMs.isFinite() && deltaMs > 0f) {
                frameCount++
                windowFrameTimesMs.add(deltaMs)
                if (windowFrameTimesMs.size > 3000) windowFrameTimesMs.removeAt(0)
            }
        }
        lastFrameNanos = frameTimeNanos
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun stopRun() {
        if (!running) return
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
        handler.removeCallbacks(sampleTick)
        val now = SystemClock.elapsedRealtime()
        tracker.stop(now)
        startButton.isEnabled = true
        stopButton.isEnabled = false
        sustainedButton.isEnabled = true
        for (i in 0 until targetGroup.childCount) targetGroup.getChildAt(i).isEnabled = true
        render("STOPPED", now)
    }

    private fun performanceHintApiLabel(): String {
        if (Build.VERSION.SDK_INT < 31) return "requires Android 12 / API 31+"
        val manager = getSystemService(PerformanceHintManager::class.java)
        return if (manager != null) {
            "service present; no session (no representative CPU workload)"
        } else {
            "service unavailable on this device"
        }
    }

    private fun gameModeLabel(): String {
        if (Build.VERSION.SDK_INT < 31) return "requires Android 12 / API 31+"
        val manager = getSystemService(GameManager::class.java)
            ?: return "unavailable"
        return when (manager.gameMode) {
            GameManager.GAME_MODE_STANDARD -> "Standard (this app)"
            GameManager.GAME_MODE_PERFORMANCE -> "Performance (this app)"
            GameManager.GAME_MODE_BATTERY -> "Battery (this app)"
            GameManager.GAME_MODE_CUSTOM -> "Custom (this app)"
            GameManager.GAME_MODE_UNSUPPORTED -> "unsupported for this utility"
            else -> "unknown"
        }
    }

    private fun render(state: String, now: Long) {
        val summary = tracker.summary(now, targetFps)
        val elapsed = if (running) (now - startedAt) / 1000L else summary?.elapsedSeconds ?: 0L
        val b = StringBuilder()
        b.append("State: ").append(state).append("\nElapsed: ").append(elapsed).append(" s\n")
        b.append(String.format(Locale.US, "Target budget: %.2f ms (%d FPS)\n", targetFrameMs, targetFps))
        b.append("Samples: ").append(summary?.sampleCount ?: 0).append("\n")
        b.append("Thermal status: ").append(currentThermalStatus?.let(::thermalName) ?: "unavailable").append("\n")
        b.append("Thermal headroom forecast: ").append(currentThermalHeadroom?.let { String.format(Locale.US, "%.2f", it) } ?: "unavailable").append("\n")
        b.append("Sustained mode request: ").append(if (sustainedModeEnabled) "ON (this app window only)" else "OFF").append("\n")
        b.append("Performance Hint API: ").append(performanceHintApiLabel()).append("\n")
        b.append("Game Mode (this app): ").append(gameModeLabel()).append("\n\n")
        if (summary != null) {
            b.append("Baseline FPS (first 10 s): ").append(fmt(summary.baselineFps)).append("\n")
            b.append("Recent FPS (last 10 s): ").append(fmt(summary.recentFps)).append("\n")
            b.append("FPS drop: ").append(summary.fpsDropPct?.let { String.format(Locale.US, "%.1f%%", it) } ?: "n/a").append("\n")
            b.append("Baseline over-budget intervals: ").append(fmtPct(summary.baselineMissedPct)).append("\n")
            b.append("Recent over-budget intervals: ").append(fmtPct(summary.recentMissedPct)).append("\n")
            b.append("Baseline p95 frame interval: ").append(fmtMs(summary.baselineP95Ms)).append("\n")
            b.append("Recent p95 frame interval: ").append(fmtMs(summary.recentP95Ms)).append("\n")
            b.append("Recent average thermal headroom: ").append(fmt(summary.recentHeadroom)).append("\n")
            b.append("Peak thermal status: ").append(summary.peakThermalStatus?.let(::thermalName) ?: "unavailable").append("\n\n")
            b.append("Assessment: ").append(summary.assessment).append("\n")
            if (!summary.comparisonReady) b.append("Comparison becomes ready after 20 s with enough samples in both windows.\n")
        }
        b.append("\nNOTE: target FPS is only an analysis threshold; it does not cap frame rate.")
        b.append("\nCAVEAT: this measures this app's UI callback pacing, not a game's FPS. Game Mode is reported for this app only.")
        output.text = b.toString()
    }

    private fun thermalName(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "none"
        PowerManager.THERMAL_STATUS_LIGHT -> "light"
        PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
        PowerManager.THERMAL_STATUS_SEVERE -> "severe"
        PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
        else -> "unknown($status)"
    }

    private fun fmt(v: Float?): String = v?.let { String.format(Locale.US, "%.2f", it) } ?: "n/a"
    private fun fmtPct(v: Float?): String = v?.let { String.format(Locale.US, "%.1f%%", it) } ?: "n/a"
    private fun fmtMs(v: Float?): String = v?.let { String.format(Locale.US, "%.2f ms", it) } ?: "n/a"

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(sampleTick)
        Choreographer.getInstance().removeFrameCallback(this)
        unregisterThermalListener()
        if (sustainedModeEnabled && Build.VERSION.SDK_INT >= 24) {
            window.setSustainedPerformanceMode(false)
            sustainedModeEnabled = false
        }
        super.onDestroy()
    }
}

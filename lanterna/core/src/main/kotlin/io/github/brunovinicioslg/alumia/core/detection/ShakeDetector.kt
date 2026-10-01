package io.github.brunovinicioslg.alumia.core.detection

import kotlin.math.sqrt

/**
 * Detects a shake gesture from raw accelerometer samples.
 *
 * Pure and deterministic: time comes only from the sample timestamps, so batched or delayed sensor
 * delivery gives the same result, and recorded traces can be replayed in tests.
 *
 * Pipeline per sample:
 * 1. A low-pass filter tracks gravity; subtracting it gives linear acceleration.
 * 2. A stroke starts when linear acceleration exceeds the sensitivity threshold and ends when it
 *    drops below half of it (hysteresis). The stroke is represented by its peak.
 * 3. Strokes chain while each one points roughly opposite to the previous one, arrives within the
 *    configured gap window, keeps the rhythm of the previous gap and has a similar strength. A hand
 *    shakes evenly; steps, impacts and bounces in a pocket mix short and long gaps and strong and weak
 *    peaks. Reaching the required count fires, then a cooldown starts.
 *
 * Not thread-safe: feed samples from a single thread.
 */
class ShakeDetector(config: ShakeConfig = ShakeConfig()) {

    var config: ShakeConfig = config
        set(value) {
            field = value
            resetChain()
        }

    private var lastTimestamp = NO_TIME
    private var gravityX = 0f
    private var gravityY = 0f
    private var gravityZ = 0f

    private var inStroke = false
    private var peakMagnitudeSq = 0f
    private var peakX = 0f
    private var peakY = 0f
    private var peakZ = 0f
    private var peakTime = NO_TIME

    private var chainLength = 0
    private var lastStrokeTime = NO_TIME
    private var lastStrokeX = 0f
    private var lastStrokeY = 0f
    private var lastStrokeZ = 0f
    private var lastStrokeMagnitude = 0f
    private var lastGap = NO_TIME
    private var cooldownUntil = NO_TIME

    /**
     * Feeds one accelerometer sample (m/s², device axes) taken at [timestampNanos].
     *
     * @return true exactly when this sample completes a shake gesture.
     */
    fun onSample(timestampNanos: Long, x: Float, y: Float, z: Float): Boolean {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return false

        if (lastTimestamp == NO_TIME) {
            startFrom(timestampNanos, x, y, z)
            return false
        }
        val dt = timestampNanos - lastTimestamp
        if (dt <= 0) return false // duplicate or out-of-order sample
        if (dt > MAX_SAMPLE_GAP_NANOS) {
            // Sensor was paused (re-registration, suspend): the gravity estimate is stale.
            startFrom(timestampNanos, x, y, z)
            return false
        }
        lastTimestamp = timestampNanos

        val alpha = dt.toFloat() / (config.gravityTimeConstantNanos + dt).toFloat()
        gravityX += alpha * (x - gravityX)
        gravityY += alpha * (y - gravityY)
        gravityZ += alpha * (z - gravityZ)
        val linX = x - gravityX
        val linY = y - gravityY
        val linZ = z - gravityZ
        val magnitudeSq = linX * linX + linY * linY + linZ * linZ

        val threshold = config.sensitivity.thresholdMs2
        if (inStroke) {
            val release = threshold * RELEASE_RATIO
            // At low sample rates a fast swing can jump across the release band between two
            // samples, so a direction reversal also ends the stroke.
            val reversed = linX * peakX + linY * peakY + linZ * peakZ < 0f
            if (!reversed && magnitudeSq >= release * release) {
                if (magnitudeSq > peakMagnitudeSq) recordPeak(timestampNanos, magnitudeSq, linX, linY, linZ)
                return false
            }
            inStroke = false
            if (onStrokeCompleted()) return true
        }
        if (magnitudeSq >= threshold * threshold) {
            inStroke = true
            recordPeak(timestampNanos, magnitudeSq, linX, linY, linZ)
        }
        return false
    }

    /** Forgets all state, as if no sample had been seen. */
    fun reset() {
        lastTimestamp = NO_TIME
        cooldownUntil = NO_TIME
        resetChain()
    }

    private fun startFrom(timestampNanos: Long, x: Float, y: Float, z: Float) {
        lastTimestamp = timestampNanos
        gravityX = x
        gravityY = y
        gravityZ = z
        resetChain()
    }

    private fun resetChain() {
        inStroke = false
        peakMagnitudeSq = 0f
        peakTime = NO_TIME
        chainLength = 0
        lastStrokeTime = NO_TIME
        lastGap = NO_TIME
    }

    private fun recordPeak(time: Long, magnitudeSq: Float, x: Float, y: Float, z: Float) {
        peakTime = time
        peakMagnitudeSq = magnitudeSq
        peakX = x
        peakY = y
        peakZ = z
    }

    private fun onStrokeCompleted(): Boolean {
        val time = peakTime
        val magnitude = sqrt(peakMagnitudeSq)
        peakMagnitudeSq = 0f
        if (cooldownUntil != NO_TIME && time < cooldownUntil) return false

        val dirX = peakX / magnitude
        val dirY = peakY / magnitude
        val dirZ = peakZ / magnitude

        if (chainLength > 0) {
            val gap = time - lastStrokeTime
            if (gap < config.minStrokeGapNanos) {
                return false // ringing of the previous stroke, not a new movement
            }
            val dot = dirX * lastStrokeX + dirY * lastStrokeY + dirZ * lastStrokeZ
            val continuesChain = gap <= config.maxStrokeGapNanos &&
                dot <= OPPOSITE_DIRECTION_MAX_DOT &&
                withinRatio(magnitude, lastStrokeMagnitude, MAX_STRENGTH_RATIO) &&
                (lastGap == NO_TIME || withinRatio(gap.toFloat(), lastGap.toFloat(), MAX_RHYTHM_RATIO))
            if (continuesChain) {
                chainLength++
                lastGap = gap
            } else {
                chainLength = 1
                lastGap = NO_TIME
            }
        } else {
            chainLength = 1
            lastGap = NO_TIME
        }
        lastStrokeTime = time
        lastStrokeX = dirX
        lastStrokeY = dirY
        lastStrokeZ = dirZ
        lastStrokeMagnitude = magnitude

        if (chainLength >= config.requiredStrokes) {
            chainLength = 0
            lastStrokeTime = NO_TIME
            lastGap = NO_TIME
            cooldownUntil = time + config.cooldownNanos
            return true
        }
        return false
    }

    private companion object {
        const val NO_TIME = Long.MIN_VALUE
        const val RELEASE_RATIO = 0.5f

        /** Cosine of the angle between strokes; -0.5 means the directions differ by at least 120°. */
        const val OPPOSITE_DIRECTION_MAX_DOT = -0.5f
        const val MAX_SAMPLE_GAP_NANOS = 500 * ShakeConfig.NANOS_PER_MILLI

        /** A stroke may be at most this many times stronger (or weaker) than the previous one. */
        const val MAX_STRENGTH_RATIO = 2.2f

        /** A gap between strokes may be at most this many times longer (or shorter) than the previous one. */
        const val MAX_RHYTHM_RATIO = 1.8f

        fun withinRatio(a: Float, b: Float, maxRatio: Float): Boolean = a <= b * maxRatio && b <= a * maxRatio
    }
}

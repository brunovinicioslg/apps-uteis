package io.github.brunovinicioslg.medeai.measure

import io.github.brunovinicioslg.medeai.geometry.Vec3

enum class MeasureMode {
    /** Two points; a third starts a new measurement. */
    DISTANCE,

    /** Any number of points in a row: each stretch and the total. */
    PATH,

    /** A closed outline: area and perimeter. */
    AREA,
}

/**
 * The points of the measurement being built, in placement order. [T] is whatever the platform
 * keeps per point (an AR anchor); the state never looks inside it. Every change returns the new
 * state and the points it dropped, so the caller can release them.
 */
data class MeasureState<T>(val mode: MeasureMode, val points: List<T> = emptyList(), val closed: Boolean = false) {

    data class Change<T>(val state: MeasureState<T>, val dropped: List<T> = emptyList())

    val canClose: Boolean get() = mode == MeasureMode.AREA && !closed && points.size >= MIN_AREA_POINTS
    val canUndo: Boolean get() = points.isNotEmpty()

    /** Whether the crosshair continues this measurement (a live stretch to it) or would start a new one. */
    val continues: Boolean
        get() = when (mode) {
            MeasureMode.DISTANCE -> points.size == 1
            MeasureMode.PATH -> points.isNotEmpty()
            MeasureMode.AREA -> points.isNotEmpty() && !closed
        }

    fun add(point: T): Change<T> {
        val full = (mode == MeasureMode.DISTANCE && points.size >= 2) || (mode == MeasureMode.AREA && closed)
        return if (full) Change(MeasureState(mode, listOf(point)), points) else Change(copy(points = points + point))
    }

    /** Closes the outline (area mode, 3 points or more); the last point joins the first. */
    fun close(): Change<T> = if (canClose) Change(copy(closed = true)) else Change(this)

    /** Reopens a closed outline, else removes the last point. */
    fun undo(): Change<T> = when {
        closed -> Change(copy(closed = false))
        points.isEmpty() -> Change(this)
        else -> Change(copy(points = points.dropLast(1)), listOf(points.last()))
    }

    fun clear(): Change<T> = Change(MeasureState(mode), points)

    fun withMode(newMode: MeasureMode): Change<T> = if (newMode == mode) Change(this) else Change(MeasureState(newMode), points)

    companion object {
        const val MIN_AREA_POINTS = 3
    }
}

/** A stretch between two placed points, or from the last point to the crosshair ([live]). */
data class Stretch(val from: Int, val to: Int?, val length: Double, val live: Boolean)

/** What a measurement amounts to, with the crosshair counted as a provisional next point. */
data class MeasureResult(
    val stretches: List<Stretch>,
    /** Sum of the stretches (the live one included); null before the first stretch. */
    val total: Double?,
    /** Area mode with 3 points or more (the crosshair included while open). */
    val polygon: PolygonMeasurement?,
) {
    companion object {
        /**
         * [positions] are the placed points, in order; [crosshair] is where the next point would
         * go, or null when the crosshair is not on a surface. Stretch indexes refer to
         * [positions]; a live stretch has [Stretch.to] null.
         */
        fun of(mode: MeasureMode, positions: List<Vec3>, closed: Boolean, crosshair: Vec3?): MeasureResult {
            val stretches = mutableListOf<Stretch>()
            for (i in 0 until positions.size - 1) stretches += Stretch(i, i + 1, positions[i].distanceTo(positions[i + 1]), live = false)
            val state = MeasureState(mode, positions, closed)
            if (closed && positions.size >= 2) {
                stretches += Stretch(positions.lastIndex, 0, positions.last().distanceTo(positions.first()), live = false)
            } else if (crosshair != null && state.continues) {
                stretches += Stretch(positions.lastIndex, null, positions.last().distanceTo(crosshair), live = true)
            }
            val polygon = if (mode == MeasureMode.AREA) {
                // The crosshair on the first point previews the closed outline: that point is not a new corner.
                val closing = crosshair != null && crosshair == positions.firstOrNull()
                val outline = if (closed || crosshair == null || closing) positions else positions + crosshair
                if (outline.size >= MeasureState.MIN_AREA_POINTS) Measurements.polygon(outline) else null
            } else {
                null
            }
            return MeasureResult(stretches, stretches.takeIf { it.isNotEmpty() }?.sumOf { it.length }, polygon)
        }
    }
}

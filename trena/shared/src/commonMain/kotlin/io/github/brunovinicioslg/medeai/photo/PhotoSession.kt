package io.github.brunovinicioslg.medeai.photo

import io.github.brunovinicioslg.medeai.geometry.Vec2

/**
 * The photo measurement flow, in image pixel coordinates: first the four corners of the reference
 * object, then any number of two-point measurements. Immutable: every action returns a new session.
 */
data class PhotoSession(
    val referenceWidth: Double = ReferenceObject.CARD.widthMeters,
    val referenceHeight: Double = ReferenceObject.CARD.heightMeters,
    val corners: List<Vec2> = emptyList(),
    val segments: List<Segment> = emptyList(),
) {
    /** [end] is null while waiting for the second tap. */
    data class Segment(val start: Vec2, val end: Vec2?)

    /** A draggable point. */
    sealed interface Handle {
        data class Corner(val index: Int) : Handle
        data class SegmentStart(val index: Int) : Handle
        data class SegmentEnd(val index: Int) : Handle
    }

    /** Null until four corners are placed, or when they do not form a usable quadrilateral. */
    val plane: PhotoPlane? = if (corners.size == 4) PhotoPlane.calibrate(corners, referenceWidth, referenceHeight) else null

    val placingCorners: Boolean get() = corners.size < 4

    /** Four corners placed but unusable (crossed, collinear, or repeated). */
    val invalidCorners: Boolean get() = corners.size == 4 && plane == null

    /** Meters for a finished segment, null otherwise. */
    fun length(index: Int): Double? {
        val segment = segments.getOrNull(index) ?: return null
        val end = segment.end ?: return null
        return plane?.distance(segment.start, end)
    }

    fun tap(p: Vec2): PhotoSession {
        if (!p.isFinite()) return this
        if (placingCorners) return copy(corners = corners + p)
        val last = segments.lastOrNull()
        return if (last != null && last.end == null) {
            copy(segments = segments.dropLast(1) + last.copy(end = p))
        } else {
            copy(segments = segments + Segment(p, null))
        }
    }

    /** The point closest to [p] within [radius] pixels; measurement points win ties over corners. */
    fun handleNear(p: Vec2, radius: Double): Handle? {
        val candidates = buildList {
            segments.forEachIndexed { i, s ->
                add(Handle.SegmentStart(i) to s.start)
                s.end?.let { add(Handle.SegmentEnd(i) to it) }
            }
            corners.forEachIndexed { i, c -> add(Handle.Corner(i) to c) }
        }
        return candidates
            .map { (handle, point) -> handle to point.distanceTo(p) }
            .filter { it.second <= radius }
            .minByOrNull { it.second }
            ?.first
    }

    fun move(handle: Handle, to: Vec2): PhotoSession {
        if (!to.isFinite()) return this
        return when (handle) {
            is Handle.Corner ->
                if (handle.index in corners.indices) copy(corners = corners.replaced(handle.index, to)) else this
            is Handle.SegmentStart -> segments.getOrNull(handle.index)
                ?.let { copy(segments = segments.replaced(handle.index, it.copy(start = to))) } ?: this
            is Handle.SegmentEnd -> segments.getOrNull(handle.index)?.takeIf { it.end != null }
                ?.let { copy(segments = segments.replaced(handle.index, it.copy(end = to))) } ?: this
        }
    }

    /** Removes the most recently added point. */
    fun undo(): PhotoSession {
        val last = segments.lastOrNull()
        return when {
            last?.end != null -> copy(segments = segments.dropLast(1) + last.copy(end = null))
            last != null -> copy(segments = segments.dropLast(1))
            corners.isNotEmpty() -> copy(corners = corners.dropLast(1))
            else -> this
        }
    }

    fun withReference(widthMeters: Double, heightMeters: Double): PhotoSession =
        if (widthMeters > 0.0 && heightMeters > 0.0) copy(referenceWidth = widthMeters, referenceHeight = heightMeters) else this

    fun clearMeasurements() = copy(segments = emptyList())

    private fun <T> List<T>.replaced(index: Int, value: T) = toMutableList().also { it[index] = value }
}

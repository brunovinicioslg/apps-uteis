package io.github.brunovinicioslg.medeai.ui.ar

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import com.google.ar.core.Anchor
import com.google.ar.core.Camera
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.SessionPausedException
import io.github.brunovinicioslg.medeai.geometry.Vec3
import io.github.brunovinicioslg.medeai.measure.MeasureMode
import io.github.brunovinicioslg.medeai.measure.MeasureResult
import io.github.brunovinicioslg.medeai.measure.MeasureState
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.hypot

/** What the screen needs to know, or tell the user. */
enum class ArStatus {
    STARTING,

    /** Tracking has not begun: the phone must move a little. */
    MOVE_TO_START,
    LOW_LIGHT,
    TOO_FAST,
    FEW_FEATURES,
    CAMERA_UNAVAILABLE,

    /** ARCore stopped working (an internal error): nothing more will come. */
    FAILED,

    /** Tracking was lost; ARCore recovers by itself. */
    LOST,

    /** Tracking, but no surface found yet. */
    FIND_SURFACE,

    /** Surfaces known, but the crosshair is not on one. */
    AIM_AT_SURFACE,
    READY,
}

data class ScreenPoint(val x: Float, val y: Float)

/** One frame's picture of the measurement, for the overlay drawn on top of the camera. */
data class ArOverlay(
    val status: ArStatus = ArStatus.STARTING,
    val mode: MeasureMode = MeasureMode.DISTANCE,
    /** Placed points on the screen, in order; null when behind the camera. */
    val points: List<ScreenPoint?> = emptyList(),
    val result: MeasureResult = MeasureResult(emptyList(), null, null),
    val closed: Boolean = false,
    val canUndo: Boolean = false,
    /** The crosshair is over the first point of an outline: "+" closes it. */
    val snapToFirst: Boolean = false,
    /** Meters from the phone to the surface under the crosshair. */
    val crosshairDistance: Double? = null,
)

sealed interface ArCommand {
    data object Add : ArCommand
    data object Undo : ArCommand
    data object Clear : ArCommand
    data class Mode(val mode: MeasureMode) : ArCommand
}

/**
 * Draws the camera and runs the measurement on the GL thread, where ARCore's frames live: points
 * are anchors, whose positions ARCore keeps refining, so measurements settle as it learns the room.
 */
class ArMeasureRenderer(
    private val session: Session,
    private val displayRotation: () -> Int,
    /** Snapping radius, in pixels. */
    private val snapRadius: Float,
    private val onOverlay: (ArOverlay) -> Unit,
) : GLSurfaceView.Renderer {

    private val background = BackgroundRenderer()
    private val commands = ConcurrentLinkedQueue<ArCommand>()
    private var state = MeasureState<Anchor>(MeasureMode.DISTANCE)
    private var width = 0
    private var height = 0

    /** After a fatal ARCore error the session is not touched again. */
    private var failed = false

    private val view = FloatArray(16)
    private val projection = FloatArray(16)
    private val viewProjection = FloatArray(16)
    private val clip = FloatArray(4)

    /** From any thread; applied on the next frame. */
    fun send(command: ArCommand) {
        commands += command
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        background.create()
        session.setCameraTextureName(background.textureId)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        this.width = width
        this.height = height
        session.setDisplayGeometry(displayRotation(), width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if (width == 0 || height == 0 || failed) return
        val frame = try {
            session.update()
        } catch (e: CameraNotAvailableException) {
            Log.w(TAG, "Camera not available", e)
            onOverlay(ArOverlay(ArStatus.CAMERA_UNAVAILABLE, state.mode))
            return
        } catch (e: SessionPausedException) {
            return // the screen is going away
        } catch (e: RuntimeException) {
            // An exception here would end the GL thread and the app with it.
            Log.e(TAG, "ARCore failed", e)
            failed = true
            onOverlay(ArOverlay(ArStatus.FAILED, state.mode))
            return
        }
        background.draw(frame)

        val camera = frame.camera
        val tracking = camera.trackingState == TrackingState.TRACKING
        if (tracking) {
            camera.getViewMatrix(view, 0)
            camera.getProjectionMatrix(projection, 0, NEAR_M, FAR_M)
            Matrix.multiplyMM(viewProjection, 0, projection, 0, view, 0)
        }
        val hit = if (tracking) crosshairHit(frame, camera) else null
        val center = ScreenPoint(width / 2f, height / 2f)
        fun screenPoints() = state.points.map { anchor -> if (tracking) project(anchor.pose) else null }
        fun snaps(points: List<ScreenPoint?>): Boolean {
            if (!state.canClose) return false
            val first = points.firstOrNull() ?: return false
            return hypot(first.x - center.x, first.y - center.y) <= snapRadius
        }

        while (true) {
            val command = commands.poll() ?: break
            apply(command, hit, snaps(screenPoints()))
        }

        val points = screenPoints()
        val snap = snaps(points)
        val positions = state.points.map { it.pose.toVec3() }
        val crosshair = if (snap) positions.firstOrNull() else hit?.hitPose?.toVec3()
        onOverlay(
            ArOverlay(
                status = status(camera, hit),
                mode = state.mode,
                points = points,
                result = MeasureResult.of(state.mode, positions, state.closed, crosshair),
                closed = state.closed,
                canUndo = state.canUndo,
                snapToFirst = snap,
                crosshairDistance = hit?.distance?.toDouble(),
            ),
        )
    }

    private fun apply(command: ArCommand, hit: HitResult?, snap: Boolean) {
        val change = when (command) {
            ArCommand.Add -> when {
                snap -> state.close()
                hit != null -> state.add(hit.createAnchor())
                else -> return // nothing under the crosshair: the button is disabled anyway
            }
            ArCommand.Undo -> state.undo()
            ArCommand.Clear -> state.clear()
            is ArCommand.Mode -> state.withMode(command.mode)
        }
        change.dropped.forEach(Anchor::detach)
        state = change.state
    }

    /**
     * The surface under the crosshair: a detected plane (inside its outline and facing the
     * camera), else a depth point, else a feature point with a surface normal.
     */
    private fun crosshairHit(frame: Frame, camera: Camera): HitResult? {
        val hits = frame.hitTest(width / 2f, height / 2f)
        hits.firstOrNull { hit ->
            val plane = hit.trackable as? Plane ?: return@firstOrNull false
            plane.isPoseInPolygon(hit.hitPose) && facesCamera(hit.hitPose, camera.pose)
        }?.let { return it }
        hits.firstOrNull { it.trackable is DepthPoint }?.let { return it }
        return hits.firstOrNull { hit ->
            val point = hit.trackable as? Point ?: return@firstOrNull false
            point.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
        }
    }

    /** The camera is on the side of the plane its normal points to (not looking at its back). */
    private fun facesCamera(planePose: Pose, cameraPose: Pose): Boolean {
        val normal = FloatArray(3)
        planePose.getTransformedAxis(1, 1f, normal, 0)
        val dx = cameraPose.tx() - planePose.tx()
        val dy = cameraPose.ty() - planePose.ty()
        val dz = cameraPose.tz() - planePose.tz()
        return dx * normal[0] + dy * normal[1] + dz * normal[2] > 0
    }

    private fun status(camera: Camera, hit: HitResult?): ArStatus = when (camera.trackingState) {
        TrackingState.TRACKING -> when {
            hit != null -> ArStatus.READY
            session.getAllTrackables(Plane::class.java).none { it.trackingState == TrackingState.TRACKING } -> ArStatus.FIND_SURFACE
            else -> ArStatus.AIM_AT_SURFACE
        }
        TrackingState.PAUSED -> when (camera.trackingFailureReason) {
            TrackingFailureReason.NONE -> ArStatus.MOVE_TO_START
            TrackingFailureReason.INSUFFICIENT_LIGHT -> ArStatus.LOW_LIGHT
            TrackingFailureReason.EXCESSIVE_MOTION -> ArStatus.TOO_FAST
            TrackingFailureReason.INSUFFICIENT_FEATURES -> ArStatus.FEW_FEATURES
            TrackingFailureReason.CAMERA_UNAVAILABLE -> ArStatus.CAMERA_UNAVAILABLE
            else -> ArStatus.LOST
        }
        else -> ArStatus.LOST
    }

    /** Screen pixels of a world point, or null when it is behind the camera. */
    private fun project(pose: Pose): ScreenPoint? {
        val world = floatArrayOf(pose.tx(), pose.ty(), pose.tz(), 1f)
        Matrix.multiplyMV(clip, 0, viewProjection, 0, world, 0)
        val w = clip[3]
        if (w <= 0f) return null
        return ScreenPoint((clip[0] / w + 1f) / 2f * width, (1f - clip[1] / w) / 2f * height)
    }

    private fun Pose.toVec3() = Vec3(tx().toDouble(), ty().toDouble(), tz().toDouble())

    private companion object {
        const val TAG = "ArMeasureRenderer"
        const val NEAR_M = 0.05f
        const val FAR_M = 100f
    }
}

package io.github.brunovinicioslg.medeai.ui.photo

import androidx.compose.ui.geometry.Offset
import io.github.brunovinicioslg.medeai.geometry.Vec2
import kotlin.math.min

/** Maps between an image drawn centered and scaled to fit a view, and the image's own pixels. */
data class FitTransform(val scale: Float, val offsetX: Float, val offsetY: Float, val imageWidth: Int, val imageHeight: Int) {

    fun toImage(view: Offset): Vec2 = Vec2(((view.x - offsetX) / scale).toDouble(), ((view.y - offsetY) / scale).toDouble())

    fun toView(image: Vec2): Offset = Offset((image.x * scale + offsetX).toFloat(), (image.y * scale + offsetY).toFloat())

    fun isInsideImage(image: Vec2): Boolean = image.x in 0.0..imageWidth.toDouble() && image.y in 0.0..imageHeight.toDouble()

    companion object {
        /** Null while the view has no size yet or the image is empty. */
        fun fit(imageWidth: Int, imageHeight: Int, viewWidth: Float, viewHeight: Float): FitTransform? {
            if (imageWidth <= 0 || imageHeight <= 0 || viewWidth <= 0f || viewHeight <= 0f) return null
            val scale = min(viewWidth / imageWidth, viewHeight / imageHeight)
            return FitTransform(
                scale = scale,
                offsetX = (viewWidth - imageWidth * scale) / 2,
                offsetY = (viewHeight - imageHeight * scale) / 2,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
            )
        }
    }
}

package com.example.fitvisor__demo.ui.workout

import android.graphics.RectF

/**
 * Maps normalized coordinates (0..1) from MediaPipe to screen pixels.
 * Accounts for Aspect Ratio (Center Crop vs Fit), Mirroring, and Offsets.
 */
class CoordinateMapper {

    private var previewWidth: Int = 1
    private var previewHeight: Int = 1
    private var viewWidth: Int = 1
    private var viewHeight: Int = 1
    
    private var scale: Float = 1f
    private var offsetX: Float = 0f
    private var offsetY: Float = 0f
    private var isMirrored: Boolean = true

    /**
     * Updates the configuration based on the current preview and view sizes.
     * Assumes CENTER_CROP scaling to match the PreviewView.
     */
    fun updateConfig(pWidth: Int, pHeight: Int, vWidth: Int, vHeight: Int, mirrored: Boolean) {
        previewWidth = pWidth
        previewHeight = pHeight
        viewWidth = vWidth
        viewHeight = vHeight
        isMirrored = mirrored

        // Calculate scale for CENTER_CROP (fill the view)
        val scaleX = viewWidth.toFloat() / previewWidth
        val scaleY = viewHeight.toFloat() / previewHeight
        scale = maxOf(scaleX, scaleY)

        // Calculate offsets to center the preview
        offsetX = (viewWidth - previewWidth * scale) / 2f
        offsetY = (viewHeight - previewHeight * scale) / 2f
    }

    /**
     * Maps normalized X to pixel X.
     */
    fun mapX(normalizedX: Float): Float {
        var x = normalizedX
        if (isMirrored) {
            x = 1f - x
        }
        return x * previewWidth * scale + offsetX
    }

    /**
     * Maps normalized Y to pixel Y.
     */
    fun mapY(normalizedY: Float): Float {
        return normalizedY * previewHeight * scale + offsetY
    }

    fun getPreviewBounds(): RectF {
        return RectF(offsetX, offsetY, offsetX + previewWidth * scale, offsetY + previewHeight * scale)
    }

    /** True when X is horizontally flipped (front-camera selfie view). */
    fun isMirrored(): Boolean = isMirrored
}

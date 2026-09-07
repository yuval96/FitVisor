package com.example.fitvisor__demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The synced frame bitmap and the landmark overlay are drawn through the same
 * [CoordinateMapper] state (see [OverlayView]), so any center-crop scale/offset
 * or mirroring bug here would misalign them or make the frame look wrongly
 * zoomed/cropped — exactly the on-device symptoms this class's math must avoid.
 *
 * Assertions go through [CoordinateMapper.mapX]/[mapY] rather than
 * [CoordinateMapper.getPreviewBounds] — the latter returns an
 * [android.graphics.RectF], whose real field semantics aren't guaranteed under
 * the unmocked Android stub these JVM unit tests run against.
 */
class CoordinateMapperTest {

    @Test fun previewNarrowerThanView_scalesToFillWidthAndLetterboxesVertically() {
        val mapper = CoordinateMapper()
        // CENTER_CROP: scale by the larger ratio (width here), then center the
        // now-oversized preview so it overflows top/bottom equally.
        mapper.updateConfig(pWidth = 100, pHeight = 100, vWidth = 200, vHeight = 150, mirrored = false)

        // scale = max(200/100, 150/100) = 2; offsetY = (150 - 100*2)/2 = -25
        assertEquals(0f, mapper.mapX(0f), 0.001f)
        assertEquals(200f, mapper.mapX(1f), 0.001f)
        assertEquals(-25f, mapper.mapY(0f), 0.001f)
        assertEquals(175f, mapper.mapY(1f), 0.001f)
    }

    @Test fun previewTallerThanView_scalesToFillHeightAndCropsSides() {
        val mapper = CoordinateMapper()
        mapper.updateConfig(pWidth = 100, pHeight = 200, vWidth = 300, vHeight = 200, mirrored = false)

        // scale = max(300/100, 200/200) = 3; offsetY = (200 - 200*3)/2 = -200
        assertEquals(0f, mapper.mapX(0f), 0.001f)
        assertEquals(300f, mapper.mapX(1f), 0.001f)
        assertEquals(-200f, mapper.mapY(0f), 0.001f)
        assertEquals(400f, mapper.mapY(1f), 0.001f)
    }

    @Test fun unmirrored_mapsNormalizedOriginToTopLeft() {
        val mapper = CoordinateMapper()
        mapper.updateConfig(pWidth = 100, pHeight = 100, vWidth = 100, vHeight = 100, mirrored = false)

        assertEquals(0f, mapper.mapX(0f), 0.001f)
        assertEquals(100f, mapper.mapX(1f), 0.001f)
        assertEquals(0f, mapper.mapY(0f), 0.001f)
        assertEquals(100f, mapper.mapY(1f), 0.001f)
    }

    @Test fun mirrored_flipsOnlyX() {
        val mapper = CoordinateMapper()
        mapper.updateConfig(pWidth = 100, pHeight = 100, vWidth = 100, vHeight = 100, mirrored = true)

        assertEquals(100f, mapper.mapX(0f), 0.001f)
        assertEquals(0f, mapper.mapX(1f), 0.001f)
        // Y is never mirrored, regardless of the mirrored flag.
        assertEquals(0f, mapper.mapY(0f), 0.001f)
        assertEquals(100f, mapper.mapY(1f), 0.001f)
    }

    @Test fun isMirrored_reflectsTheConfiguredValue() {
        val mapper = CoordinateMapper()
        mapper.updateConfig(pWidth = 100, pHeight = 100, vWidth = 100, vHeight = 100, mirrored = true)
        assertTrue(mapper.isMirrored())

        mapper.updateConfig(pWidth = 100, pHeight = 100, vWidth = 100, vHeight = 100, mirrored = false)
        assertFalse(mapper.isMirrored())
    }
}

package com.simplechat.app.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.simplechat.app.ui.theme.tokens.SurfaceShadowSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfaceShadowTest {
    private val spec = SurfaceShadowSpec(
        elevation = 8.dp,
        ambient = Color.Black.copy(alpha = 0.05f),
        spot = Color.Black.copy(alpha = 0.08f),
        contactElevation = 2.dp,
        contactColor = Color.Black.copy(alpha = 0.09f),
    )

    @Test fun shadowFadesAndShrinksWithSurfaceOpacity() {
        val full = spec.layerShadows(1f)
        val half = spec.layerShadows(0.5f)
        assertEquals(full.size, half.size)
        full.zip(half).forEach { (whole, weaker) ->
            assertEquals(whole.radius.value / 2f, weaker.radius.value, 0.001f)
            assertEquals(whole.spread.value / 2f, weaker.spread.value, 0.001f)
            assertEquals(whole.offset.x.value / 2f, weaker.offset.x.value, 0.001f)
            assertEquals(whole.offset.y.value / 2f, weaker.offset.y.value, 0.001f)
            assertEquals(0.5f, weaker.alpha, 0.001f)
        }
    }

    @Test fun fullyTransparentSurfaceCastsNoShadow() {
        assertTrue(spec.layerShadows(0f).isEmpty())
    }
}

package com.simplechat.app.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.simplechat.app.ui.theme.tokens.ShadowTokens
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

    /**
     * 影子要朝下，不能是围着元素的一圈均匀灰晕。
     *
     * 判据：下方延伸至少是上方残留的三倍 —— 上缘可以有一丝柔和过渡，
     * 但体积必须明显落在下缘。
     */
    @Test fun shadowsCastDownward() {
        val layers = spec.layerShadows(1f)
        val main = layers.first()
        val contact = layers.last()
        val above = (main.radius.value - main.offset.y.value).coerceAtLeast(0f)
        val below = main.radius.value + main.offset.y.value

        assertEquals(0f, main.offset.x.value, 0.001f)
        assertTrue(main.offset.y.value > 0f)
        assertTrue(below > above * 3f)
        assertTrue(contact.offset.y.value > 0f)
    }

    /** 面积越大，投影半径 / 扩散 / 下移都跟着变大。 */
    @Test fun biggerSurfaceCastsBiggerShadow() {
        val small = spec.layerShadows(1f, shadowAreaScale(40f, 40f)).first()
        val large = spec.layerShadows(1f, shadowAreaScale(360f, 240f)).first()

        assertTrue(large.radius.value > small.radius.value)
        assertTrue(large.spread.value > small.spread.value)
        assertTrue(large.offset.y.value > small.offset.y.value)
    }

    @Test fun areaScaleIsBounded() {
        assertEquals(0.85f, shadowAreaScale(1f, 1f), 0.001f)
        assertEquals(1.5f, shadowAreaScale(2000f, 2000f), 0.001f)
        assertEquals(1f, shadowAreaScale(0f, 0f), 0.001f)
    }

    /**
     * 大面板的投影必须压过右下角的小圆钮。
     *
     * 这是真实反馈：按钮用 floating、输入栏用 panel，早先 panel 太淡、floating 太浓，
     * 看起来小按钮反而更“重”。这里按典型尺寸锁住两者的相对关系。
     */
    @Test fun largePanelShadowOutweighsSmallButton() {
        val tokens = ShadowTokens()
        val panel = tokens.panel.layerShadows(1f, shadowAreaScale(360f, 56f)).first()
        val button = tokens.floating.layerShadows(1f, shadowAreaScale(40f, 40f)).first()

        assertTrue(panel.radius.value > button.radius.value)
        assertTrue(panel.offset.y.value > button.offset.y.value)
        assertTrue(panel.color.alpha >= button.color.alpha)
    }
}

package com.simplechat.app.ui.theme.tokens

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.simplechat.app.ui.theme.ChatColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeResolverTest {

    private val text = Color(0xFF1A1A1A)
    private val base = ThemeTokens(
        dark = false,
        colors = ChatColors(
            userBubble = Color(0xFFDCE6FF), onUserBubble = text,
            processText = text.copy(alpha = 0.58f), processBar = text.copy(alpha = 0.16f),
            placeholder = text.copy(alpha = 0.38f), pageBackground = Color.White,
            settingsBackground = Color(0xFFF5F6F8), card = Color.White,
            outline = text.copy(alpha = 0.09f), divider = text.copy(alpha = 0.06f),
            danger = Color.Red, warning = Color.Yellow,
            capsuleSelected = Color.Blue.copy(alpha = 0.16f), fieldBackground = text.copy(alpha = 0.05f),
            menuBackground = Color.White, markdownCodeBlock = text.copy(alpha = 0.025f),
            markdownInlineCode = text.copy(alpha = 0.08f), markdownQuoteBar = text.copy(alpha = 0.34f),
            markdownRule = Color.Black.copy(alpha = 0.1f), scrim = Color.Black.copy(alpha = 0.45f),
        ),
        scheme = lightColorScheme(onSurface = text, surface = Color.White, background = Color.White),
    )

    @Test fun nullSpecKeepsDefaults() {
        assertEquals(base, ThemeResolver.resolve(base, null).tokens)
    }

    @Test fun partialSpecOnlyOverridesGivenKeys() {
        val spec = ThemeFiles.parse("""{"schema":1,"light":{"colors":{"primary":"#FF6A00"}},"shapes":{"soft":10}}""").getOrThrow()
        val t = ThemeResolver.resolve(base, spec).tokens
        assertEquals(Color(0xFFFF6A00), t.scheme.primary)
        assertEquals(10.dp, t.shapes.soft)
        assertEquals(base.shapes.menu, t.shapes.menu)
        assertEquals(base.colors.card, t.colors.card)
    }

    @Test fun lowContrastPaletteIsReverted() {
        val spec = ThemeFiles.parse("""{"light":{"colors":{"text":"#CCCCCC","pageBackground":"#FFFFFF"}}}""").getOrThrow()
        val r = ThemeResolver.resolve(base, spec)
        assertEquals(text, r.tokens.scheme.onSurface)
        assertTrue(r.warnings.isNotEmpty())
    }

    @Test fun surfaceFloorsAndBlurAreClampedToSafetyLimits() {
        val spec = ThemeFiles.parse("""{"surface":{"drawerFloor":0.1,"floatingMaxBlur":80,"accentTint":0.5}}""").getOrThrow()
        val s = ThemeResolver.resolve(base, spec).tokens.surface
        assertEquals(SurfaceTokens.SafetyFloor, s.drawerFloor)
        assertEquals(SurfaceTokens.MaxBlurCap, s.floatingMaxBlur)
        assertEquals(SurfaceTokens.MaxAccentTint, s.accentTint)
    }

    @Test fun unknownKeysIgnoredButNewerSchemaRejected() {
        assertTrue(ThemeFiles.parse("""{"schema":1,"futureThing":{"a":1}}""").isSuccess)
        assertTrue(ThemeFiles.parse("""{"schema":99}""").isFailure)
    }
}

package com.simplechat.app.ui.common

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp

@Composable
internal fun LoadingIndicator(
    modifier: Modifier,
    strokeWidth: Dp,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    if (LocalUseSimpleCatIcon.current) {
        SimpleCatLoadingIndicator(modifier, color)
    } else {
        CircularProgressIndicator(modifier = modifier, color = color, strokeWidth = strokeWidth)
    }
}

private val DrawEasing = CubicBezierEasing(.35f, 0f, .28f, 1f)
private val RetractEasing = CubicBezierEasing(.42f, 0f, .6f, 1f)
private val EaseOut = CubicBezierEasing(0f, 0f, .58f, 1f)
private val EaseInOut = CubicBezierEasing(.42f, 0f, .58f, 1f)
private val BodyStroke = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
private val LidStroke = Stroke(width = 1.3f, cap = StrokeCap.Round)

private fun interval(phase: Float, start: Float, end: Float, easing: Easing): Float =
    easing.transform(((phase - start) / (end - start)).coerceIn(0f, 1f))

@Composable
internal fun SimpleCatLoadingIndicator(modifier: Modifier, color: Color) {
    val progress = rememberInfiniteTransition(label = "simpleCatLoading").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3000, easing = LinearEasing)),
        label = "simpleCatPhase",
    )
    val outline = remember {
        PathMeasure().apply {
            setPath(PathParser().parsePathString(SimpleCatOutlinePath).toPath(), forceClosed = false)
        }
    }
    val segment = remember { Path() }
    val openEyes = remember { PathParser().parsePathString(SimpleCatOpenEyesPath).toPath() }
    val lids = remember {
        PathParser().parsePathString("M7.15 12.3q1.15 1 2.3 0m5.1 0q1.15 1 2.3 0").toPath()
    }

    Canvas(modifier.semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }) {
        // The infinite transition snaps to the target when system animations are disabled.
        val phase = if (progress.value == 1f) .74f else progress.value
        val end = interval(phase, 0f, .48f, DrawEasing)
        val start = .88f * interval(phase, .78f, .93f, RetractEasing) +
            .13f * interval(phase, .93f, .98f, EaseOut)
        val outlineAlpha = interval(phase, 0f, .03f, DrawEasing) -
            interval(phase, .93f, .98f, EaseOut)
        val lidAlpha = interval(phase, .50f, .54f, EaseInOut) -
            interval(phase, .57f, .62f, EaseInOut)
        val eyeAlpha = interval(phase, .56f, .61f, EaseOut) -
            interval(phase, .76f, .83f, EaseOut)
        val eyeScale = .08f + .92f * (interval(phase, .56f, .66f, EaseOut) -
            interval(phase, .76f, .83f, EaseOut))
        val squash = interval(phase, .54f, .58f, EaseInOut) - interval(phase, .58f, .63f, EaseInOut)
        val lift = interval(phase, .58f, .63f, EaseInOut) - interval(phase, .63f, .72f, EaseInOut)
        val viewportScale = size.minDimension / 24f

        withTransform({
            translate((size.width - size.minDimension) / 2f, (size.height - size.minDimension) / 2f)
            scale(viewportScale, viewportScale, Offset.Zero)
            translate(0f, .12f * squash - .65f * lift)
            scale(1f + .025f * squash - .01f * lift, 1f - .03f * squash + .02f * lift, Offset(12f, 19f))
        }) {
            segment.rewind()
            if (outlineAlpha > 0f && start < end &&
                outline.getSegment(start * outline.length, end * outline.length, segment)
            ) {
                drawPath(segment, color, alpha = outlineAlpha, style = BodyStroke)
            }
            if (lidAlpha > 0f) drawPath(lids, color, alpha = lidAlpha, style = LidStroke)
            if (eyeAlpha > 0f) {
                scale(1f, eyeScale, Offset(12f, 12.4f)) {
                    drawPath(openEyes, color, alpha = eyeAlpha, style = BodyStroke)
                }
            }
        }
    }
}

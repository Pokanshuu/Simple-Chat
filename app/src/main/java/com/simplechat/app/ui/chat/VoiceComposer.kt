package com.simplechat.app.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.simplechat.app.R

internal fun Modifier.holdToSpeak(
    onTap: () -> Unit,
    start: () -> Boolean,
    finish: (Boolean) -> Unit,
    onSlide: (Boolean) -> Unit,
): Modifier = pointerInput(Unit) {
    val cancelDistance = 56.dp.toPx()
    val returnDistance = 44.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(pass = PointerEventPass.Initial)
        down.consume()
        val tapped = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            var released = false
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null || change.isConsumed ||
                    (change.position - down.position).getDistance() > viewConfiguration.touchSlop ||
                    event.changes.any { it.id != down.id && it.pressed }
                ) break
                change.consume()
                released = !change.pressed
            } while (!released)
            released
        }
        if (tapped != null) {
            if (tapped) onTap()
            return@awaitEachGesture
        }
        if (!start()) return@awaitEachGesture
        var sliding = false
        var released = false
        var distance = 0f
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (change.isConsumed || event.changes.any { it.id != down.id && it.pressed }) break
                // Deltas avoid counting a layout shift when the keyboard or an input panel closes.
                distance += change.previousPosition.y - change.position.y
                val newSliding = distance >= if (sliding) returnDistance else cancelDistance
                if (newSliding != sliding) {
                    sliding = newSliding
                    onSlide(sliding)
                }
                change.consume()
                if (!change.pressed) {
                    released = true
                    break
                }
            }
        } finally {
            onSlide(false)
            finish(released && !sliding)
        }
    }
}

@Composable
internal fun VoiceComposer(
    state: VoiceInputState,
    soundLevel: () -> Float,
    cancelling: Boolean,
    tapMode: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = when {
        state.processing -> R.string.voice_processing
        cancelling -> R.string.voice_release_cancel
        tapMode -> R.string.voice_tap_to_send
        else -> R.string.voice_listening
    }
    val level by animateFloatAsState(
        targetValue = if (state.processing) 0f else soundLevel(),
        animationSpec = tween(95),
        label = "voiceLevel",
    )
    Column(
        modifier.fillMaxWidth().heightIn(min = 70.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .then(if (tapMode && state.holding) Modifier.clickable(onClick = onTap) else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        Text(stringResource(label), color = Color.White, style = MaterialTheme.typography.labelLarge)
        Canvas(Modifier.width(138.dp).height(27.dp)) {
            val centerY = size.height / 2f
            VoiceBars.forEachIndexed { index, weight ->
                val x = size.width * index / VoiceBars.lastIndex
                val height = 4.dp.toPx() + (size.height - 4.dp.toPx()) * weight * (0.15f + 0.85f * level)
                drawLine(Color.White.copy(alpha = if (state.processing) 0.54f else 0.95f),
                    Offset(x, centerY - height / 2), Offset(x, centerY + height / 2),
                    strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
            }
        }
        if (state.holding && !cancelling && !tapMode) {
            Text(stringResource(R.string.voice_swipe_cancel), color = Color.White.copy(alpha = 0.86f),
                style = MaterialTheme.typography.labelSmall)
        }
    }
}

private val VoiceBars = floatArrayOf(
    0.17f, 0.29f, 0.48f, 0.33f, 0.62f, 0.53f, 0.78f, 0.54f, 0.93f, 0.69f, 1f,
    0.74f, 0.88f, 0.60f, 0.72f, 0.47f, 0.62f, 0.36f, 0.48f, 0.25f, 0.16f,
)

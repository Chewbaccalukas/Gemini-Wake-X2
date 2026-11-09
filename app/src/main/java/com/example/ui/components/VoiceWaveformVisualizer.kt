package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.sin

@Composable
fun VoiceWaveformVisualizer(
    isListening: Boolean,
    audioRmsDb: Float,
    modifier: Modifier = Modifier,
    barCount: Int = 24,
    height: androidx.compose.ui.unit.Dp = 56.dp
) {
    val infiniteTransition = rememberInfiniteTransition(label = "wave_ambient")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6.2831f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    val animatedRms = remember { Animatable(0f) }
    LaunchedEffect(audioRmsDb) {
        animatedRms.animateTo(
            targetValue = audioRmsDb,
            animationSpec = tween(durationMillis = 80)
        )
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
    ) {
        val width = size.width
        val height = size.height
        val centerY = height / 2f
        val barWidth = (width / (barCount * 1.6f)).coerceIn(3f, 10f)
        val gap = (width - (barCount * barWidth)) / (barCount - 1)

        val activeGradient = Brush.verticalGradient(
            colors = listOf(
                Color(0xFF38BDF8),
                Color(0xFFA855F7),
                Color(0xFF38BDF8)
            )
        )

        val idleGradient = Brush.verticalGradient(
            colors = listOf(
                Color(0xFF64748B),
                Color(0xFF475569)
            )
        )

        for (i in 0 until barCount) {
            val normalizedIndex = i.toFloat() / barCount
            val wave = sin(phase + (normalizedIndex * 4f))

            val currentHeight = if (isListening) {
                val rmsFactor = (animatedRms.value / 10f).coerceIn(0f, 1f)
                val base = height * 0.18f
                val dynamic = (height * 0.75f) * rmsFactor * (0.4f + 0.6f * ((wave + 1f) / 2f))
                (base + dynamic).coerceIn(4f, height)
            } else {
                (height * 0.15f * ((wave + 1f) / 2f) + 4f).coerceIn(4f, height * 0.35f)
            }

            val x = i * (barWidth + gap)
            val top = centerY - (currentHeight / 2f)

            drawRoundRect(
                brush = if (isListening && animatedRms.value > 0.5f) activeGradient else idleGradient,
                topLeft = Offset(x, top),
                size = Size(barWidth, currentHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}

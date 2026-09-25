package com.svetlana.home.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.svetlana.home.avatar.AvatarLevel
import kotlin.math.cos
import kotlin.math.sin

/**
 * Living Orb — световой AI-шар Светланы.
 *
 * Стиль: Premium AI / Dark Glass / Liquid Light.
 * Лёгкие частицы и мягкие анимации на Canvas, без тяжёлой 3D-графики.
 *
 * Визуальное состояние зависит от режима:
 *  - L0 Living Orb — пульсирующий шар с частицами;
 *  - L1 Light Avatar — шар + мягкий силуэт;
 *  - L2/L3 — шар + расходящееся свечение (av realistic render подключается
 *    отдельным движком при наличии ресурсов).
 */
@Composable
fun LivingOrb(
    modifier: Modifier = Modifier,
    size: Dp = 220.dp,
    level: AvatarLevel = AvatarLevel.L0_LIVING_ORB,
    active: Boolean = true,
    speaking: Boolean = false
) {
    val transition = rememberInfiniteTransition(label = "orb")

    val breath by transition.animateFloat(
        initialValue = 0.88f, targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (speaking) 900 else 3400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "breath"
    )
    val rotation by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(24000, easing = LinearEasing)),
        label = "rotation"
    )
    val particles by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(6000, easing = LinearEasing)),
        label = "particles"
    )
    val glow by transition.animateFloat(
        initialValue = 0.35f, targetValue = if (active) 0.85f else 0.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(2600, easing = LinearEasing), repeatMode = RepeatMode.Reverse
        ), label = "glow"
    )

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // Внешнее свечение
        Canvas(modifier = Modifier.size(size * 1.6f).blur(24.dp)) {
            val center = Offset(this.size.width / 2f, this.size.height / 2f)
            val radius = this.size.minDimension / 2f * breath
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF5FE3B0).copy(alpha = glow * 0.6f),
                        Color(0xFF123B30).copy(alpha = glow * 0.3f),
                        Color.Transparent
                    )
                ),
                radius = radius,
                center = center
            )
        }

        // Орбита-кольца для старших уровней
        if (level.level >= 1) {
            Canvas(modifier = Modifier.size(size * 1.25f)) {
                val center = Offset(this.size.width / 2f, this.size.height / 2f)
                val r = this.size.minDimension / 2f * 0.92f
                drawCircle(
                    color = Color(0xFF5FE3B0).copy(alpha = 0.18f),
                    radius = r,
                    center = center,
                    style = Stroke(width = 1.5f)
                )
                val dotAngle = Math.toRadians(rotation.toDouble())
                drawCircle(
                    color = Color(0xFF8FF0CC).copy(alpha = 0.9f),
                    radius = 5f,
                    center = Offset(
                        (center.x + r * cos(dotAngle)).toFloat(),
                        (center.y + r * sin(dotAngle)).toFloat()
                    )
                )
            }
        }

        // Сам шар
        Canvas(modifier = Modifier.size(size)) {
            val center = Offset(this.size.width / 2f, this.size.height / 2f)
            val radius = this.size.minDimension / 2f * breath

            // Тёмное стекло-основа
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF12211C),
                        Color(0xFF0A1210),
                        Color(0xFF05080A)
                    ),
                    center = Offset(center.x - radius * 0.25f, center.y - radius * 0.3f),
                    radius = radius * 1.2f
                ),
                radius = radius,
                center = center
            )

            // Жидкий свет: градиентное ядро
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFFE8FFF6).copy(alpha = if (active) 0.95f else 0.6f),
                        Color(0xFF8FF0CC).copy(alpha = 0.85f),
                        Color(0xFF5FE3B0).copy(alpha = 0.55f),
                        Color(0xFF123B30).copy(alpha = 0.2f),
                        Color.Transparent
                    ),
                    center = Offset(center.x - radius * 0.22f, center.y - radius * 0.28f),
                    radius = radius * 0.95f
                ),
                radius = radius * 0.92f,
                center = center
            )

            // Блик
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFFFFFFFF).copy(alpha = 0.5f), Color.Transparent),
                    center = Offset(center.x - radius * 0.3f, center.y - radius * 0.35f),
                    radius = radius * 0.35f
                ),
                radius = radius * 0.35f,
                center = Offset(center.x - radius * 0.3f, center.y - radius * 0.35f)
            )

            // Лёгкие частицы
            val count = 14
            for (i in 0 until count) {
                val angle = (i.toFloat() / count) * Math.PI * 2 + rotation * 0.01
                val dist = radius * (0.55f + 0.4f * ((i % 3) / 3f))
                val wob = sin((particles * Math.PI * 2 + i).toFloat()) * 6f
                val px = center.x + (dist * cos(angle)).toFloat() + wob
                val py = center.y + (dist * sin(angle)).toFloat() - wob / 2
                drawCircle(
                    color = Color(0xFF8FF0CC).copy(alpha = 0.35f + 0.35f * sin(particles * Math.PI.toFloat() + i)),
                    radius = (1.6f + (i % 4) * 0.7f),
                    center = Offset(px, py)
                )
            }

            // Уровни 2-3: расходящееся свечение "реального" аватара
            if (level.level >= 2) {
                for (ring in 0..1) {
                    drawCircle(
                        color = Color(0xFF5FE3B0).copy(alpha = 0.12f * (1 - ring * 0.4f)),
                        radius = radius * (1.05f + ring * 0.12f) * breath,
                        center = center,
                        style = Stroke(width = 1f)
                    )
                }
            }
        }
    }
}

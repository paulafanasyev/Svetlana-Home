package com.svetlana.home.ui.components

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Образ Светланы — тот же портрет, что в Svetlana 2.0
 * (public/images/avatar/svetlana-master.jpg). Файл кладётся в assets при сборке
 * (задача fetchSvetlanaAvatar); если его нет, показывается прежний световой шар.
 */
object SvetlanaAvatarImage {
    const val ASSET = "avatar/svetlana.jpg"

    @Volatile
    private var cached: ImageBitmap? = null

    @Volatile
    private var missing = false

    fun peek(): ImageBitmap? = cached

    fun load(context: Context): ImageBitmap? {
        cached?.let { return it }
        if (missing) return null
        return try {
            context.assets.open(ASSET).use { BitmapFactory.decodeStream(it) }
                ?.asImageBitmap()
                ?.also { cached = it }
        } catch (t: Throwable) {
            Log.w("SvetlanaAvatar", "Портрет не найден в assets — показываем шар", t)
            missing = true
            null
        }
    }
}

@Composable
fun rememberSvetlanaAvatar(): ImageBitmap? {
    val context = LocalContext.current.applicationContext
    val avatar by produceState(initialValue = SvetlanaAvatarImage.peek(), context) {
        if (value == null) value = withContext(Dispatchers.IO) { SvetlanaAvatarImage.load(context) }
    }
    return avatar
}

/**
 * Живой портрет: мягкое мятное свечение, «дыхание» и кольцо, которое
 * пульсирует, пока Светлана слушает или говорит.
 */
@Composable
fun SvetlanaPortrait(
    avatar: ImageBitmap,
    modifier: Modifier = Modifier,
    size: Dp = 220.dp,
    active: Boolean = true,
    speaking: Boolean = false
) {
    val transition = rememberInfiniteTransition(label = "portrait")
    val breath by transition.animateFloat(
        initialValue = 0.985f, targetValue = if (speaking) 1.035f else 1.015f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (speaking) 600 else 3200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "breath"
    )
    val glow by transition.animateFloat(
        initialValue = 0.25f, targetValue = if (active) 0.75f else 0.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = LinearEasing), repeatMode = RepeatMode.Reverse
        ), label = "glow"
    )
    val ring by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(1600, easing = LinearEasing)),
        label = "ring"
    )
    val mint = Color(0xFF5FE3B0)
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size * 1.35f).blur(20.dp)) {
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(mint.copy(alpha = glow * 0.55f), mint.copy(alpha = glow * 0.15f), Color.Transparent)
                ),
                radius = this.size.minDimension / 2f
            )
        }
        if (active || speaking) {
            Canvas(Modifier.size(size * 1.18f)) {
                val c = Offset(this.size.width / 2f, this.size.height / 2f)
                val base = size.toPx() / 2f
                drawCircle(
                    color = mint.copy(alpha = (1f - ring) * 0.6f),
                    radius = base * (1f + 0.16f * ring),
                    center = c,
                    style = Stroke(width = 3.dp.toPx())
                )
            }
        }
        Image(
            bitmap = avatar,
            contentDescription = "Светлана",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size)
                .scale(breath)
                .clip(CircleShape)
                .border((size.value / 60f).coerceIn(1.5f, 4f).dp, mint.copy(alpha = 0.55f), CircleShape)
        )
    }
}

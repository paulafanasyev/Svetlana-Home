package com.svetlana.home.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.svetlana.home.avatar.AvatarLevel

/**
 * Образ Светланы — только настоящий портрет из Svetlana 2.0 ([SvetlanaPortrait]).
 * Прежний световой шар удалён. Имя функции оставлено, чтобы не трогать вызовы
 * (главный экран, панель, голос, онбординг). Портрет всегда есть в APK — без него
 * сборка падает (см. fetchSvetlanaAvatar); пока картинка декодируется (миллисекунды),
 * на её месте тонкий контур того же размера.
 */
@Composable
fun LivingOrb(
    modifier: Modifier = Modifier,
    size: Dp = 220.dp,
    @Suppress("UNUSED_PARAMETER") level: AvatarLevel = AvatarLevel.L0_LIVING_ORB,
    active: Boolean = true,
    speaking: Boolean = false
) {
    val avatar = rememberSvetlanaAvatar()
    if (avatar != null) {
        SvetlanaPortrait(avatar = avatar, modifier = modifier, size = size, active = active, speaking = speaking)
    } else {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(size)
                    .border(2.dp, Color(0xFF5FE3B0).copy(alpha = 0.35f), CircleShape)
            )
        }
    }
}

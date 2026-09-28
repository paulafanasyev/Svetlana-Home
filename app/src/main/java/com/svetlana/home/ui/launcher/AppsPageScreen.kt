package com.svetlana.home.ui.launcher

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.svetlana.home.R
import com.svetlana.home.apps.AppModel
import com.svetlana.home.apps.AppRegistry
import com.svetlana.home.core.ServiceLocator
import com.svetlana.home.ui.apps.AppDrawerActivity
import com.svetlana.home.ui.components.GlassCard
import com.svetlana.home.ui.settings.SettingsActivity
import com.svetlana.home.ui.theme.AlmostBlack
import com.svetlana.home.ui.theme.MintPrimary
import com.svetlana.home.ui.theme.MintSoft
import com.svetlana.home.ui.theme.TextPrimary
import com.svetlana.home.ui.theme.TextSecondary
import com.svetlana.home.ui.theme.TextTertiary

/**
 * Страница 3: приложения + настройки.
 *
 * Закреплённые приложения (перенесённые сюда пользователем через
 * «На главный экран» в App Drawer) показаны вверху сеткой.
 * Ниже — все приложения и вход в настройки.
 */
@Composable
fun AppsPageScreen(appRegistry: AppRegistry) {
    val context = LocalContext.current
    val apps by appRegistry.apps.collectAsState()

    val visible = apps.filterNot { it.isHidden }
    val pinned = visible.filter { it.isFavorite }
    val others = visible.filterNot { it.isFavorite }

    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Заголовок
        item(span = { GridItemSpan(4) }) {
            Text(
                text = stringResource(R.string.page_apps),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 4.dp)
            )
        }

        // Закреплённые
        if (pinned.isNotEmpty()) {
            item(span = { GridItemSpan(4) }) {
                SectionHeader(stringResource(R.string.apps_pinned_section))
            }
            items(pinned, key = { it.packageName }) { app ->
                AppIcon(app) { launch(context, app) }
            }
            item(span = { GridItemSpan(4) }) { Spacer(Modifier.height(8.dp)) }
        }

        // Входы: все приложения и настройки
        item(span = { GridItemSpan(4) }) {
            EntryRow(
                icon = Icons.Outlined.Apps,
                label = stringResource(R.string.apps_all_section),
                sub = "${visible.size} приложений"
            ) {
                context.startActivity(Intent(context, AppDrawerActivity::class.java))
            }
        }
        item(span = { GridItemSpan(4) }) {
            EntryRow(
                icon = Icons.Outlined.Settings,
                label = stringResource(R.string.home_settings),
                sub = "ИИ, голос, Hands, разрешения, устройство"
            ) {
                context.startActivity(Intent(context, SettingsActivity::class.java))
            }
        }

        // Все приложения
        if (others.isNotEmpty()) {
            item(span = { GridItemSpan(4) }) {
                SectionHeader(stringResource(R.string.apps_all_section))
            }
            items(others, key = { it.packageName }) { app ->
                AppListItem(app) { launch(context, app) }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 2.dp)
    )
}

@Composable
private fun AppIcon(app: AppModel, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 8.dp)
    ) {
        AppIconDrawable(app.packageName, Modifier.size(52.dp))
        Spacer(Modifier.height(6.dp))
        Text(
            text = app.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun AppListItem(app: AppModel, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIconDrawable(app.packageName, Modifier.size(40.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                text = app.label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outlineVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun EntryRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    sub: String,
    onClick: () -> Unit
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.clickable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MintPrimary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

/**
 * Иконка приложения из PackageManager. Безопасно возвращает заглушку,
 * если пакет уже удалён.
 */
@Composable
private fun AppIconDrawable(packageName: String, modifier: Modifier) {
    val context = LocalContext.current
    val drawable = remember(packageName) {
        runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull()
    }
    if (drawable != null) {
        androidx.compose.foundation.Image(
            bitmap = drawableToBitmap(drawable).asImageBitmap(),
            contentDescription = null,
            modifier = modifier
        )
    } else {
        Box(
            modifier = modifier.background(MintSoft.copy(alpha = 0.2f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Outlined.Apps,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

private fun drawableToBitmap(drawable: android.graphics.drawable.Drawable): android.graphics.Bitmap {
    return if (drawable is android.graphics.drawable.BitmapDrawable && drawable.bitmap != null) {
        drawable.bitmap
    } else {
        val width = drawable.intrinsicWidth.coerceAtLeast(1)
        val height = drawable.intrinsicHeight.coerceAtLeast(1)
        val bmp = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        bmp
    }
}

private fun launch(context: android.content.Context, app: AppModel) {
    runCatching {
        val intent = context.packageManager.getLaunchIntentForPackage(app.packageName) ?: return@runCatching
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        ServiceLocator.appRepository.markUsed(app.packageName)
    }
}

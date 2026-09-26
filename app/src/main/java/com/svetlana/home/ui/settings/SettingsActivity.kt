package com.svetlana.home.ui.settings

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.svetlana.home.R
import com.svetlana.home.core.ServiceLocator
import com.svetlana.home.ui.components.GlassCard
import com.svetlana.home.ui.theme.AlmostBlack
import com.svetlana.home.ui.theme.MintPrimary
import com.svetlana.home.ui.theme.SvetlanaTheme
import com.svetlana.home.ui.theme.TextSecondary
import com.svetlana.home.ui.history.HistoryScreen

/**
 * Настройки — единый экран с разделами.
 */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SvetlanaTheme { SettingsScreen(onBack = { finish() }) } }
    }
}

enum class SettingsSection(val titleRes: Int) {
    HOME(R.string.title_settings),
    AI(R.string.title_providers),
    LOCAL_AI(R.string.title_local_ai),
    MODELS(R.string.title_model_registry),
    SERVER(R.string.title_server),
    VOICE(R.string.voice_settings),
    HANDS(R.string.hands_settings),
    TRANSLATOR(R.string.title_translator),
    LAUNCHER(R.string.title_launcher),
    APPS(R.string.title_apps),
    AVATAR(R.string.title_avatar),
    DEVICE(R.string.title_device),
    OWNER(R.string.title_owner),
    PERMISSIONS(R.string.permission_center),
    MEMORY(R.string.title_memory),
    PRIVACY(R.string.privacy_settings),
    HISTORY(R.string.title_history),
    SYSTEM(R.string.title_system_settings),
    ABOUT(R.string.title_about)
}

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    var section by remember { mutableStateOf(SettingsSection.HOME) }

    Box(modifier = Modifier.fillMaxSize().background(AlmostBlack)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (section != SettingsSection.HOME) {
                    IconButton(onClick = { section = SettingsSection.HOME }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Назад")
                    }
                }
                Text(
                    text = stringResource(section.titleRes),
                    style = MaterialTheme.typography.headlineMedium
                )
            }
            Spacer(Modifier.height(16.dp))

            when (section) {
                SettingsSection.HOME -> SectionsList { section = it }
                SettingsSection.AI -> AiProvidersScreen()
                SettingsSection.LOCAL_AI -> LocalAiScreen()
                SettingsSection.MODELS -> ModelRegistryScreen()
                SettingsSection.SERVER -> ServerScreen()
                SettingsSection.VOICE -> VoiceSettingsScreen()
                SettingsSection.HANDS -> HandsSettingsScreen()
                SettingsSection.TRANSLATOR -> TranslatorSettingsScreen()
                SettingsSection.LAUNCHER -> LauncherSettingsScreen()
                SettingsSection.APPS -> AppDrawerSettingsScreen()
                SettingsSection.AVATAR -> AvatarScreen()
                SettingsSection.DEVICE -> DeviceScreen()
                SettingsSection.OWNER -> OwnerScreen()
                SettingsSection.PERMISSIONS -> PermissionCenterScreen()
                SettingsSection.MEMORY -> MemoryScreen()
                SettingsSection.PRIVACY -> PrivacySettingsScreen()
                SettingsSection.HISTORY -> HistoryScreen()
                SettingsSection.SYSTEM -> SystemSettingsScreen()
                SettingsSection.ABOUT -> AboutScreen()
            }
        }
    }
}

@Composable
private fun SectionsList(onOpen: (SettingsSection) -> Unit) {
    // Аудит п.8: настройки Светланы отделены от системных настроек Android.
    // Системные настройки — отдельный раздел в конце, который открывает
    // системные экраны, а не дублирует их.
    val sections = listOf(
        SettingsSection.AI,
        SettingsSection.LOCAL_AI,
        SettingsSection.MODELS,
        SettingsSection.SERVER,
        SettingsSection.VOICE,
        SettingsSection.HANDS,
        SettingsSection.TRANSLATOR,
        SettingsSection.LAUNCHER,
        SettingsSection.APPS,
        SettingsSection.AVATAR,
        SettingsSection.DEVICE,
        SettingsSection.OWNER,
        SettingsSection.PERMISSIONS,
        SettingsSection.MEMORY,
        SettingsSection.PRIVACY,
        SettingsSection.HISTORY,
        SettingsSection.SYSTEM,
        SettingsSection.ABOUT
    )
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(sections.size) { index ->
            val s = sections[index]
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(s) }
                ) {
                    Text(
                        text = stringResource(s.titleRes),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = TextSecondary)
                }
            }
        }
    }
}

package com.svetlana.home.ui.settings

import android.app.Activity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.svetlana.home.R
import com.svetlana.home.core.AppLanguage
import com.svetlana.home.ui.components.GlassCard

/** Названия языков — на самих этих языках, чтобы их узнали при любом текущем языке. */
@Composable
private fun choiceLabel(choice: AppLanguage.Choice): String = when (choice) {
    AppLanguage.Choice.SYSTEM -> stringResource(R.string.lang_system)
    AppLanguage.Choice.RU -> "Русский"
    AppLanguage.Choice.EN -> "English"
}

@Composable
private fun LanguageOptions(selected: AppLanguage.Choice, onSelect: (AppLanguage.Choice) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AppLanguage.Choice.entries.forEach { choice ->
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(choice) }
                        .testTag("lang_${choice.name.lowercase()}"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        choiceLabel(choice),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (choice == selected) Text("✓", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/** Настройки → Язык: смена языка применяется сразу (экраны пересоздаются). */
@Composable
fun LanguageScreen() {
    val context = LocalContext.current
    var current by remember { mutableStateOf(AppLanguage.current(context)) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.lang_settings_note), style = MaterialTheme.typography.bodyMedium)
        LanguageOptions(current) { choice ->
            current = choice
            (context as? Activity)?.let { AppLanguage.set(it, choice) }
        }
    }
}

/** Первый запуск: выбор языка до онбординга. */
@Composable
fun LanguagePickerFirstRun(onDone: (AppLanguage.Choice) -> Unit) {
    var selected by remember { mutableStateOf(AppLanguage.Choice.SYSTEM) }
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp)
            .testTag("language_picker")
    ) {
        // До выбора язык ещё неизвестен — заголовок на обоих языках.
        Text("Язык · Language", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Выберите язык Светланы. Его можно поменять позже в настройках.\n" +
                "Choose Svetlana's language. You can change it later in Settings.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(20.dp))
        LanguageOptions(selected) { selected = it }
        Spacer(Modifier.weight(1f))
        Button(
            onClick = { onDone(selected) },
            modifier = Modifier.fillMaxWidth().height(52.dp).testTag("language_continue"),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Text("Продолжить · Continue", color = MaterialTheme.colorScheme.onPrimary)
        }
    }
}

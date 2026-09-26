package com.svetlana.home.ui.translate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.svetlana.home.R
import com.svetlana.home.core.ServiceLocator
import com.svetlana.home.translate.TranslateDirection
import com.svetlana.home.translate.TranslateResult
import com.svetlana.home.ui.components.GlassCard
import com.svetlana.home.ui.theme.AlmostBlack
import com.svetlana.home.ui.theme.MintPrimary
import com.svetlana.home.ui.theme.SvetlanaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Светлана Переводчик — RU ↔ VI (ТЗ §52, §53, §55).
 */
class TranslateActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SvetlanaTheme { TranslateScreen() } }
    }
}

@Composable
private fun TranslateScreen() {
    val scope = rememberCoroutineScope()
    var direction by remember { mutableStateOf(TranslateDirection.RU_TO_VI) }
    var source by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<TranslateResult?>(null) }
    var busy by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(AlmostBlack)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .statusBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.title_translator),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.fillMaxWidth())

            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(
                    text = direction.label,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = stringResource(R.string.translate_swap),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .clickable {
                            direction = if (direction == TranslateDirection.RU_TO_VI)
                                TranslateDirection.VI_TO_RU else TranslateDirection.RU_TO_VI
                        }
                        .background(MintPrimary.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }

            OutlinedTextField(
                value = source,
                onValueChange = { source = it },
                label = { Text(if (direction == TranslateDirection.RU_TO_VI)
                    stringResource(R.string.translate_speak_ru) else stringResource(R.string.translate_speak_vi)) },
                modifier = Modifier.fillMaxWidth()
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (source.isBlank()) return@Button
                        busy = true
                        scope.launch(Dispatchers.IO) {
                            val r = ServiceLocator.translator.translateText(source, direction)
                            withContext(Dispatchers.Main) { result = r; busy = false }
                        }
                    },
                    enabled = !busy && source.isNotBlank(),
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MintPrimary)
                ) { Text("Перевести", color = AlmostBlack) }

                Button(
                    onClick = {
                        busy = true
                        scope.launch(Dispatchers.IO) {
                            val r = ServiceLocator.translator.translateVoice(direction)
                            withContext(Dispatchers.Main) { result = r; busy = false }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("Голосом") }
            }

            Button(
                onClick = {
                    busy = true
                    scope.launch(Dispatchers.IO) {
                        val r = ServiceLocator.translator.translateConversationCycle()
                        withContext(Dispatchers.Main) { result = r; busy = false }
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(14.dp)
            ) { Text(stringResource(R.string.translate_sync_mode)) }

            result?.let { res ->
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        Text(stringResource(R.string.translate_result),
                            style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = if (res.success) res.text else "Не удалось перевести: ${res.error ?: ""}",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(Modifier.height(6.dp))
                        Text("Способ: ${res.backend}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

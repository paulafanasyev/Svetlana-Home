package com.svetlana.home.ui.onboarding

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.svetlana.home.R
import com.svetlana.home.core.ServiceLocator
import com.svetlana.home.owner.OwnerIdentity
import com.svetlana.home.ui.components.LivingOrb
import com.svetlana.home.ui.launcher.HomeActivity
import com.svetlana.home.ui.permissions.PermissionSetupScreen
import com.svetlana.home.ui.theme.AlmostBlack
import com.svetlana.home.ui.theme.MintPrimary
import com.svetlana.home.ui.theme.SvetlanaTheme
import kotlinx.coroutines.launch

/**
 * Onboarding — первый запуск (ТЗ §65).
 */
class OnboardingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SvetlanaTheme {
                OnboardingFlowContent(
                    launchHome = {
                        startActivity(Intent(this@OnboardingActivity, HomeActivity::class.java))
                    },
                    onFinished = { finish() }
                )
            }
        }
    }
}

/**
 * Переиспользуемый onboarding-флоу (ТЗ §65).
 *
 * [launchHome] вызывается по завершении; на HomeActivity он не нужен —
 * Home уже на экране, поэтому туда передаётся no-op.
 */
@Composable
fun OnboardingFlowContent(
    launchHome: () -> Unit = {},
    onFinished: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var stage by remember { mutableStateOf(0) }

    Box(modifier = Modifier.fillMaxSize().background(AlmostBlack)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(24.dp))
                LivingOrb(size = 150.dp)
                Spacer(Modifier.height(24.dp))
                Text(
                    text = stringResource(R.string.onboarding_welcome),
                    style = MaterialTheme.typography.displayMedium
                )
            }

            when (stage) {
                0 -> IntroStage()
                1 -> OwnerCreateStage(
                    onCreated = { stage = 2 },
                    onSkip = { stage = 2 }
                )
                else -> PermissionSetupScreen(
                    onAllHandled = {
                        scope.launch {
                            ServiceLocator.settings.setOnboardingDone(true)
                            ServiceLocator.settings.setSetupDone(true)
                        }
                        launchHome()
                        onFinished()
                    }
                )
            }

            when (stage) {
                0 -> Column(modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { stage = 1 },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MintPrimary)
                    ) {
                        Text(
                            text = stringResource(R.string.onboarding_setup_button),
                            color = AlmostBlack
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = {
                        scope.launch { ServiceLocator.settings.setOnboardingDone(true) }
                        launchHome()
                        onFinished()
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text(text = stringResource(R.string.onboarding_skip))
                    }
                }
            }
        }
    }
}

/**
 * Шаг 0 — краткое описание возможностей (ТЗ §65).
 */
@Composable
private fun IntroStage() {
    Column {
        Text(
            text = stringResource(R.string.onboarding_intro),
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(12.dp))
        val abilities = listOf(
            R.string.onboarding_ability_launcher,
            R.string.onboarding_ability_apps,
            R.string.onboarding_ability_harness,
            R.string.onboarding_ability_voice,
            R.string.onboarding_ability_vision,
            R.string.onboarding_ability_translate,
            R.string.onboarding_ability_local,
            R.string.onboarding_ability_external,
            R.string.onboarding_ability_server
        )
        abilities.forEach { res ->
            Text(
                text = "•  " + stringResource(res),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Владелец: создаётся единый профиль. PIN и пароли не сохраняются.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

/**
 * Шаг 1 — создание профиля владельца (ТЗ §23).
 *
 * Ключ защиты генерируется в Android Keystore; PIN и пароли не сохраняются.
 */
@Composable
private fun OwnerCreateStage(
    onCreated: () -> Unit,
    onSkip: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Создать профиль владельца",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Имя используется только на этом устройстве. Ключ защиты " +
                    "генерируется в Android Keystore; PIN и пароли не сохраняются.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Как вас зовут?") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                val displayName = name.trim().ifBlank { "Владелец" }
                scope.launch {
                    val result = ServiceLocator.ownerIdentity.createOwner(displayName)
                    when (result) {
                        is OwnerIdentity.Result.Created -> {
                            ServiceLocator.settings.setOwnerName(displayName)
                            onCreated()
                        }
                        is OwnerIdentity.Result.AlreadyExists -> onCreated()
                        is OwnerIdentity.Result.Failed -> {
                            message = "Не удалось создать профиль: ${result.message}. " +
                                    "Android Keystore недоступен — проверьте блокировку экрана."
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MintPrimary)
        ) {
            Text(text = "Создать профиль", color = AlmostBlack)
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
            Text(text = "Пропустить")
        }
        if (message.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(text = message, style = MaterialTheme.typography.bodySmall, color = MintPrimary)
        }
    }
}

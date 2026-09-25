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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.svetlana.home.ui.components.LivingOrb
import com.svetlana.home.ui.launcher.HomeActivity
import com.svetlana.home.ui.permissions.PermissionSetupScreen
import com.svetlana.home.ui.settings.SettingsActivity
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
        setContent { SvetlanaTheme { OnboardingFlow(onFinished = { finish() }) } }
    }

    @Composable
    private fun OnboardingFlow(onFinished: () -> Unit) {
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        var stage by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0) }

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
                    else -> PermissionSetupScreen(
                        onAllHandled = {
                            scope.launch {
                                ServiceLocator.settings.setOnboardingDone(true)
                                ServiceLocator.settings.setSetupDone(true)
                            }
                            startActivity(Intent(this@OnboardingActivity, HomeActivity::class.java))
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
                            startActivity(Intent(this@OnboardingActivity, HomeActivity::class.java))
                            onFinished()
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text(text = stringResource(R.string.onboarding_skip))
                        }
                    }
                }
            }
        }
    }

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
}

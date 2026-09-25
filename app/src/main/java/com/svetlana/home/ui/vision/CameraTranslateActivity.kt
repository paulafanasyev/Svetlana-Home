package com.svetlana.home.ui.vision

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.svetlana.home.R
import com.svetlana.home.core.ServiceLocator
import com.svetlana.home.translate.TranslateDirection
import com.svetlana.home.ui.components.GlassCard
import com.svetlana.home.ui.theme.AlmostBlack
import com.svetlana.home.ui.theme.MintPrimary
import com.svetlana.home.ui.theme.SvetlanaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Camera Translation (ТЗ §55): Камера → OCR → определение языка → перевод → отображение.
 *
 * OCR выполняется через подключённого провайдера (VLM на сервере/внешнем AI).
 * Если провайдер не настроен, приложение честно об этом сообщает.
 */
class CameraTranslateActivity : ComponentActivity() {

    private var imageCapture: ImageCapture? = null
    private var hasCameraPermission = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hasCameraPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
        setContent { SvetlanaTheme { CameraTranslateScreen() } }
    }

    @Composable
    private fun CameraTranslateScreen() {
        val scope = rememberCoroutineScope()
        var status by remember { mutableStateOf("Наведите камеру на текст и нажмите «Перевести»") }
        val lifecycleOwner = LocalLifecycleOwner.current

        Box(modifier = Modifier.fillMaxSize().background(AlmostBlack)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(stringResource(R.string.title_camera_translate),
                    style = MaterialTheme.typography.headlineMedium)

                if (hasCameraPermission) {
                    AndroidView(
                        factory = { ctx ->
                            val previewView = PreviewView(ctx)
                            val providerFuture = ProcessCameraProvider.getInstance(ctx)
                            providerFuture.addListener({
                                try {
                                    val provider = providerFuture.get()
                                    val preview = Preview.Builder().build().also {
                                        it.setSurfaceProvider(previewView.surfaceProvider)
                                    }
                                    imageCapture = ImageCapture.Builder().build()
                                    provider.unbindAll()
                                    provider.bindToLifecycle(
                                        lifecycleOwner,
                                        CameraSelector.DEFAULT_BACK_CAMERA,
                                        preview, imageCapture
                                    )
                                } catch (t: Throwable) {
                                    status = "Камера недоступна: ${t.message}"
                                }
                            }, ContextCompat.getMainExecutor(ctx))
                            previewView
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(320.dp)
                    )
                } else {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Text("Нет разрешения на камеру. Его можно выдать в «Разрешениях Светланы».",
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }

                Button(
                    onClick = {
                        val capture = imageCapture ?: return@Button
                        scope.launch(Dispatchers.IO) {
                            status = "Делаю снимок…"
                            // Захват кадра
                            val executor = ContextCompat.getMainExecutor(this@CameraTranslateActivity)
                            val result = kotlinx.coroutines.suspendCancellableCoroutine<android.graphics.Bitmap?> { cont ->
                                capture.takePicture(
                                    executor,
                                    object : ImageCapture.OnImageCapturedCallback() {
                                        override fun onCaptureSuccess(image: androidx.camera.core.ImageProxy) {
                                            val bmp = image.toBitmap()
                                            image.close()
                                            cont.resume(bmp) { }
                                        }
                                        override fun onError(exception: ImageCaptureException) {
                                            cont.resume(null) { }
                                        }
                                    }
                                )
                            }
                            if (result == null) {
                                withContext(Dispatchers.Main) { status = "Не удалось сделать снимок" }
                                return@launch
                            }
                            status = "Распознаю текст…"
                            val aiResult = ServiceLocator.vision.analyzeImage(result)
                            if (!aiResult.success) {
                                withContext(Dispatchers.Main) {
                                    status = "OCR недоступен: ${aiResult.text.take(120)}. " +
                                            "Подключите сервер или внешнего провайдера с поддержкой зрения."
                                }
                                return@launch
                            }
                            status = "Перевожу…"
                            val translated = ServiceLocator.translator.translateImageText(
                                aiResult.text, TranslateDirection.RU_TO_VI)
                            withContext(Dispatchers.Main) {
                                status = if (translated.success)
                                    "Перевод:\n${translated.text}" else "Не удалось перевести: ${translated.error}"
                            }
                        }
                    },
                    enabled = hasCameraPermission,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MintPrimary)
                ) { Text("Перевести", color = AlmostBlack) }

                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Text(status, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

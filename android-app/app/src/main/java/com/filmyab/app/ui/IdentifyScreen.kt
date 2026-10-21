package com.filmyab.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.filmyab.app.MainViewModel
import com.filmyab.app.UiState
import com.filmyab.app.ui.theme.Accent
import com.filmyab.app.ui.theme.DimText
import com.filmyab.app.ui.theme.ErrorRed
import com.filmyab.app.ui.theme.OnAccent
import com.filmyab.app.ui.theme.OnBg
import com.filmyab.app.ui.theme.OnSurfaceVariantDark
import com.filmyab.app.ui.theme.StatusFail
import com.filmyab.app.ui.theme.StatusLow
import com.filmyab.app.ui.theme.StatusSuccess
import com.filmyab.app.ui.theme.SurfaceDark

@Composable
fun IdentifyScreen(viewModel: MainViewModel) {
    val state = viewModel.uiState.collectAsState().value
    val imagePicked = viewModel.imagePicked.collectAsState().value
    val urlText = viewModel.urlInput.collectAsState().value
    val noteText = viewModel.noteInput.collectAsState().value

    val pickImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        viewModel.onImagePicked(uri)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        /* ---------- Header ---------- */
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "فیلم‌یاب",
                color = Accent,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                "اسم فیلم را از روی کلیپ اینستاگرام پیدا کن",
                color = OnSurfaceVariantDark,
                fontSize = 14.sp
            )
        }

        /* ---------- Input card ---------- */
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    OutlinedTextField(
                        value = urlText,
                        onValueChange = { viewModel.setUrlInput(it) },
                        label = { Text("لینک اینستاگرام (پست/ریلز)") },
                        placeholder = { Text("https://www.instagram.com/reel/...") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        trailingIcon = {
                            val clipboardManager = LocalClipboardManager.current
                            IconButton(onClick = {
                                val clipboardText = clipboardManager.getText()?.text
                                if (!clipboardText.isNullOrBlank()) {
                                    viewModel.setUrlInput(clipboardText.trim())
                                }
                            }) {
                                Icon(
                                    Icons.Filled.ContentPaste,
                                    contentDescription = "چسباندن از کلیپ‌بورد",
                                    tint = OnSurfaceVariantDark
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                OutlinedTextField(
                    value = noteText,
                    onValueChange = { viewModel.setNoteInput(it) },
                    label = { Text("راهنما (اختیاری)") },
                    placeholder = { Text("هر چیزی می‌دانی: ژانر، زبان، سال...") },
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!imagePicked) {
                        OutlinedButton(onClick = {
                            pickImageLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }) {
                            Icon(
                                Icons.Filled.Image,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("اسکرین‌شات (اختیاری)")
                        }
                    } else {
                        Text(
                            "تصویر انتخاب شد ✓",
                            color = StatusSuccess,
                            fontSize = 13.sp
                        )
                        IconButton(onClick = { viewModel.clearImage() }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "حذف تصویر",
                                tint = OnSurfaceVariantDark,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                Button(
                    onClick = { viewModel.identify(urlText, noteText) },
                    enabled = state !is UiState.Running,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Accent,
                        contentColor = OnAccent
                    )
                ) {
                    if (state is UiState.Running) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = OnAccent
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("در حال پردازش…")
                    } else {
                        Text("شناسایی فیلم", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        /* ---------- Running card ---------- */
        val running = state as? UiState.Running
        if (running != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (running.isFallback) {
                        AssistChip(
                            onClick = {},
                            label = { Text("تحلیل سریع روی دستگاه", fontSize = 12.sp) },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.CloudOff,
                                    contentDescription = null,
                                    tint = DimText,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )
                    }
                    running.steps.forEach { step ->
                        StepRow(label = step.label, status = step.status)
                    }
                    if (running.currentLabel.isNotBlank()) {
                        Text(running.currentLabel, fontSize = 12.sp, color = DimText)
                    }
                }
            }
        }

        /* ---------- Result / Error ---------- */
        when (val s = state) {
            is UiState.Success -> {
                val quick = s.quick
                if (quick != null) {
                    QuickResultCard(quick)
                    TextButton(
                        onClick = { viewModel.resetToIdle() },
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Text("شناسایی مجدد", color = Accent)
                    }
                } else {
                    ResultCard(result = s.result, onRetry = { viewModel.resetToIdle() })
                }
            }
            is UiState.Error -> {
                ErrorCard(
                    message = s.message,
                    hint = s.hint,
                    canTryScreenshot = s.canTryScreenshot,
                    onRequestScreenshot = {
                        viewModel.requestScreenshotIdentify(noteText)
                        pickImageLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                )
            }
            else -> {}
        }
    }
}

@Composable
private fun ErrorCard(
    message: String,
    hint: String?,
    canTryScreenshot: Boolean,
    onRequestScreenshot: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = ErrorRed,
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    "خطا",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = ErrorRed
                )
            }
            Text(message, fontSize = 14.sp, color = OnBg, lineHeight = 22.sp)
            if (hint == "screenshot") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        Icons.Filled.Info,
                        contentDescription = null,
                        tint = StatusLow,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        "پیشنهاد: از حالت اسکرین‌شات استفاده کنید",
                        fontSize = 13.sp,
                        color = StatusLow
                    )
                }
                if (canTryScreenshot) {
                    Button(
                        onClick = onRequestScreenshot,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Accent,
                            contentColor = OnAccent
                        )
                    ) {
                        Text("انتخاب اسکرین‌شات")
                    }
                }
            }
        }
    }
}

package com.filmyab.app.ui

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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.filmyab.app.data.NetState
import com.filmyab.app.ui.theme.Accent
import com.filmyab.app.ui.theme.BgDark
import com.filmyab.app.ui.theme.DimText
import com.filmyab.app.ui.theme.ErrorRed
import com.filmyab.app.ui.theme.OnAccent
import com.filmyab.app.ui.theme.OnBg
import com.filmyab.app.ui.theme.OnSurfaceVariantDark
import com.filmyab.app.ui.theme.StatusFail
import com.filmyab.app.ui.theme.StatusLow
import com.filmyab.app.ui.theme.StatusSuccess
import com.filmyab.app.ui.theme.SurfaceDark
import com.filmyab.app.ui.theme.SurfaceVariantDark

@Composable
fun IdentifyScreen(viewModel: MainViewModel) {
    val state = viewModel.uiState.collectAsState().value
    val net = viewModel.netState.collectAsState().value
    val urlText = viewModel.urlInput.collectAsState().value

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
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
                "لینک را کپی کن؛ موتور هوشمند خودش همه‌چیز را پیدا می‌کند",
                color = OnSurfaceVariantDark,
                fontSize = 14.sp
            )
        }

        /* ---------- Auto VPN / connectivity banner ---------- */
        NetBanner(net = net, onRefresh = { viewModel.refreshNetwork() })

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
                            // NOTE: clipboard access must be hoisted here (composable scope)
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

                Button(
                    onClick = { viewModel.identifyFromUi() },
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
                        Icon(
                            Icons.Filled.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("شناسایی خودکار", fontSize = 16.sp, fontWeight = FontWeight.Bold)
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
                SmartResultCard(result = s.result, onRetry = { viewModel.resetToIdle() })
            }
            is UiState.Error -> {
                ErrorCard(
                    message = s.message,
                    showVpnHint = s.needVpn || net.kind == "vpn_needed"
                )
            }
            else -> {}
        }

        /* ---------- About ---------- */
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = BgDark)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = null,
                    tint = DimText,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    "نسخه ۲.۰ — بدون سرور و بدون هیچ تنظیماتی. موتور هوشمند روی دستگاه: متادیتای پست + فریم‌ها + متن روی تصویر + ویکی‌پدیا",
                    fontSize = 12.sp,
                    color = DimText
                )
            }
        }
    }
}

@Composable
private fun NetBanner(net: NetState, onRefresh: () -> Unit) {
    val (color, icon, message) = when (net.kind) {
        "ok" -> Triple(StatusSuccess, Icons.Filled.CheckCircle, "اتصال برقراره — اینستاگرام در دسترسه")
        "vpn_needed" -> Triple(
            StatusLow,
            Icons.Filled.VpnKey,
            "اینستاگرام در دسترس نیست — فیلترشکن را روشن کن"
        )
        "offline" -> Triple(StatusFail, Icons.Filled.WifiOff, "اینترنت در دسترس نیست")
        else -> Triple(DimText, Icons.Filled.Refresh, "در حال بررسی اتصال…")
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (net.kind == "checking") SurfaceVariantDark else color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (net.kind == "checking") {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = DimText
                )
            } else {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
            }
            Text(
                message,
                fontSize = 13.sp,
                color = if (net.kind == "checking") DimText else color,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onRefresh, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = "بررسی مجدد اتصال",
                    tint = OnSurfaceVariantDark,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String, showVpnHint: Boolean) {
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
            if (showVpnHint) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        Icons.Filled.VpnKey,
                        contentDescription = null,
                        tint = StatusLow,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        "فیلترشکن را روشن کن و دوباره «شناسایی خودکار» را بزن",
                        fontSize = 13.sp,
                        color = StatusLow
                    )
                }
            }
        }
    }
}

package com.filmyab.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.filmyab.app.MainViewModel
import com.filmyab.app.ui.theme.Accent
import com.filmyab.app.ui.theme.OnAccent
import com.filmyab.app.ui.theme.OnBg
import com.filmyab.app.ui.theme.OnSurfaceVariantDark
import com.filmyab.app.ui.theme.StatusFail
import com.filmyab.app.ui.theme.StatusSuccess

@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val serverUrl = viewModel.serverUrl.collectAsState().value
    val connTest = viewModel.connTest.collectAsState().value
    var urlInput by remember(serverUrl) { mutableStateOf(serverUrl) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            "تنظیمات",
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            color = OnBg
        )

        SectionCard(title = "سرور شناسایی") {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    label = { Text("آدرس سرور") },
                    placeholder = { Text("http://10.0.2.2:3000") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { viewModel.setServerUrl(urlInput) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Accent,
                        contentColor = OnAccent
                    )
                ) {
                    Text("ذخیره")
                }
                OutlinedButton(
                    onClick = { viewModel.testConnection() },
                    enabled = !connTest.testing
                ) {
                    if (connTest.testing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Accent
                        )
                        Spacer(Modifier.width(6.dp))
                    } else {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text("تست اتصال")
                }
            }
            when {
                connTest.testing -> {}
                connTest.ok == true -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = StatusSuccess,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        "✓ سرور در دسترس است",
                        fontSize = 13.sp,
                        color = StatusSuccess
                    )
                }
                connTest.ok == false -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = null,
                        tint = StatusFail,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        "✕ اتصال ممکن نشد — برنامه به حالت تحلیل سریع برمی‌گردد",
                        fontSize = 13.sp,
                        color = StatusFail
                    )
                }
            }
        }

        SectionCard(title = "سرور چیست؟") {
            Text(
                "پایپ‌لاین کامل شناسایی (تحلیل فریم‌های ویدیو با هوش مصنوعی بینایی، جستجوی وب و تطبیق هوشمند) " +
                    "روی سرور اجرا می‌شود. اگر سرور در دسترس نباشد، برنامه به «حالت تحلیل سریع روی دستگاه» " +
                    "برمی‌گردد که فقط کپشن و هشتگ پست را می‌خواند و دقت کمتری دارد.",
                fontSize = 13.sp,
                color = OnSurfaceVariantDark,
                lineHeight = 21.sp
            )
            Text(
                "برای فعال‌سازی حالت کامل، بک‌اند این پروژه را اجرا (deploy) کنید و آدرس آن را در فیلد بالا وارد کنید. " +
                    "آدرس پیش‌فرض http://10.0.2.2:3000 فقط در شبیه‌ساز اندروید کار می‌کند.",
                fontSize = 13.sp,
                color = OnSurfaceVariantDark,
                lineHeight = 21.sp
            )
        }

        SectionCard(title = "درباره") {
            Text(
                "فیلم‌یاب — نسخه ۱٫۰",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = OnBg
            )
            Text(
                "پایپ‌لاین شناسایی در ۵ گام: دریافت پست از اینستاگرام ← تحلیل صحنه با هوش مصنوعی ← " +
                    "جستجو در وب ← تطبیق هوشمند ← یافتن پوستر.",
                fontSize = 13.sp,
                color = OnSurfaceVariantDark,
                lineHeight = 21.sp
            )
            Text(
                "ساخته‌شده با ❤ برای دوستداران سینما",
                fontSize = 13.sp,
                color = Accent
            )
        }
    }
}

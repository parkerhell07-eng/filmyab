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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.filmyab.app.MainViewModel
import com.filmyab.app.data.LocalHistoryItem
import com.filmyab.app.ui.theme.Accent
import com.filmyab.app.ui.theme.DimText
import com.filmyab.app.ui.theme.OnBg
import com.filmyab.app.ui.theme.OnSurfaceVariantDark
import com.filmyab.app.ui.theme.StatusFail
import com.filmyab.app.ui.theme.StatusLow
import com.filmyab.app.ui.theme.StatusSuccess
import com.filmyab.app.ui.theme.SurfaceDark
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun HistoryScreen(viewModel: MainViewModel) {
    val historyItems = viewModel.history.collectAsState().value
    var showClearDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "تاریخچه",
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                color = OnBg,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { showClearDialog = true }) {
                Icon(
                    Icons.Filled.DeleteSweep,
                    contentDescription = "پاک کردن تاریخچه",
                    tint = OnSurfaceVariantDark
                )
            }
        }

        if (historyItems.isEmpty()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Filled.Movie,
                    contentDescription = null,
                    tint = DimText,
                    modifier = Modifier.size(56.dp)
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "هنوز چیزی اینجا نیست",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = OnBg
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "نتیجه‌های شناسایی اینجا ذخیره می‌شوند",
                    fontSize = 13.sp,
                    color = DimText
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(historyItems) { item ->
                    HistoryCard(item)
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("همه تاریخچه پاک شود؟") },
            text = {
                Text("این کار همه نتیجه‌های ذخیره‌شده روی این دستگاه را حذف می‌کند.")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearHistory()
                    showClearDialog = false
                }) {
                    Text("پاک کن", color = StatusFail)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("انصراف", color = OnSurfaceVariantDark)
                }
            }
        )
    }
}

@Composable
private fun HistoryCard(item: LocalHistoryItem) {
    val dateText = remember(item.createdAtMs) {
        SimpleDateFormat("yyyy/MM/dd HH:mm", Locale("fa")).format(Date(item.createdAtMs))
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PosterImage(
                url = item.posterUrl,
                modifier = Modifier
                    .width(44.dp)
                    .height(64.dp)
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    item.movieName ?: item.originalName ?: "—",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = OnBg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusChip(status = item.status)
                    Text(sourceLabel(item.source), fontSize = 11.sp, color = DimText)
                }
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Text(dateText, fontSize = 11.sp, color = DimText)
                }
            }
            Text(
                "${(item.confidence.coerceIn(0.0, 1.0) * 100).roundToInt()}٪",
                fontSize = 12.sp,
                color = Accent
            )
        }
    }
}

@Composable
private fun StatusChip(status: String) {
    val pair = when (status) {
        "success" -> StatusSuccess to "موفق"
        "low_confidence" -> StatusLow to "کم‌دقت"
        else -> StatusFail to "ناموفق"
    }
    Surface(
        color = pair.first.copy(alpha = 0.15f),
        shape = RoundedCornerShape(50)
    ) {
        Text(
            pair.second,
            color = pair.first,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

private fun sourceLabel(source: String): String = when (source) {
    "link" -> "لینک"
    "screenshot" -> "اسکرین‌شات"
    "quick" -> "سریع"
    else -> source
}

package com.filmyab.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.filmyab.app.data.SmartResult
import com.filmyab.app.ui.theme.Accent
import com.filmyab.app.ui.theme.DimText
import com.filmyab.app.ui.theme.ErrorRed
import com.filmyab.app.ui.theme.NavIndicator
import com.filmyab.app.ui.theme.OnBg
import com.filmyab.app.ui.theme.OnSurfaceVariantDark
import com.filmyab.app.ui.theme.StatusLow
import com.filmyab.app.ui.theme.StatusSuccess
import com.filmyab.app.ui.theme.SurfaceDark
import com.filmyab.app.ui.theme.SurfaceVariantDark
import kotlin.math.roundToInt

/** Poster image with graceful placeholder (gray box + movie icon) when missing or failed. */
@Composable
fun PosterImage(url: String?, modifier: Modifier = Modifier) {
    if (url.isNullOrBlank()) {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(12.dp))
                .background(SurfaceVariantDark),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Movie,
                contentDescription = null,
                tint = DimText,
                modifier = Modifier.size(28.dp)
            )
        }
    } else {
        AsyncImage(
            model = url,
            contentDescription = null,
            modifier = modifier.clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.Crop,
            placeholder = ColorPainter(SurfaceVariantDark),
            error = ColorPainter(SurfaceVariantDark)
        )
    }
}

/** One engine step row: state icon + Persian label. */
@Composable
fun StepRow(label: String, status: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        when (status) {
            "done" -> Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = StatusSuccess,
                modifier = Modifier.size(18.dp)
            )
            "skip" -> Icon(
                Icons.Filled.Remove,
                contentDescription = null,
                tint = DimText,
                modifier = Modifier.size(18.dp)
            )
            "error" -> Icon(
                Icons.Filled.Close,
                contentDescription = null,
                tint = ErrorRed,
                modifier = Modifier.size(18.dp)
            )
            "start" -> CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = Accent
            )
            else -> Icon(
                Icons.Filled.Schedule,
                contentDescription = null,
                tint = DimText.copy(alpha = 0.5f),
                modifier = Modifier.size(18.dp)
            )
        }
        Text(
            label,
            fontSize = 14.sp,
            color = if (status == "pending") DimText else OnBg
        )
    }
}

/** «دقت تشخیص: NN٪» label + rounded accent progress bar. */
@Composable
fun ConfidenceBar(confidence: Double) {
    val clamped = confidence.coerceIn(0.0, 1.0)
    val pct = (clamped * 100).roundToInt()
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("دقت تشخیص: $pct٪", fontSize = 13.sp, color = OnSurfaceVariantDark)
        LinearProgressIndicator(
            progress = { clamped.toFloat() },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = Accent,
            trackColor = NavIndicator,
            strokeCap = StrokeCap.Round
        )
    }
}

/** Card wrapper with a bold title. */
@Composable
fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = OnBg)
            content()
        }
    }
}

/** Smart engine result card: poster, names, meta, confidence, reasoning, wiki, candidates. */
@Composable
fun SmartResultCard(result: SmartResult, onRetry: () -> Unit) {
    var detailsExpanded by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val title = result.titleEn ?: result.titleFa ?: "—"
    val subtitle = result.titleFa?.takeIf { it.isNotBlank() && it != title }
    val year = result.year
    val director = result.director
    val reasoning = result.reasoningFa

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark)
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                PosterImage(
                    url = result.posterUrl,
                    modifier = Modifier
                        .width(110.dp)
                        .height(165.dp)
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp,
                        color = OnBg
                    )
                    if (subtitle != null) {
                        Text(subtitle, fontSize = 16.sp, color = Accent)
                    }
                    if (year != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Filled.Schedule,
                                contentDescription = null,
                                tint = DimText,
                                modifier = Modifier.size(15.dp)
                            )
                            Text("سال $year", fontSize = 13.sp, color = OnSurfaceVariantDark)
                        }
                    }
                    if (director != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Filled.Movie,
                                contentDescription = null,
                                tint = DimText,
                                modifier = Modifier.size(15.dp)
                            )
                            Text("کارگردان: $director", fontSize = 13.sp, color = OnSurfaceVariantDark)
                        }
                    }
                }
            }

            if (result.genres.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    result.genres.take(4).forEach { genre ->
                        Surface(
                            color = SurfaceVariantDark,
                            shape = RoundedCornerShape(50)
                        ) {
                            Text(
                                genre,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                fontSize = 12.sp,
                                color = OnSurfaceVariantDark
                            )
                        }
                    }
                }
            }

            ConfidenceBar(result.confidence)

            if (result.status == "low_confidence") {
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
                        "دقت پایین — گزینه‌های دیگر را هم ببینید",
                        fontSize = 13.sp,
                        color = StatusLow
                    )
                }
            }

            if (reasoning != null && reasoning.isNotBlank()) {
                Text(
                    reasoning,
                    fontSize = 14.sp,
                    color = OnBg,
                    lineHeight = 22.sp
                )
            }

            if (result.wikiUrl != null || result.googleQuery != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (result.wikiUrl != null) {
                        FilledTonalButton(
                            onClick = {
                                try {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(result.wikiUrl))
                                    )
                                } catch (e: Exception) {
                                    // ignore malformed URLs
                                }
                            },
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = NavIndicator,
                                contentColor = Accent
                            )
                        ) {
                            Icon(
                                Icons.Filled.Link,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("ویکی‌پدیا", fontSize = 13.sp)
                        }
                    }
                    if (result.googleQuery != null) {
                        FilledTonalButton(
                            onClick = {
                                try {
                                    context.startActivity(
                                        Intent(
                                            Intent.ACTION_VIEW,
                                            Uri.parse(
                                                "https://www.google.com/search?q=" +
                                                    Uri.encode(result.googleQuery)
                                            )
                                        )
                                    )
                                } catch (e: Exception) {
                                    // ignore
                                }
                            },
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = NavIndicator,
                                contentColor = Accent
                            )
                        ) {
                            Icon(
                                Icons.Filled.Search,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("جستجوی بیشتر", fontSize = 13.sp)
                        }
                    }
                }
            }

            if (result.candidates.isNotEmpty()) {
                Text("گزینه‌های دیگر", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = OnBg)
                Column(
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    result.candidates.forEach { candidate ->
                        val candidateYear = candidate.year
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (candidateYear != null) {
                                    "${candidate.name ?: "—"} ($candidateYear)"
                                } else {
                                    candidate.name ?: "—"
                                },
                                fontSize = 14.sp,
                                color = OnSurfaceVariantDark,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "${(candidate.confidence.coerceIn(0.0, 1.0) * 100).roundToInt()}٪",
                                fontSize = 13.sp,
                                color = Accent
                            )
                        }
                    }
                }
            }

            if ((result.sceneSummary != null && result.sceneSummary.isNotBlank()) ||
                (result.evidenceSummary != null && result.evidenceSummary.isNotBlank())
            ) {
                TextButton(onClick = { detailsExpanded = !detailsExpanded }) {
                    Text(
                        if (detailsExpanded) "بستن جزئیات" else "جزئیات تحلیل",
                        fontSize = 13.sp,
                        color = Accent
                    )
                }
                if (detailsExpanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (result.evidenceSummary != null && result.evidenceSummary.isNotBlank()) {
                            Text(
                                "شواهد استفاده‌شده",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = DimText
                            )
                            Text(
                                result.evidenceSummary,
                                fontSize = 13.sp,
                                color = OnSurfaceVariantDark,
                                lineHeight = 20.sp
                            )
                        }
                        if (result.sceneSummary != null && result.sceneSummary.isNotBlank()) {
                            Text(
                                "توضیح صحنه",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = DimText
                            )
                            Text(
                                result.sceneSummary,
                                fontSize = 13.sp,
                                color = OnSurfaceVariantDark,
                                lineHeight = 20.sp
                            )
                        }
                    }
                }
            }

            TextButton(onClick = onRetry) {
                Text("شناسایی مجدد", color = Accent)
            }
        }
    }
}

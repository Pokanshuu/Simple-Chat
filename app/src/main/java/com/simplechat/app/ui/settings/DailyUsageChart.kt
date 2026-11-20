package com.simplechat.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.simplechat.app.R
import com.simplechat.app.db.DailyUsage
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
internal fun DailyUsageChart(firstDay: LocalDate, rows: List<DailyUsage>) {
    val days = remember(firstDay) { List(30) { firstDay.plusDays(it.toLong()) } }
    val amounts = remember(days, rows) {
        val byDate = rows.associate { it.day to it.tokens }
        days.map { byDate[it.toString()] ?: 0L }
    }
    val maximum = amounts.maxOrNull() ?: 0L
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (maximum <= 0L) {
            Text(stringResource(R.string.stats_usage_daily_empty), style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        var selected by remember(amounts) { mutableIntStateOf(amounts.indexOfLast { it > 0L }) }
        val number = NumberFormat.getIntegerInstance()
        val accent = MaterialTheme.colorScheme.primary
        Text(stringResource(R.string.stats_usage_daily_value, days[selected].toString(), number.format(amounts[selected])),
            style = MaterialTheme.typography.labelMedium)
        Row(Modifier.fillMaxWidth().height(112.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            amounts.forEachIndexed { index, count ->
                val description = stringResource(R.string.stats_usage_daily_value, days[index].toString(), number.format(count))
                Box(Modifier.weight(1f).fillMaxHeight().clickable { selected = index }
                    .semantics { contentDescription = description }, contentAlignment = Alignment.BottomCenter) {
                    val height = if (count > 0L) (count.toDouble() / maximum).toFloat().coerceAtLeast(0.015f) else 0.015f
                    val opacity = when {
                        index == selected -> 1f
                        count > 0L -> 0.58f
                        else -> 0.15f
                    }
                    Box(Modifier.fillMaxWidth().fillMaxHeight(height)
                        .background(accent.copy(alpha = opacity), RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)))
                }
            }
        }
        val dateFormat = DateTimeFormatter.ofPattern("M/d")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(days.first().format(dateFormat), style = MaterialTheme.typography.labelSmall)
            Text(days.last().format(dateFormat), style = MaterialTheme.typography.labelSmall)
        }
    }
}

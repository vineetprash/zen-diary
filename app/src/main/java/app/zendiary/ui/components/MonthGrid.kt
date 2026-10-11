package app.zendiary.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/** Real month calendar (locale-aware first weekday). Shared by Calendar and Cycle screens. */
@Composable
fun MonthGrid(
    month: YearMonth,
    onDayClick: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    selected: LocalDate? = null,
    cell: @Composable BoxScope.(LocalDate) -> Unit,
) {
    val first = WeekFields.of(Locale.getDefault()).firstDayOfWeek
    val today = LocalDate.now()
    val offset = (month.atDay(1).dayOfWeek.value - first.value + 7) % 7
    val rows = (offset + month.lengthOfMonth() + 6) / 7
    Column(modifier) {
        Row(Modifier.fillMaxWidth()) {
            for (i in 0..6) Text(
                first.plus(i.toLong()).getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                Modifier.weight(1f), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        for (r in 0 until rows) {
            Row(Modifier.fillMaxWidth()) {
                for (c in 0..6) {
                    val dayNum = r * 7 + c - offset + 1
                    if (dayNum < 1 || dayNum > month.lengthOfMonth()) { Spacer(Modifier.weight(1f).aspectRatio(0.8f)); continue }
                    val date = month.atDay(dayNum)
                    val future = date.isAfter(today)
                    val isSel = date == selected
                    Box(
                        Modifier.weight(1f).aspectRatio(0.8f).padding(2.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .then(if (isSel) Modifier.background(MaterialTheme.colorScheme.primaryContainer) else Modifier)
                            .then(if (date == today) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp)) else Modifier)
                            .clickable(enabled = !future) { onDayClick(date) }
                            .alpha(if (future) 0.35f else 1f),
                    ) {
                        Text("$dayNum", Modifier.align(Alignment.TopCenter).padding(top = 3.dp), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Box(Modifier.align(Alignment.Center).padding(top = 8.dp)) { cell(date) }
                    }
                }
            }
        }
    }
}

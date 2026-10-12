package com.chrisgoac.gymlog.progress

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.chrisgoac.gymlog.designsystem.chart.ChartMath
import com.chrisgoac.gymlog.designsystem.chart.ChartPoint
import com.chrisgoac.gymlog.designsystem.chart.ChartText
import com.chrisgoac.gymlog.designsystem.chart.ChartTrend
import com.chrisgoac.gymlog.feature.progress.R
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Spanish number and date formatting (the app is Spanish-only), independent of the device locale so output is deterministic. */
internal object ProgressFormat {
    private val ES: Locale = Locale.forLanguageTag("es-ES")
    private val SHORT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM", ES)

    /** "1,2" / "850": at most [decimals] decimals, comma separator, no thousands grouping. */
    fun number(value: Double, decimals: Int = 1): String =
        NumberFormat.getNumberInstance(ES).apply {
            maximumFractionDigits = decimals
            minimumFractionDigits = 0
            isGroupingUsed = false
            roundingMode = RoundingMode.HALF_UP // JS toFixed parity: 14.25 -> 14.3
        }.format(value)

    /** Volume as the PWA shows it: tonnes from 1000 ("1,2 t"), otherwise whole kg·rep ("850"). */
    fun volume(kgRep: Double): String = if (kgRep >= 1000) number(kgRep / 1000, 1) + " t" else number(Math.round(kgRep).toDouble(), 0)

    fun kg(value: Double): String = number(value, 1)

    /** Zero-padded "dd/MM" in the user's zone ("03/08"). */
    fun shortDate(millis: Long, zone: ZoneId): String = SHORT_DATE.format(Instant.ofEpochMilli(millis).atZone(zone))
}

@Composable
internal fun chartText(valueHeader: String = stringResource(R.string.progress_chart_col_value), xHeader: String = stringResource(R.string.progress_chart_col_date)) =
    ChartText(
        showTable = stringResource(R.string.progress_chart_show_table),
        hideTable = stringResource(R.string.progress_chart_hide_table),
        xHeader = xHeader,
        yHeader = valueHeader,
        empty = stringResource(R.string.progress_chart_empty),
    )

/** The chart's spoken summary (min / max / last / trend), built from the same points the canvas and table use. */
@Composable
internal fun chartSummary(title: String, points: List<ChartPoint>, format: (Double) -> String): String {
    val stats = ChartMath.stats(points) ?: return title
    if (stats.count == 1) return stringResource(R.string.progress_chart_summary_one, title, format(stats.last.value), stats.last.label)
    val trend = stringResource(
        when (stats.trend) {
            ChartTrend.Up -> R.string.progress_trend_up
            ChartTrend.Down -> R.string.progress_trend_down
            ChartTrend.Flat -> R.string.progress_trend_flat
        },
    )
    return stringResource(
        R.string.progress_chart_summary,
        title, stats.count,
        format(stats.min.value), stats.min.label,
        format(stats.max.value), stats.max.label,
        format(stats.last.value), stats.last.label,
        trend,
    )
}

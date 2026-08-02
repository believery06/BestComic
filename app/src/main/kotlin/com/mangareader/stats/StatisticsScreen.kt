package com.mangareader.stats

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mangareader.R
import com.mangareader.data.GoalHistoryEntry
import com.mangareader.data.ReadingGoals
import com.mangareader.data.ReadingStats
import com.mangareader.data.SettingsRepository
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * 阅读统计页面：展示累计页数、读完本数、连续阅读天数、阅读目标进度，
 * 以及按日/周/月聚合的阅读时长。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val settings = remember { SettingsRepository.getInstance(context) }
    val stats by settings.getReadingStats().collectAsStateWithLifecycle(initialValue = ReadingStats())
    val goals by settings.getReadingGoals().collectAsStateWithLifecycle(initialValue = ReadingGoals())
    val goalHistory by settings.getGoalHistory().collectAsStateWithLifecycle(initialValue = emptyList())
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.statistics_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = { shareStatistics(context, stats, goals) }) {
                        Icon(Icons.Default.Share, contentDescription = stringResource(R.string.statistics_share_title))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatCard(
                icon = Icons.AutoMirrored.Filled.MenuBook,
                contentDescription = stringResource(R.string.statistics_total_pages),
                label = stringResource(R.string.statistics_total_pages),
                value = stringResource(R.string.statistics_total_pages_value, stats.totalPagesRead)
            )
            StatCard(
                icon = Icons.Default.CalendarMonth,
                contentDescription = stringResource(R.string.statistics_comics_finished),
                label = stringResource(R.string.statistics_comics_finished),
                value = stringResource(R.string.statistics_comics_finished_value, stats.comicsFinished)
            )
            StatCard(
                icon = Icons.Default.Schedule,
                contentDescription = stringResource(R.string.statistics_streak_days),
                label = stringResource(R.string.statistics_streak_days),
                value = stringResource(R.string.statistics_streak_days_value, stats.streakDays())
            )
            StatCard(
                icon = Icons.Default.Today,
                contentDescription = stringResource(R.string.statistics_today_minutes),
                label = stringResource(R.string.statistics_today_minutes),
                value = stringResource(R.string.statistics_today_minutes_value, stats.todayMinutes())
            )
            StatCard(
                icon = Icons.Default.Today,
                contentDescription = stringResource(R.string.statistics_today_pages),
                label = stringResource(R.string.statistics_today_pages),
                value = stringResource(R.string.statistics_today_pages_value, stats.todayPages())
            )
            StatCard(
                icon = Icons.Default.Schedule,
                contentDescription = stringResource(R.string.statistics_total_minutes),
                label = stringResource(R.string.statistics_total_minutes),
                value = stringResource(R.string.statistics_total_minutes_value, stats.totalMinutesRead())
            )
            StatCard(
                icon = Icons.AutoMirrored.Filled.MenuBook,
                contentDescription = stringResource(R.string.statistics_avg_pages_per_hour),
                label = stringResource(R.string.statistics_avg_pages_per_hour),
                value = stringResource(R.string.statistics_avg_pages_per_hour_value, stats.averagePagesPerHour())
            )
            StatCard(
                icon = Icons.Default.CalendarMonth,
                contentDescription = stringResource(R.string.statistics_avg_minutes_per_day),
                label = stringResource(R.string.statistics_avg_minutes_per_day),
                value = stringResource(R.string.statistics_avg_minutes_per_day_value, stats.averageMinutesPerDay())
            )

            if (goals.dailyPages > 0 || goals.dailyMinutes > 0) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(stringResource(R.string.statistics_today_goals), style = MaterialTheme.typography.titleMedium)
                if (goals.dailyPages > 0) {
                    GoalProgressBar(
                        label = stringResource(R.string.statistics_goal_pages_label),
                        current = stats.todayPages(),
                        target = goals.dailyPages
                    )
                }
                if (goals.dailyMinutes > 0) {
                    GoalProgressBar(
                        label = stringResource(R.string.statistics_goal_minutes_label),
                        current = stats.todayMinutes(),
                        target = goals.dailyMinutes
                    )
                }

                GoalTrendSection(
                    history = goalHistory,
                    goals = goals
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text(stringResource(R.string.statistics_daily_minutes_title), style = MaterialTheme.typography.titleMedium)
            val daily = stats.dailyMinutes.toList().sortedByDescending { it.first }.take(14).reversed()
            val dailyMax = daily.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
            if (daily.isEmpty()) {
                Text(stringResource(R.string.statistics_no_data), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                daily.forEach { (date, minutes) ->
                    TimeBar(label = date, minutes = minutes, max = dailyMax)
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text(stringResource(R.string.statistics_weekly_trend_title), style = MaterialTheme.typography.titleMedium)
            val weekly = stats.weeklyMinutes(8)
            if (weekly.isEmpty()) {
                Text(stringResource(R.string.statistics_no_data), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                WeeklyTrendChart(
                    data = weekly,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .padding(vertical = 8.dp)
                )
                // 本周高亮数据
                val latest = weekly.lastOrNull()
                if (latest != null) {
                    Text(
                        text = stringResource(R.string.statistics_week_value, latest.second),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text(stringResource(R.string.statistics_monthly_title), style = MaterialTheme.typography.titleMedium)
            val monthly = stats.monthlyMonths(12)
            val monthlyMax = monthly.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
            if (monthly.isEmpty()) {
                Text(stringResource(R.string.statistics_no_data), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                monthly.forEach { (month, minutes) ->
                    TimeBar(label = month, minutes = minutes, max = monthlyMax)
                }
            }
        }
    }
}

@Composable
private fun StatCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    label: String,
    value: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = contentDescription, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                Text(value, style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

@Composable
private fun TimeBar(label: String, minutes: Int, max: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(80.dp)
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(18.dp)
                .padding(end = 8.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth((minutes.toFloat() / max).coerceIn(0f, 1f))
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                        MaterialTheme.shapes.small
                    )
            )
        }
        Text(
            text = stringResource(R.string.statistics_minutes_value, minutes),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(48.dp)
        )
    }
}

/**
 * 近 14 天目标达成趋势：每天用一个圆点表示是否完成当日目标。
 * 圆点颜色：达成=tertiary，部分达成=primary 半透明，未达成=surfaceVariant。
 */
@Composable
private fun GoalTrendSection(
    history: List<GoalHistoryEntry>,
    goals: ReadingGoals
) {
    if (goals.dailyPages <= 0 && goals.dailyMinutes <= 0) return

    val fmt = remember { DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.getDefault()) }
    val dayFmt = remember { DateTimeFormatter.ofPattern("E", Locale.getDefault()) }

    val days = remember {
        val today = LocalDate.now()
        (13 downTo 0).map { offset ->
            val d = today.minusDays(offset.toLong())
            fmt.format(d) to dayFmt.format(d)
        }
    }
    val historyMap = remember(history) { history.associateBy { it.date } }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
    ) {
        Text(
            stringResource(R.string.statistics_goal_trend_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            days.forEach { (date, label) ->
                val entry = historyMap[date]
                val pagesDone = goals.dailyPages > 0 && entry?.pagesAchieved == true
                val minutesDone = goals.dailyMinutes > 0 && entry?.minutesAchieved == true
                val achieved = when {
                    goals.dailyPages > 0 && goals.dailyMinutes > 0 -> pagesDone && minutesDone
                    goals.dailyPages > 0 -> pagesDone
                    else -> minutesDone
                }
                val partial = !achieved &&
                    ((goals.dailyPages > 0 && pagesDone) || (goals.dailyMinutes > 0 && minutesDone))

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(
                                color = when {
                                    achieved -> MaterialTheme.colorScheme.tertiary
                                    partial -> MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                                    else -> MaterialTheme.colorScheme.surfaceVariant
                                },
                                shape = CircleShape
                            )
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * 周阅读时长趋势图：使用 Canvas 绘制折线与半透明填充区域，
 * 不引入额外图表库，保持安装包精简。
 */
@Composable
private fun WeeklyTrendChart(
    data: List<Pair<String, Int>>,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val density = LocalDensity.current
    val labelTextSize = with(density) { 10.dp.toPx() }
    val axisTextSize = with(density) { 10.dp.toPx() }
    val lineStroke = with(density) { 2.dp.toPx() }
    val pointRadius = with(density) { 3.dp.toPx() }

    // 最多展示 8 周，不足则按实际数量
    val values = data.takeLast(8)
    if (values.size < 2) return

    val maxMinutes = values.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
    val maxIndex = values.indices.maxByOrNull { values[it].second } ?: 0
    val maxLabel = stringResource(R.string.statistics_weekly_chart_max_label, values[maxIndex].second)
    val labelFormatter = remember { DateTimeFormatter.ofPattern("MM-dd", Locale.getDefault()) }
    val parseFormatter = remember { DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.getDefault()) }
    val axisPaint = remember(onSurfaceVariant, axisTextSize) {
        android.graphics.Paint().apply {
            color = onSurfaceVariant.hashCode()
            textSize = axisTextSize
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        }
    }
    val labelPaint = remember(primary, labelTextSize) {
        android.graphics.Paint().apply {
            color = primary.hashCode()
            textSize = labelTextSize
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        }
    }

    Box(modifier = modifier) {
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val paddingStart = 4.dp.toPx()
            val paddingEnd = 4.dp.toPx()
            val paddingBottom = 18.dp.toPx()
            val paddingTop = 12.dp.toPx()
            val chartWidth = width - paddingStart - paddingEnd
            val chartHeight = height - paddingTop - paddingBottom

            val stepX = if (values.size > 1) chartWidth / (values.size - 1) else 0f

            // 网格横线
            val gridLines = 4
            for (i in 0..gridLines) {
                val y = paddingTop + chartHeight * i / gridLines
                drawLine(
                    color = surfaceVariant,
                    start = Offset(paddingStart, y),
                    end = Offset(width - paddingEnd, y),
                    strokeWidth = 1.dp.toPx()
                )
            }

            // 折线点坐标
            val points = values.mapIndexed { index, (_, minutes) ->
                val x = paddingStart + index * stepX
                val y = paddingTop + chartHeight * (1f - minutes.toFloat() / maxMinutes)
                Offset(x, y)
            }

            // 填充区域
            val fillPath = Path().apply {
                moveTo(points.first().x, height - paddingBottom)
                points.forEach { lineTo(it.x, it.y) }
                lineTo(points.last().x, height - paddingBottom)
                close()
            }
            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(primary.copy(alpha = 0.25f), primary.copy(alpha = 0.02f)),
                    startY = paddingTop,
                    endY = height - paddingBottom
                )
            )

            // 折线
            val linePath = Path().apply {
                moveTo(points.first().x, points.first().y)
                for (i in 1 until values.size) {
                    lineTo(points[i].x, points[i].y)
                }
            }
            drawPath(
                path = linePath,
                color = primary,
                style = Stroke(width = lineStroke, cap = StrokeCap.Round)
            )

            // 数据点
            points.forEach { point ->
                drawCircle(
                    color = primary,
                    radius = pointRadius,
                    center = point
                )
            }

            // X 轴日期标签（隔一个显示，避免拥挤）
            values.forEachIndexed { index, (monday, _) ->
                if (values.size <= 5 || index % 2 == 0) {
                    val label = runCatching {
                        val date = LocalDate.parse(monday, parseFormatter)
                        labelFormatter.format(date)
                    }.getOrDefault(monday)
                    drawIntoCanvas { canvas ->
                        canvas.nativeCanvas.drawText(
                            label,
                            points[index].x,
                            height - 4.dp.toPx(),
                            axisPaint
                        )
                    }
                }
            }

            // 最大值标注
            val maxPoint = points[maxIndex]
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawText(
                    maxLabel,
                    maxPoint.x,
                    maxPoint.y - 8.dp.toPx(),
                    labelPaint
                )
            }
        }
    }
}

@Composable
private fun GoalProgressBar(label: String, current: Int, target: Int) {
    val progress = (current.toFloat() / target).coerceIn(0f, 1f)
    val isCompleted = current >= target
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(48.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(12.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    MaterialTheme.shapes.small
                )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(progress)
                    .background(
                        if (isCompleted) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                        MaterialTheme.shapes.small
                    )
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = stringResource(R.string.statistics_goal_progress, current, target),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(64.dp),
            color = if (isCompleted) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * 将当前阅读统计生成纯文本报告，并通过系统分享面板发送。
 * 报告包含累计页数、读完本数、连续天数、今日/累计时长、平均速度及目标进度。
 */
private fun shareStatistics(context: android.content.Context, stats: ReadingStats, goals: ReadingGoals) {
    val report = buildString {
        appendLine(context.getString(R.string.statistics_share_header))
        appendLine()
        appendLine(context.getString(R.string.statistics_share_total_pages, stats.totalPagesRead))
        appendLine(context.getString(R.string.statistics_share_comics_finished, stats.comicsFinished))
        appendLine(context.getString(R.string.statistics_share_streak_days, stats.streakDays()))
        appendLine(context.getString(R.string.statistics_share_today_minutes, stats.todayMinutes()))
        appendLine(context.getString(R.string.statistics_share_today_pages, stats.todayPages()))
        appendLine(context.getString(R.string.statistics_share_total_minutes, stats.totalMinutesRead()))
        appendLine(context.getString(R.string.statistics_share_avg_speed, stats.averagePagesPerHour()))
        appendLine(context.getString(R.string.statistics_share_avg_daily, stats.averageMinutesPerDay()))
        if (goals.dailyPages > 0 || goals.dailyMinutes > 0) {
            appendLine()
            appendLine(context.getString(R.string.statistics_today_goals))
            if (goals.dailyPages > 0) {
                appendLine(context.getString(R.string.statistics_share_goal_pages, stats.todayPages(), goals.dailyPages))
            }
            if (goals.dailyMinutes > 0) {
                appendLine(context.getString(R.string.statistics_share_goal_minutes, stats.todayMinutes(), goals.dailyMinutes))
            }
        }
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.statistics_share_subject))
        putExtra(Intent.EXTRA_TEXT, report)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.statistics_share_chooser)))
    }
}

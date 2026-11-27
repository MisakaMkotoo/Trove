package com.exodidio.tune.ui.screens

import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.columnModel
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.cartesian.layer.ColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.component.rememberShapeComponent
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import com.patrykandpatrick.vico.compose.cartesian.marker.rememberDefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.marker.DefaultCartesianMarker
import com.patrykandpatrick.vico.compose.pie.PieChart
import com.patrykandpatrick.vico.compose.pie.PieChartHost
import com.patrykandpatrick.vico.compose.pie.PieSize
import com.patrykandpatrick.vico.compose.pie.data.PieChartModelProducer
import com.patrykandpatrick.vico.compose.pie.data.pieSeries
import com.patrykandpatrick.vico.compose.pie.rememberPieChart
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import com.exodidio.tune.R
import com.exodidio.tune.ui.theme.LocalTuneColors

private const val ChartAnimationMillis = 400

@Composable
internal fun InsightHeatmapGrid(
    state: InsightHeatmapState,
    selectedDate: String?,
    onSelectDate: (String) -> Unit,
    description: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalTuneColors.current
    val weeks = state.weeks.coerceAtLeast(1)
    val levelColors = listOf(
        colors.borderGlass.copy(alpha = 0.35f),
        colors.primary.copy(alpha = 0.25f),
        colors.primary.copy(alpha = 0.48f),
        colors.primary.copy(alpha = 0.74f),
        colors.primary,
    )
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val density = LocalDensity.current
            val gapDp = 3.dp
            val cellDp = ((maxWidth - gapDp * (weeks - 1)) / weeks).coerceIn(6.dp, 16.dp)
            val gridHeight = cellDp * 7 + gapDp * 6
            val cellPx = with(density) { cellDp.toPx() }
            val gapPx = with(density) { gapDp.toPx() }
            val stepPx = cellPx + gapPx
            val radiusPx = with(density) { 2.5.dp.toPx() }
            val strokePx = with(density) { 1.5.dp.toPx() }

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(gridHeight)
                    .semantics { contentDescription = description }
                    .pointerInput(state.cells, weeks, stepPx) {
                        detectTapGestures { offset ->
                            val col = (offset.x / stepPx).toInt().coerceIn(0, weeks - 1)
                            val row = (offset.y / stepPx).toInt().coerceIn(0, 6)
                            val index = col * 7 + row
                            val cell = state.cells.getOrNull(index)
                            if (cell != null && !cell.inFuture) {
                                onSelectDate(cell.dateText)
                            }
                        }
                    },
            ) {
                state.cells.forEachIndexed { index, cell ->
                    val col = index / 7
                    val row = index % 7
                    val topLeft = Offset(col * stepPx, row * stepPx)
                    val cellColor = if (cell.inFuture) {
                        colors.borderGlass.copy(alpha = 0.14f)
                    } else {
                        levelColors[cell.level.coerceIn(0, 4)]
                    }
                    drawRoundRect(
                        color = cellColor,
                        topLeft = topLeft,
                        size = Size(cellPx, cellPx),
                        cornerRadius = CornerRadius(radiusPx, radiusPx),
                    )
                    if (!cell.inFuture && cell.dateText == selectedDate) {
                        drawRoundRect(
                            color = colors.textMain,
                            topLeft = topLeft,
                            size = Size(cellPx, cellPx),
                            cornerRadius = CornerRadius(radiusPx, radiusPx),
                            style = Stroke(width = strokePx),
                        )
                    }
                }
            }
        }
        val selectedCell = selectedDate?.let { target -> state.cells.firstOrNull { it.dateText == target } }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val detailText = when {
                selectedCell == null -> stringResource(R.string.insight_heatmap_subtitle, state.activeDays)
                selectedCell.listenedSeconds > 0 || selectedCell.plays > 0 -> stringResource(
                    R.string.insight_heatmap_day_detail,
                    selectedCell.dateText,
                    formatDuration(selectedCell.listenedSeconds),
                    selectedCell.plays,
                )
                else -> stringResource(R.string.insight_heatmap_day_empty, selectedCell.dateText)
            }
            Text(
                text = detailText,
                style = MaterialTheme.typography.labelSmall,
                color = colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.insight_heatmap_less), style = MaterialTheme.typography.labelSmall, color = colors.textMuted)
                levelColors.forEach { shade ->
                    Box(
                        Modifier
                            .size(9.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(shade)
                            .border(0.5.dp, colors.borderGlass.copy(alpha = 0.2f), RoundedCornerShape(2.dp)),
                    )
                }
                Text(stringResource(R.string.insight_heatmap_more), style = MaterialTheme.typography.labelSmall, color = colors.textMuted)
            }
        }
    }
}

@Composable
internal fun InsightBarChart(points: List<InsightPoint>, description: String, modifier: Modifier = Modifier) {
    val colors = LocalTuneColors.current
    val modelProducer = remember { CartesianChartModelProducer() }
    val pointCount = points.size.coerceAtLeast(7)
    val column = rememberLineComponent(
        fill = Fill(Brush.verticalGradient(listOf(colors.primary, colors.primary.copy(alpha = .22f)))),
        thickness = (126f / pointCount).coerceIn(2f, 18f).dp,
        shape = RoundedCornerShape(6.dp),
    )
    val markerLabels = points.map { formatDuration(it.value) }
    val chart = rememberCartesianChart(
        rememberColumnCartesianLayer(
            columnProvider = ColumnCartesianLayer.ColumnProvider.series(column),
            columnCollectionSpacing = (84f / pointCount).coerceAtLeast(1f).dp,
        ),
        marker = rememberInsightMarker(markerLabels),
    )
    LaunchedEffect(points) {
        modelProducer.runTransaction { columnModel { series(points.map { it.value }) } }
    }
    Column(modifier) {
        CartesianChartHost(
            chart = chart,
            modelProducer = modelProducer,
            modifier = Modifier.fillMaxWidth().height(136.dp).semantics { contentDescription = description },
            animationSpec = tween(ChartAnimationMillis),
        )
        ChartEdgeLabels(points.firstOrNull()?.date.orEmpty(), points.lastOrNull()?.date.orEmpty())
    }
}

@Composable
internal fun InsightLineChart(points: List<InsightPoint>, description: String, modifier: Modifier = Modifier) {
    val colors = LocalTuneColors.current
    val modelProducer = remember { CartesianChartModelProducer() }
    val pointProvider = if (points.size == 1) {
        LineCartesianLayer.PointProvider.single(
            LineCartesianLayer.Point(rememberShapeComponent(Fill(colors.primary), CircleShape)),
        )
    } else null
    val line = LineCartesianLayer.rememberLine(
        fill = LineCartesianLayer.LineFill.single(Fill(colors.primary)),
        areaFill = LineCartesianLayer.AreaFill.single(
            Fill(Brush.verticalGradient(listOf(colors.primary.copy(alpha = .38f), colors.primary.copy(alpha = 0f)))),
        ),
        pointProvider = pointProvider,
        interpolator = LineCartesianLayer.Interpolator.catmullRom(),
    )
    val markerLabels = points.map { number(it.value) }
    val chart = rememberCartesianChart(
        rememberLineCartesianLayer(LineCartesianLayer.LineProvider.series(line)),
        marker = rememberInsightMarker(markerLabels),
    )
    LaunchedEffect(points) {
        modelProducer.runTransaction { lineModel { series(points.map { it.value }) } }
    }
    Column(modifier) {
        CartesianChartHost(
            chart = chart,
            modelProducer = modelProducer,
            scrollState = rememberVicoScrollState(scrollEnabled = false),
            modifier = Modifier.fillMaxWidth().height(136.dp).semantics { contentDescription = description },
            animationSpec = tween(ChartAnimationMillis),
        )
        ChartEdgeLabels(points.firstOrNull()?.date.orEmpty(), points.lastOrNull()?.date.orEmpty())
    }
}

@Composable
internal fun InsightDonut(
    values: List<Pair<Int, androidx.compose.ui.graphics.Color>>,
    centerText: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    val modelProducer = remember { PieChartModelProducer() }
    val chartValues = values.map { it.first } + listOfNotNull(values.singleOrNull()?.first?.times(0.0001f))
    val chart = rememberPieChart(
        sliceProvider = PieChart.SliceProvider.series(
            values.map { (_, color) -> PieChart.Slice(Fill(color)) } +
                listOfNotNull(values.singleOrNull()?.let { PieChart.Slice(Fill(Color.Transparent)) }),
        ),
        outerSize = PieSize.Outer.fixed(120.dp),
        innerSize = PieSize.Inner.fixed(82.dp),
    )
    LaunchedEffect(values) {
        modelProducer.runTransaction { pieSeries { series(chartValues) } }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        PieChartHost(
            chart = chart,
            modelProducer = modelProducer,
            modifier = Modifier.height(132.dp).fillMaxWidth().semantics { contentDescription = description },
            animationSpec = tween(ChartAnimationMillis),
        )
        Text(
            centerText,
            color = LocalTuneColors.current.textMain,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ChartEdgeLabels(first: String, last: String) {
    val colors = LocalTuneColors.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(first, style = MaterialTheme.typography.labelSmall, color = colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(last, style = MaterialTheme.typography.labelSmall, color = colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun rememberInsightMarker(labels: List<String>) = rememberDefaultCartesianMarker(
    label = rememberTextComponent(TextStyle(color = LocalTuneColors.current.textMain, fontSize = 12.sp)),
    valueFormatter = remember(labels) {
        DefaultCartesianMarker.ValueFormatter { _, targets -> labels.getOrNull(targets.firstOrNull()?.x?.toInt() ?: -1).orEmpty() }
    },
)

package io.hydrion.hub.ui.messages

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.axis.axisLabelComponent
import com.patrykandpatrick.vico.compose.axis.horizontal.rememberBottomAxis
import com.patrykandpatrick.vico.compose.axis.vertical.rememberStartAxis
import com.patrykandpatrick.vico.compose.chart.Chart
import com.patrykandpatrick.vico.compose.chart.column.columnChart
import com.patrykandpatrick.vico.compose.chart.line.lineChart
import com.patrykandpatrick.vico.compose.chart.line.lineSpec
import com.patrykandpatrick.vico.compose.chart.scroll.rememberChartScrollSpec
import com.patrykandpatrick.vico.compose.component.lineComponent
import com.patrykandpatrick.vico.compose.component.shapeComponent
import com.patrykandpatrick.vico.core.axis.AxisItemPlacer
import com.patrykandpatrick.vico.core.chart.values.AxisValuesOverrider
import io.hydrion.hub.R
import io.hydrion.hub.ui.theme.HubShapes
import java.io.IOException
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MessageChartCard(data: MessageChart) {
    var table by rememberSaveable { mutableStateOf(false) }
    var export by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val csv = export
        export = null
        if (uri != null && csv != null) scope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    val stream = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException()
                    stream.bufferedWriter(Charsets.UTF_8).use { it.write(csv) }
                }
                R.string.turn_chart_export_saved
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { R.string.turn_chart_export_failed }
            withContext(Dispatchers.Main.immediate) { Toast.makeText(context, result, Toast.LENGTH_SHORT).show() }
        }
    }
    val colors = MaterialTheme.colorScheme
    val defaults = listOf(colors.primary, colors.tertiary, colors.secondary, Color(0xff639b72),
        Color(0xffd8893a), Color(0xff748bd4), Color(0xffc66d8f), Color(0xff5597a1))
    val inks = data.series.mapIndexed { index, s -> s.color?.let { Color(android.graphics.Color.parseColor(it)) } ?: defaults[index] }
    val title = data.title ?: stringResource(R.string.turn_chart)
    Surface(Modifier.fillMaxWidth().testTag("message-chart"), shape = HubShapes.Small,
        color = colors.surfaceContainer) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                TextButton({ table = !table }) { Text(stringResource(if (table) R.string.turn_chart_plot else R.string.turn_chart_table)) }
                IconButton({
                    export = data.csv()
                    try {
                        saver.launch("HydrionHub-chart.csv")
                    } catch (_: ActivityNotFoundException) {
                        export = null
                        Toast.makeText(context, R.string.turn_chart_export_failed, Toast.LENGTH_SHORT).show()
                    }
                }, enabled = export == null) { Icon(Icons.Outlined.Download, stringResource(R.string.turn_chart_export)) }
            }
            if (table) ChartTable(data)
            else {
                ChartPlot(data, inks, title)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    data.series.forEachIndexed { index, line ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Spacer(Modifier.size(8.dp).background(inks[index], CircleShape))
                            Text(line.name, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChartPlot(data: MessageChart, colors: List<Color>, title: String) {
    val model = remember(data) { data.plotModel() }
    val singleX = model.minX == model.maxX
    val padding = maxOf(1f, abs(model.minX) * .01f)
    val limits = remember(model) { if (singleX) AxisValuesOverrider.fixed(minX = model.minX - padding, maxX = model.maxX + padding) else null }
    val numeric = data.categories.isEmpty()
    // Fixed tick spacing also bounds the number of ticks for irregular, closely spaced samples.
    val tick = when {
        !numeric -> 1f
        singleX -> padding
        else -> maxOf((model.maxX - model.minX) / 4f, Math.ulp(maxOf(abs(model.minX), abs(model.maxX))))
    }
    val plot = if (data.kind == ChartKind.Bar) columnChart(columns = colors.map { lineComponent(color = it, thickness = 8.dp) },
        axisValuesOverrider = limits)
    else lineChart(lines = colors.map { ink ->
        lineSpec(lineColor = if (data.kind == ChartKind.Scatter) Color.Transparent else ink, lineBackgroundShader = null,
            point = shapeComponent(shape = CircleShape, color = ink), pointSize = if (data.kind == ChartKind.Scatter) 7.dp else 5.dp)
    }, axisValuesOverrider = limits)
    val label = axisLabelComponent(color = MaterialTheme.colorScheme.onSurfaceVariant)
    Chart(chart = plot, model = model,
        modifier = Modifier.fillMaxWidth().height(190.dp).testTag("message-chart-plot").semantics { contentDescription = title },
        getXStep = { tick }, chartScrollSpec = rememberChartScrollSpec(isScrollEnabled = false), isZoomEnabled = false,
        startAxis = rememberStartAxis(label = label, title = data.yTitle, titleComponent = label),
        bottomAxis = rememberBottomAxis(label = label, title = data.xTitle, titleComponent = label,
            itemPlacer = AxisItemPlacer.Horizontal.default(spacing = maxOf(1, (data.categories.size + 4) / 5)),
            valueFormatter = { value, _ ->
                if (numeric) value.toString().removeSuffix(".0") else data.categories.getOrNull(value.roundToInt()).orEmpty()
            }))
}

@Composable
private fun ChartTable(data: MessageChart) {
    Column(Modifier.fillMaxWidth().testTag("message-chart-table")) {
        TableLine(stringResource(R.string.turn_chart_series), data.xTitle ?: "X", data.yTitle ?: "Y")
        HorizontalDivider()
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 230.dp)) {
            for (line in data.series) items(line.points) { point ->
                TableLine(line.name, data.xText(point), chartNumber(point.y))
            }
        }
    }
}

@Composable
private fun TableLine(name: String, x: String, y: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (text in listOf(name, x, y)) Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
    }
}

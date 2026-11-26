package io.hydrion.hub.ui.messages

import com.patrykandpatrick.vico.core.entry.FloatEntry
import com.patrykandpatrick.vico.core.entry.entryModelOf
import io.hydrion.hub.json.JsonSyntax
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.math.BigDecimal
import kotlin.math.abs

internal enum class ChartKind { Line, Bar, Scatter }

internal data class ChartPoint(val x: Double, val y: Double)
internal data class ChartSeries(val name: String, val color: String?, val points: List<ChartPoint>)
internal class MessageChart(
    val kind: ChartKind,
    val title: String?,
    val xTitle: String?,
    val yTitle: String?,
    val categories: List<String>,
    val series: List<ChartSeries>
) {
    // Vico traverses points in X order. Keep source order for the table and export.
    fun plotModel() = entryModelOf(*series.map { line ->
        line.points.sortedBy { it.x }.map { FloatEntry(it.x.toFloat(), it.y.toFloat()) }
    }.toTypedArray())

    fun xText(point: ChartPoint): String = categories.getOrNull(point.x.toInt()) ?: chartNumber(point.x)

    fun csv(): String = buildString {
        append("series,").append(csvText(xTitle ?: "x")).append(',').append(csvText(yTitle ?: "y")).append("\r\n")
        for (line in series) for (point in line.points) {
            append(csvText(line.name)).append(',')
            append(if (categories.isEmpty()) chartNumber(point.x) else csvText(xText(point)))
            append(',').append(chartNumber(point.y)).append("\r\n")
        }
    }
}

internal fun chartNumber(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

private fun csvText(value: String): String {
    val first = value.trimStart().firstOrNull()
    val cell = if (first in listOf('=', '+', '-', '@') || value.firstOrNull() == '\t') "'$value" else value
    return "\"" + cell.replace("\"", "\"\"") + "\""
}

private fun invalidChart(): Nothing = throw JSONException("Unsupported chart")
private fun label(value: Any?): String = (value as? String)?.takeIf {
    it.isNotBlank() && it.length <= 120 && it.none { c -> c.isISOControl() }
} ?: invalidChart()

private fun number(value: Any?): Double {
    val n = (value as? Number)?.toDouble() ?: invalidChart()
    if (!n.isFinite() || (n != 0.0 && abs(n) !in 1e-12..1e12)) invalidChart()
    return n
}

private fun JSONObject.only(vararg fields: String): JSONObject {
    if (keys().asSequence().any { it !in fields }) invalidChart()
    return this
}

private fun JSONObject.optionalLabel(key: String): String? = if (has(key)) label(get(key)) else null
private fun JSONObject.axis(key: String): JSONObject = if (has(key)) getJSONObject(key) else JSONObject()
private fun JSONArray.bounded(): IntRange {
    if (length() !in 1..256) invalidChart()
    return 0 until length()
}

/** Called only for closed, explicitly named chart fences in a settled assistant reply. */
internal fun messageChart(source: String): MessageChart? {
    if (source.length !in 2..32768) return null
    return try {
        JsonSyntax(source, ::invalidChart).check()
        val spec = JSONObject(source).only("style", "title", "x_axis", "y_axis", "series")
        val kind = when (spec.get("style")) {
            "line" -> ChartKind.Line
            "bar" -> ChartKind.Bar
            "scatter" -> ChartKind.Scatter
            else -> invalidChart()
        }
        val xAxis = spec.axis("x_axis").only("title", "data")
        val yAxis = spec.axis("y_axis").only("title")
        val axis = if (xAxis.has("data")) xAxis.getJSONArray("data").let { rows -> rows.bounded().map { rows.get(it) } }
            else emptyList()
        val categories = if (axis.firstOrNull() is String) axis.map(::label) else emptyList()
        val xs = if (categories.isEmpty()) axis.map(::number) else categories.indices.map(Int::toDouble)
        val sourceSeries = spec.getJSONArray("series")
        if (sourceSeries.length() !in 1..8) invalidChart()
        var total = 0
        val series = (0 until sourceSeries.length()).map { i ->
            val line = sourceSeries.getJSONObject(i).only("name", "color", "points", "values")
            val points = when {
                line.has("points") && !line.has("values") && axis.isEmpty() -> {
                    val list = line.getJSONArray("points")
                    list.bounded().map { j -> list.getJSONObject(j).only("x", "y").let { ChartPoint(number(it.get("x")), number(it.get("y"))) } }
                }
                line.has("values") && !line.has("points") && kind != ChartKind.Scatter && xs.isNotEmpty() -> {
                    val list = line.getJSONArray("values")
                    if (list.length() != xs.size) invalidChart()
                    xs.mapIndexed { j, x -> ChartPoint(x, number(list.get(j))) }
                }
                else -> invalidChart()
            }
            total += points.size
            if (total > 1024) invalidChart()
            val color = line.optionalLabel("color")?.also { if (!hexColor.matches(it)) invalidChart() }
            ChartSeries(label(line.get("name")), color, points)
        }
        // Distinct supplied coordinates must not silently coalesce in Vico's Float model.
        val coordinates = series.flatMap { it.points }.map { it.x }.distinct()
        if (coordinates.map { it.toFloat() }.distinct().size != coordinates.size) invalidChart()
        MessageChart(kind, spec.optionalLabel("title"), xAxis.optionalLabel("title"), yAxis.optionalLabel("title"),
            categories, series)
    } catch (_: JSONException) { null }
}

private val hexColor = Regex("#[0-9a-fA-F]{6}")

package io.hydrion.hub.ui.messages

import org.junit.Assert.*
import org.junit.Test

class MessageChartTest {
    private val scatter = """{"style":"scatter","series":[{"name":"A","points":[{"x":10,"y":4},{"x":1,"y":2},{"x":3,"y":6}]}]}"""

    @Test fun numericPointsKeepTheirSourceCoordinatesAndExportOrder() {
        val chart = requireNotNull(messageChart(scatter))
        assertEquals(listOf(1f to 2f, 3f to 6f, 10f to 4f),
            chart.plotModel().entries.single().map { it.x to it.y })
        assertEquals("series,\"x\",\"y\"\r\n\"A\",10,4\r\n\"A\",1,2\r\n\"A\",3,6\r\n", chart.csv())
    }

    @Test fun labeledSeriesUseTheRecordedValuesAndEscapedCsvColumns() {
        val chart = requireNotNull(messageChart("""{"style":"bar","x_axis":{"title":"Plot","data":["April, west","May"]},"series":[{"name":"=Name","values":[-2,0]},{"name":"Bed \"B\"","color":"#226688","values":[1.5,4]}]}"""))
        assertEquals("April, west", chart.xText(chart.series.first().points.first()))
        assertEquals("series,\"Plot\",\"y\"\r\n\"'=Name\",\"April, west\",-2\r\n\"'=Name\",\"May\",0\r\n" +
            "\"Bed \"\"B\"\"\",\"April, west\",1.5\r\n\"Bed \"\"B\"\"\",\"May\",4\r\n", chart.csv())
    }

    @Test fun onlyClosedExplicitChartBlocksRenderAndSourceIsKeptForEveryOtherCase() {
        fun plan(language: String, suffix: String) = richMessagePlan(listOf(ReplyBlock.Body("~~~$language\n$scatter\n$suffix")))
        assertNull(plan("json", "~~~"))
        assertNull(plan("", "~~~"))
        assertNull(plan("chart", ""))
        assertNull(plan("chart", "~~~~ info"))
        val rich = requireNotNull(plan("chart", "~~~"))
        assertTrue(rich.items.isEmpty())
        assertTrue(rich.bodies.getValue(0).getValue(0) is RichFragment.Chart)
        listOf("", "{style:'line'}", scatter.replace("scatter", "pie"), scatter.replace("\"x\":1,", ""),
            scatter.replace("10,\"y\"", "1e999,\"y\""), scatter.replace("\"y\":2", "\"y\":\"2\""),
            """{"style":"scatter","series":[{"name":"A","values":[2,3]}],"x_axis":{"data":[1,3]}}""",
            """{"style":"line","series":[{"name":"A","values":[2]}],"x_axis":{"data":["one","two"]}}""",
            scatter.replace("\"style\"", "\"scale\":\"log\",\"style\""),
            " ".repeat(33000) + scatter).forEach { assertNull(messageChart(it)) }
    }

    @Test fun chartAndMessageBudgetsLeaveAdditionalFencesAsOrdinaryCode() {
        fun fence(json: String) = "~~~chart\n$json\n~~~"
        val items = (0..256).joinToString(",") { """{"x":$it,"y":0}""" }
        assertNull(messageChart("""{"style":"scatter","series":[{"name":"A","points":[$items]}]}"""))
        val reply = List(9) { fence(scatter) }.joinToString("\n")
        val result = requireNotNull(richMessagePlan(listOf(ReplyBlock.Body(reply))))
        assertEquals((0..7).toSet(), result.bodies.getValue(0).keys)
        assertEquals(9, markdownBlocks(reply).size)
    }
}

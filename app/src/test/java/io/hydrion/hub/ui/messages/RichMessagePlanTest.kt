package io.hydrion.hub.ui.messages

import org.junit.Assert.*
import org.junit.Test

class RichMessagePlanTest {
    private fun plan(text: String) = richMessagePlan(listOf(ReplyBlock.Body(text)))

    @Test fun onlyClosedFormulasAndNamedDiagramFencesEnterTheRenderer() {
        val input = "Price \$5 and \$10. Use ``\$x\$`` or \\\$8. Real \\(a_i\\), \$z\$." +
            "\n\n~~~json\n{\"text\":\"\$z\$\"}\n~~~\n~~~mermaid\nflowchart LR"
        assertEquals(listOf(RenderItem("inline", "a_i"), RenderItem("inline", "z")), plan(input)?.items)
        assertNull(plan("Plain text \\(not finished or \$2.00"))
        assertNull(plan("~~~mermaid\nflowchart LR\nA --> B\n~~~~ info"))
    }

    @Test fun equationSlotsAndReferencesExtendAcrossToolGroupsInsideOneReply() {
        val parts = listOf(ReplyBlock.Body("See \\(\\eqref{eq:q}\\)."), ReplyBlock.Chain(emptyList()),
            ReplyBlock.Body("\\[q=1\\label{eq:q}\\]\n\n| Name | Value |\n| --- | --- |\n| Pair | \$2\$ |"))
        val result = requireNotNull(richMessagePlan(parts))
        assertEquals(listOf("inline", "display", "inline"), result.items.map { it.kind })
        assertEquals(setOf(0, 2), result.bodies.keys)
        assertEquals(2, (result.bodies.getValue(2).getValue(1) as RichFragment.Table).rows.last().last().spans.single().slot)
        assertEquals(0, (plan("\\(p\\)")!!.bodies.getValue(0).getValue(0) as RichFragment.Formula).line.spans.single().slot)
    }

    @Test fun overBudgetRegionsKeepTheirSource() {
        val limited = requireNotNull(plan(List(52) { "\\(x\\)" }.joinToString(" ")))
        assertEquals(48, limited.items.size)
        val line = (limited.bodies.getValue(0).getValue(0) as RichFragment.Formula).line
        assertEquals(-1, line.spans.last().slot)
        assertEquals("\\(x\\)", line.text.substring(line.spans.last().start, line.spans.last().end))
        assertNull(plan(" ".repeat(96000) + "\\(p\\)"))
    }
}

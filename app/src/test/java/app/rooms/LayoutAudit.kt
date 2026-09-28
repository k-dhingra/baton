package app.rooms

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult

/**
 * Measurable UI defects, found from the semantics tree rather than by eye:
 * truncated/ellipsised text, text overflowing its box, small touch targets,
 * clickable targets overlapping each other, and content outside the screen width.
 */
object LayoutAudit {
    fun run(rule: ComposeContentTestRule): List<String> {
        val root = rule.onRoot(useUnmergedTree = true).fetchSemanticsNode()
        val density = rule.density.density
        val screen = root.boundsInRoot
        val all = mutableListOf<SemanticsNode>()
        fun walk(n: SemanticsNode) { all += n; n.children.forEach(::walk) }
        walk(root)
        val out = mutableListOf<String>()

        all.forEach { n ->
            val text = n.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } ?: return@forEach
            val results = mutableListOf<TextLayoutResult>()
            n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
            val layout = results.firstOrNull() ?: return@forEach
            val ellipsised = (0 until layout.lineCount).any { layout.isLineEllipsized(it) }
            if (ellipsised) out += "TRUNCATED \"${text.take(60)}\" (${layout.lineCount} line(s))"
            val b = n.boundsInRoot
            if (b.width > 0 && (b.right > screen.right + 1 || b.left < screen.left - 1)) out += "OFFSCREEN \"${text.take(40)}\" $b"
        }

        val clickables = all.filter { it.config.getOrNull(SemanticsActions.OnClick) != null && it.boundsInRoot.width > 0 && it.boundsInRoot.height > 0 && it.boundsInRoot.bottom <= screen.bottom - 140 * density && it.boundsInRoot.top >= screen.top }
        clickables.forEach { n ->
            val b = n.boundsInRoot
            val w = b.width / density; val h = b.height / density
            if (w < 47.5f || h < 47.5f) out += "SMALL TARGET ${label(n)} ${w.toInt()}x${h.toInt()}dp"
        }
        for (i in clickables.indices) for (j in i + 1 until clickables.size) {
            val a = clickables[i]; val c = clickables[j]
            if (isAncestor(a, c) || isAncestor(c, a)) continue
            val overlap = a.boundsInRoot.intersect(c.boundsInRoot)
            if (overlap.width > 2 && overlap.height > 2) out += "OVERLAP ${label(a)} x ${label(c)}"
        }
        return out.distinct()
    }

    private fun isAncestor(a: SemanticsNode, b: SemanticsNode): Boolean {
        var p = b.parent
        while (p != null) { if (p.id == a.id) return true; p = p.parent }
        return false
    }

    private fun label(n: SemanticsNode): String {
        val d = n.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
        val t = n.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text
        fun firstText(x: SemanticsNode): String? = x.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text ?: x.children.firstNotNullOfOrNull(::firstText)
        return "\"" + (d ?: t ?: firstText(n) ?: "#${n.id}").take(40) + "\""
    }

    @Suppress("unused") private fun Rect.str() = "(${left.toInt()},${top.toInt()})-(${right.toInt()},${bottom.toInt()})"
}

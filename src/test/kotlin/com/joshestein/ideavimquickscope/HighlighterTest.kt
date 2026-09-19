package com.joshestein.ideavimquickscope

import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.maddyhome.idea.vim.VimPlugin
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimString
import java.awt.Color
import java.awt.Font

/** Tests for [Highlighter]: markup model integration and colour configuration. */
class HighlighterTest : QuickscopeTestBase() {

    private fun rangeHighlighters(): List<RangeHighlighter> =
        myFixture.editor.markupModel.allHighlighters
            .filter { it.layer == HighlighterLayer.SELECTION }
            .sortedBy { it.startOffset }

    private fun setColor(variable: String, value: String) {
        VimPlugin.getVariableService().storeGlobalVariable(variable, VimString(value))
    }

    private fun themeColor(): Color =
        myFixture.editor.colorsScheme.getAttributes(EditorColors.REFERENCE_HYPERLINK_COLOR)?.foregroundColor
            ?: EditorColors.REFERENCE_HYPERLINK_COLOR.defaultAttributes.foregroundColor

    private fun foregroundColors(): List<Color> = rangeHighlighters().map { it.getTextAttributes(null)!!.foregroundColor }

    fun `test addHighlights adds one single character range per highlight`() {
        val editor = configure("abc def ghi")
        val highlighter = Highlighter(editor)

        highlighter.addHighlights(listOf(Highlight(4, true), Highlight(8, false)))

        val ranges = rangeHighlighters()
        assertEquals(2, ranges.size)
        assertEquals(4, ranges[0].startOffset)
        assertEquals(5, ranges[0].endOffset)
        assertEquals(8, ranges[1].startOffset)
        assertEquals(9, ranges[1].endOffset)
        ranges.forEach { assertEquals(HighlighterTargetArea.EXACT_RANGE, it.targetArea) }
    }

    fun `test primary highlight is bold with a bold underscore`() {
        val editor = configure("abc def")
        Highlighter(editor).addHighlights(listOf(Highlight(4, true)))

        val attributes = rangeHighlighters().single().getTextAttributes(null)!!
        assertEquals(Font.BOLD, attributes.fontType)
        assertEquals(EffectType.BOLD_LINE_UNDERSCORE, attributes.effectType)
        assertNull(attributes.backgroundColor)
        assertEquals(attributes.foregroundColor, attributes.effectColor)
    }

    fun `test secondary highlight is plain with a thin underscore`() {
        val editor = configure("abc def")
        Highlighter(editor).addHighlights(listOf(Highlight(4, false)))

        val attributes = rangeHighlighters().single().getTextAttributes(null)!!
        assertEquals(Font.PLAIN, attributes.fontType)
        assertEquals(EffectType.LINE_UNDERSCORE, attributes.effectType)
        assertNull(attributes.backgroundColor)
        assertEquals(attributes.foregroundColor, attributes.effectColor)
    }

    fun `test removeHighlights removes everything that was added`() {
        val editor = configure("abc def ghi")
        val highlighter = Highlighter(editor)
        highlighter.addHighlights(listOf(Highlight(4, true), Highlight(8, false)))
        assertEquals(2, rangeHighlighters().size)

        highlighter.removeHighlights()

        assertEquals(emptyList<RangeHighlighter>(), rangeHighlighters())
    }

    fun `test removeHighlights is safe to call repeatedly and when empty`() {
        val editor = configure("abc def")
        val highlighter = Highlighter(editor)

        highlighter.removeHighlights()
        highlighter.addHighlights(listOf(Highlight(4, true)))
        highlighter.removeHighlights()
        highlighter.removeHighlights()

        assertEquals(emptyList<RangeHighlighter>(), rangeHighlighters())
    }

    fun `test addHighlights accumulates until removed`() {
        val editor = configure("abc def ghi")
        val highlighter = Highlighter(editor)

        highlighter.addHighlights(listOf(Highlight(4, true)))
        highlighter.addHighlights(listOf(Highlight(8, true)))
        assertEquals(2, rangeHighlighters().size)

        highlighter.removeHighlights()
        assertEquals(0, rangeHighlighters().size)
    }

    fun `test default colours come from the editor colour scheme`() {
        val editor = configure("abc def")
        Highlighter(editor).addHighlights(listOf(Highlight(4, true)))

        assertEquals(themeColor(), foregroundColors().single())
    }

    fun `test default secondary colour differs from primary colour`() {
        val editor = configure("abc def ghi")
        Highlighter(editor).addHighlights(listOf(Highlight(4, true), Highlight(8, false)))

        val (primary, secondary) = foregroundColors()
        assertFalse(primary == secondary)
    }

    fun `test qs_primary_color overrides the primary colour`() {
        setColor("qs_primary_color", "#ff0000")
        val editor = configure("abc def")
        Highlighter(editor).addHighlights(listOf(Highlight(4, true)))

        assertEquals(Color(0xff, 0x00, 0x00), foregroundColors().single())
    }

    fun `test qs_secondary_color overrides the secondary colour`() {
        setColor("qs_secondary_color", "#00ff00")
        val editor = configure("abc def")
        Highlighter(editor).addHighlights(listOf(Highlight(4, false)))

        assertEquals(Color(0x00, 0xff, 0x00), foregroundColors().single())
    }

    fun `test qs_primary_color does not affect the secondary colour`() {
        setColor("qs_primary_color", "#ff0000")
        val editor = configure("abc def")
        Highlighter(editor).addHighlights(listOf(Highlight(4, false)))

        assertFalse(Color(0xff, 0x00, 0x00) == foregroundColors().single())
    }

    fun `test invalid colour string falls back to the theme colour`() {
        setColor("qs_primary_color", "not-a-colour")
        setColor("qs_secondary_color", "#zzzzzz")
        val editor = configure("abc def ghi")

        Highlighter(editor).addHighlights(listOf(Highlight(4, true), Highlight(8, false)))

        val (primary, secondary) = foregroundColors()
        assertEquals(themeColor(), primary)
        assertFalse(primary == secondary)
    }

    fun `test updateHighlighterColors removes highlights and re-reads colours`() {
        val editor = configure("abc def")
        val highlighter = Highlighter(editor)
        highlighter.addHighlights(listOf(Highlight(4, true)))
        assertEquals(1, rangeHighlighters().size)

        setColor("qs_primary_color", "#0000ff")
        highlighter.updateHighlighterColors()
        assertEquals(0, rangeHighlighters().size)

        highlighter.addHighlights(listOf(Highlight(4, true)))
        assertEquals(Color(0x00, 0x00, 0xff), foregroundColors().single())
    }

    fun `test colours are read once when the highlighter is created`() {
        val editor = configure("abc def")
        val highlighter = Highlighter(editor)

        setColor("qs_primary_color", "#0000ff")
        highlighter.addHighlights(listOf(Highlight(4, true)))

        // The variable changed after construction, so the old colour is still in use until updateHighlighterColors.
        assertEquals(themeColor(), foregroundColors().single())
    }
}

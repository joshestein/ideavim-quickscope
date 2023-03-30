package com.joshestein.ideavimquickscope

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.markup.*
import com.maddyhome.idea.vim.VimPlugin
import java.awt.Color
import java.awt.Font

private const val PRIMARY_COLOR_VARIABLE = "qs_primary_color"
private const val SECONDARY_COLOR_VARIABLE = "qs_secondary_color"

private val DEFAULT_PRIMARY_COLOR = EditorColors.REFERENCE_HYPERLINK_COLOR.defaultAttributes.foregroundColor
private val DEFAULT_SECONDARY_COLOR = DEFAULT_PRIMARY_COLOR.brighter()

data class Highlight(val position: Int, val primary: Boolean)

class Highlighter(var editor: Editor) {
    private var primaryColor: Color? = try {
        Color.decode(VimPlugin.getVariableService().getGlobalVariableValue(PRIMARY_COLOR_VARIABLE).toString())
    } catch (e: Exception) {
        DEFAULT_PRIMARY_COLOR
    }
    private var secondaryColor: Color? = try {
        Color.decode(VimPlugin.getVariableService().getGlobalVariableValue(SECONDARY_COLOR_VARIABLE).toString())
    } catch (e: Exception) {
        DEFAULT_SECONDARY_COLOR
    }
    private val highlighters: MutableSet<RangeHighlighter> = mutableSetOf()

    fun updateEditor(editor: Editor) {
        this.removeHighlights()
        this.editor = editor
    }

    fun addHighlights(highlights: List<Highlight>) {
        highlights.forEach { highlight ->
            highlighters.add(
                this.editor.markupModel.addRangeHighlighter(
                    highlight.position,
                    highlight.position + 1,
                    HighlighterLayer.SELECTION,
                    getHighlightTextAttributes(highlight.primary),
                    HighlighterTargetArea.EXACT_RANGE
                )
            )
        }
    }

    private fun getHighlightTextAttributes(primary: Boolean) = TextAttributes(
        if (primary) primaryColor else secondaryColor,
        null,
        if (primary) primaryColor else secondaryColor,
        EffectType.LINE_UNDERSCORE,
        Font.PLAIN
    )

    fun removeHighlights() {
        highlighters.forEach { highlighter ->
            this.editor.markupModel.removeHighlighter(highlighter)
        }
        highlighters.clear()
    }
}
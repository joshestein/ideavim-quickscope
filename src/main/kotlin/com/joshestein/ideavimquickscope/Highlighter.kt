package com.joshestein.ideavimquickscope

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.markup.*
import com.maddyhome.idea.vim.VimPlugin
import java.awt.Color
import java.awt.Font

private const val PRIMARY_COLOR_VARIABLE = "qs_primary_color"
private const val SECONDARY_COLOR_VARIABLE = "qs_secondary_color"

data class Highlight(val position: Int, val primary: Boolean)

class Highlighter(var editor: Editor) {
    private val primaryColor: Color = try {
        Color.decode(VimPlugin.getVariableService().getGlobalVariableValue(PRIMARY_COLOR_VARIABLE).toString())
    } catch (e: Exception) {
        editor.colorsScheme.getAttributes(EditorColors.REFERENCE_HYPERLINK_COLOR)?.foregroundColor
            ?: EditorColors.REFERENCE_HYPERLINK_COLOR.defaultAttributes.foregroundColor
    }
    private val secondaryColor: Color = try {
        Color.decode(VimPlugin.getVariableService().getGlobalVariableValue(SECONDARY_COLOR_VARIABLE).toString())
    } catch (e: Exception) {
        (editor.colorsScheme.getAttributes(EditorColors.REFERENCE_HYPERLINK_COLOR)?.foregroundColor
            ?: EditorColors.REFERENCE_HYPERLINK_COLOR.defaultAttributes.foregroundColor).let { color ->
            color.brighter().takeIf { it != color } ?: color.darker()
        }
    }
    private val highlighters: MutableSet<RangeHighlighter> = mutableSetOf()

    fun updateEditor(editor: Editor) {
        this.removeHighlights()
        this.editor = editor
    }

    fun addHighlights(highlights: List<Highlight>) {
        val primary = getPrimaryHighlightTextAttributes()
        val secondary = getSecondaryHighlightTextAttributes()

        highlights.forEach { highlight ->
            highlighters.add(
                this.editor.markupModel.addRangeHighlighter(
                    highlight.position,
                    highlight.position + 1,
                    HighlighterLayer.SELECTION,
                    if (highlight.primary) primary else secondary,
                    HighlighterTargetArea.EXACT_RANGE
                )
            )
        }
    }

    private fun getPrimaryHighlightTextAttributes(): TextAttributes {
        return TextAttributes(primaryColor, null, primaryColor, EffectType.BOLD_LINE_UNDERSCORE, Font.BOLD)
    }

    private fun getSecondaryHighlightTextAttributes(): TextAttributes {
        return TextAttributes(secondaryColor, null, secondaryColor, EffectType.LINE_UNDERSCORE, Font.PLAIN)
    }

    fun removeHighlights() {
        highlighters.forEach { highlighter ->
            this.editor.markupModel.removeHighlighter(highlighter)
        }
        highlighters.clear()
    }
}
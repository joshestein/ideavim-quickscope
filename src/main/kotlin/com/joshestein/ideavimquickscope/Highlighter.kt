package com.joshestein.ideavimquickscope

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.markup.*
import com.maddyhome.idea.vim.VimPlugin
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimString
import java.awt.Color
import java.awt.Font

private const val PRIMARY_COLOR_VARIABLE = "qs_primary_color"
private const val SECONDARY_COLOR_VARIABLE = "qs_secondary_color"

data class Highlight(val position: Int, val primary: Boolean)

/** The colour used when no `g:qs_*_color` variable is set: the scheme's hyperlink colour. */
internal fun Editor.defaultHighlightColor(): Color =
    colorsScheme.getAttributes(EditorColors.REFERENCE_HYPERLINK_COLOR)?.foregroundColor
        ?: EditorColors.REFERENCE_HYPERLINK_COLOR.defaultAttributes.foregroundColor

class Highlighter(val editor: Editor) {
    private var primaryTextAttributes = this.getPrimaryHighlightTextAttributes()
    private var secondaryTextAttributes = this.getSecondaryHighlightTextAttributes()
    private val highlighters: MutableSet<RangeHighlighter> = mutableSetOf()

    fun addHighlights(highlights: List<Highlight>) {
        highlights.forEach { highlight ->
            highlighters.add(
                this.editor.markupModel.addRangeHighlighter(
                    highlight.position,
                    highlight.position + 1,
                    HighlighterLayer.SELECTION,
                    if (highlight.primary) primaryTextAttributes else secondaryTextAttributes,
                    HighlighterTargetArea.EXACT_RANGE
                )
            )
        }
    }

    private fun getPrimaryHighlightTextAttributes(): TextAttributes {
        val primaryColor = this.getPrimaryColor()
        return TextAttributes(
            primaryColor,
            null,
            primaryColor,
            EffectType.BOLD_LINE_UNDERSCORE,
            Font.BOLD
        )
    }

    private fun getSecondaryHighlightTextAttributes(): TextAttributes {
        val secondaryColor = this.getSecondaryColor()
        return TextAttributes(
            secondaryColor,
            null,
            secondaryColor,
            EffectType.LINE_UNDERSCORE,
            Font.PLAIN
        )
    }

    private fun getPrimaryColor(): Color = configuredColor(PRIMARY_COLOR_VARIABLE) ?: editor.defaultHighlightColor()

    private fun getSecondaryColor(): Color = configuredColor(SECONDARY_COLOR_VARIABLE) ?: defaultSecondaryColor()

    /** The colour from [variable], or null when it is unset or not a valid colour string. */
    private fun configuredColor(variable: String): Color? {
        val rawValue = VimPlugin.getVariableService().getGlobalVariableValue(variable)
        val colorString = (rawValue as? VimString)?.value ?: return null
        return try {
            Color.decode(colorString)
        } catch (e: NumberFormatException) {
            null
        }
    }

    private fun defaultSecondaryColor(): Color {
        val color = editor.defaultHighlightColor()
        return color.brighter().takeIf { it != color } ?: color.darker()
    }

    fun removeHighlights() {
        if (!editor.isDisposed) highlighters.forEach { editor.markupModel.removeHighlighter(it) }
        highlighters.clear()
    }

    fun updateHighlighterColors() {
        this.removeHighlights()
        if (editor.isDisposed) return
        this.primaryTextAttributes = this.getPrimaryHighlightTextAttributes()
        this.secondaryTextAttributes = this.getSecondaryHighlightTextAttributes()
    }
}

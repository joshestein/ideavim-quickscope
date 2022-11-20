package com.joshestein.ideavimquickscope

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.markup.*
import java.awt.Font

data class Highlight(val position: Int, val primary: Boolean)

class Highlighter(var editor: Editor) {
    private val highlighters: MutableSet<RangeHighlighter> = mutableSetOf()

    fun updateEditor(editor: Editor) {
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
        null,
        if (primary) EditorColors.TEXT_SEARCH_RESULT_ATTRIBUTES.defaultAttributes.backgroundColor else EditorColors.DELETED_TEXT_ATTRIBUTES.defaultAttributes.backgroundColor,
        this.editor.colorsScheme.getColor(EditorColors.CARET_COLOR),
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
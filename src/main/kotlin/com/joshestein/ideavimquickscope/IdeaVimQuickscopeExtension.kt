package com.joshestein.ideavimquickscope

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.markup.*
import com.maddyhome.idea.vim.VimPlugin
import com.maddyhome.idea.vim.command.MappingMode
import com.maddyhome.idea.vim.extension.VimExtension
import com.maddyhome.idea.vim.extension.VimExtensionFacade
import com.maddyhome.idea.vim.extension.VimExtensionFacade.putExtensionHandlerMapping
import com.maddyhome.idea.vim.extension.VimExtensionFacade.putKeyMappingIfMissing
import com.maddyhome.idea.vim.extension.VimExtensionHandler
import com.maddyhome.idea.vim.helper.StringHelper.parseKeys
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimList
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimString
import java.awt.Font
import java.awt.event.KeyEvent

private enum class Direction { FORWARD, BACKWARD }

private val ACCEPTED_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray()

private const val HIGHLIGHT_ON_KEYS_VARIABLE = "qs_highlight_on_keys"
private val DEFAULT_HIGHLIGHT_ON_KEYS =
    VimList(mutableListOf(VimString("f"), VimString("F"), VimString("t"), VimString("T")))

class IdeaVimQuickscopeExtension : VimExtension {

    override fun getName() = "quickscope"
    override fun init() {
        val highlightKeysVal = VimPlugin.getVariableService().getGlobalVariableValue(HIGHLIGHT_ON_KEYS_VARIABLE)
        val highlightKeys = if (highlightKeysVal != null && highlightKeysVal is VimList) {
            highlightKeysVal
        } else {
            DEFAULT_HIGHLIGHT_ON_KEYS
        }
        for (value in highlightKeys.values) {
            putExtensionHandlerMapping(
                MappingMode.NXO,
                parseKeys("<Plug>quickscope-$value"),
                owner,
                QuickscopeHandler(value.toString()[0]),
                false
            )
            putKeyMappingIfMissing(
                MappingMode.NXO,
                parseKeys(value.toString()),
                owner,
                parseKeys("<Plug>quickscope-$value"),
                true
            )
        }
    }

    private class QuickscopeHandler(private val char: Char) : VimExtensionHandler {
        private val highlighters: MutableSet<RangeHighlighter> = mutableSetOf()

        lateinit var editor: Editor

        override fun execute(editor: Editor, context: DataContext) {
            val direction = if (char == 'f' || char == 't') Direction.FORWARD else Direction.BACKWARD
            this.editor = editor

            addHighlights(direction)
            val to = getChar() ?: return removeHighlights()

            VimExtensionFacade.executeNormalWithoutMapping(parseKeys("$char$to"), editor)
            removeHighlights()
        }

        private fun getChar(): Char? {
            val key = VimExtensionFacade.inputKeyStroke(this.editor)
            if (key.keyChar == KeyEvent.CHAR_UNDEFINED || key.keyCode == KeyEvent.VK_ESCAPE) return null
            return key.keyChar
        }

        private fun addHighlights(direction: Direction) {
            val occurrences = mutableMapOf<Char, Int>()
            var posPrimary = 0
            var posSecondary = 0

            val caret = this.editor.caretModel.primaryCaret
            var i = caret.offset

            var isFirstWord = true
            var isFirstChar = true
            while ((direction == Direction.FORWARD && (i < caret.visualLineEnd)) || (direction == Direction.BACKWARD && (i >= caret.visualLineStart))) {
                val char = this.editor.document.charsSequence[i]
                if (isFirstChar) {
                    isFirstChar = false
                } else if (ACCEPTED_CHARS.contains(char)) {
                    occurrences[char] = occurrences.getOrDefault(char, 0) + 1
                    if (!isFirstWord) {
                        val occurrence = occurrences[char]

                        if (occurrence == 1 && ((direction == Direction.FORWARD && posPrimary == 0) || direction == Direction.BACKWARD)) {
                            posPrimary = i
                        }
                        if (occurrence == 2 && ((direction == Direction.FORWARD && posPrimary == 0) || direction == Direction.BACKWARD)) {
                            posSecondary = i
                        }
                    }
                } else {
                    if (!isFirstWord) {
                        if (posPrimary > 0) {
                            addHighlight(posPrimary, true)
                        } else if (posSecondary > 0) {
                            addHighlight(posSecondary, false)
                        }
                    }

                    isFirstWord = false
                    posPrimary = 0
                    posSecondary = 0
                }

                if (direction == Direction.FORWARD) {
                    i += 1
                } else {
                    i -= 1
                }
            }

            // Add highlights for first/last characters.
            // We allow for equality to zero, since the primary/secondary position may be in the Oth position!
            // However, this requires us to differentiate between a not-allowed 0th position char (which would
            // correspond to pos = 0) and an allowed 0th position char.
            if (posPrimary >= 0 && ACCEPTED_CHARS.contains(this.editor.document.charsSequence[posPrimary])) {
                addHighlight(posPrimary, true)
            } else if (posSecondary >= 0 && ACCEPTED_CHARS.contains(this.editor.document.charsSequence[posPrimary])) {
                addHighlight(posSecondary, false)
            }
        }

        private fun addHighlight(position: Int, primary: Boolean) {
            val highlight = editor.markupModel.addRangeHighlighter(
                position,
                position + 1,
                HighlighterLayer.SELECTION,
                getHighlightTextAttributes(primary),
                HighlighterTargetArea.EXACT_RANGE
            )
            highlighters.add(highlight)
        }

        private fun getHighlightTextAttributes(primary: Boolean) = TextAttributes(
            null,
            if (primary) EditorColors.TEXT_SEARCH_RESULT_ATTRIBUTES.defaultAttributes.backgroundColor else EditorColors.DELETED_TEXT_ATTRIBUTES.defaultAttributes.backgroundColor,
            this.editor.colorsScheme.getColor(EditorColors.CARET_COLOR),
            EffectType.LINE_UNDERSCORE,
            Font.PLAIN
        )

        private fun removeHighlights() {
            highlighters.forEach { highlighter ->
                this.editor.markupModel.removeHighlighter(highlighter)
            }
            highlighters.clear()
        }
    }
}

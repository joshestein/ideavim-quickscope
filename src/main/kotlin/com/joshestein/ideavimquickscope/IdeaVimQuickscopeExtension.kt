package com.joshestein.ideavimquickscope

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.TextRange
import com.maddyhome.idea.vim.command.MappingMode
import com.maddyhome.idea.vim.extension.VimExtension
import com.maddyhome.idea.vim.extension.VimExtensionFacade
import com.maddyhome.idea.vim.extension.VimExtensionFacade.putExtensionHandlerMapping
import com.maddyhome.idea.vim.extension.VimExtensionFacade.putKeyMappingIfMissing
import com.maddyhome.idea.vim.extension.VimExtensionHandler
import com.maddyhome.idea.vim.helper.StringHelper.parseKeys
import java.awt.Font
import java.awt.event.KeyEvent

private val log = logger<IdeaVimQuickscopeExtension>()

enum class Direction { FORWARD, BACKWARD }

val ACCEPTED_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray()

class IdeaVimQuickscopeExtension : VimExtension {

    override fun getName() = "quickscope"
    override fun init() {
        // TODO: HIGHLIGHT_ON_KEYS
        // @formatter:off
        putExtensionHandlerMapping(MappingMode.NXO, parseKeys("<Plug>quickscope-forward-find"), owner, QuickscopeHandler('f'), false)
        putExtensionHandlerMapping(MappingMode.NXO, parseKeys("<Plug>quickscope-forward-to"), owner, QuickscopeHandler('t'), false)
        putExtensionHandlerMapping(MappingMode.NXO, parseKeys("<Plug>quickscope-backward-find"), owner, QuickscopeHandler('F'), false)
        putExtensionHandlerMapping(MappingMode.NXO, parseKeys("<Plug>quickscope-backward-to"), owner, QuickscopeHandler('T'), false)

        putKeyMappingIfMissing(MappingMode.NXO, parseKeys("f"), owner, parseKeys("<Plug>quickscope-forward-find"), true)
        putKeyMappingIfMissing(MappingMode.NXO, parseKeys("t"), owner, parseKeys("<Plug>quickscope-forward-to"), true)
        putKeyMappingIfMissing(MappingMode.NXO, parseKeys("F"), owner, parseKeys("<Plug>quickscope-backward-find"), true)
        putKeyMappingIfMissing(MappingMode.NXO, parseKeys("T"), owner, parseKeys("<Plug>quickscope-backward-to"), true)
        // @formatter:on
    }

    private class QuickscopeHandler(private val char: Char) : VimExtensionHandler {
        private val highlighters: MutableSet<RangeHighlighter> = mutableSetOf()

        lateinit var editor: Editor

        override fun execute(editor: Editor, context: DataContext) {
            val direction = if (char == 'f' || char == 't') Direction.FORWARD else Direction.BACKWARD
            this.editor = editor

            addHighlights(direction)
            val to = getChar()
            if (to == null) {
                removeHighlights()
                return
            }
            VimExtensionFacade.executeNormalWithoutMapping(parseKeys(char.toString()), editor)
            VimExtensionFacade.executeNormalWithoutMapping(parseKeys(to.toString()), editor)
            removeHighlights()
        }

        private fun getChar(): Char? {
            val key = VimExtensionFacade.inputKeyStroke(this.editor)
            if (key.keyChar == KeyEvent.CHAR_UNDEFINED || key.keyCode == KeyEvent.VK_ESCAPE) return null
            return key.keyChar
        }

        private fun addHighlights(direction: Direction) {
            val occurrences = mutableMapOf<Char, Int>()
            var (highlight_primary, highlight_secondary) = Pair(0, 0)

            val caret = this.editor.caretModel.primaryCaret
            val text = if (direction == Direction.FORWARD) {
                this.editor.document.getText(TextRange(caret.offset, caret.visualLineEnd))
            } else {
                // TODO: backwards
                this.editor.document.getText(TextRange(caret.visualLineStart, caret.offset))
            }

            var newWord = true
            for ((i, c) in text.withIndex()) {
                if (ACCEPTED_CHARS.contains(c)) {
                    occurrences[c] = occurrences.getOrDefault(c, 0) + 1
                    val occurrence = occurrences[c]

//                    if (!newWord && (occurence == 1 || occurence == 2)) {
                    if (!newWord && occurrence == 1) {
                        addHighlight(caret.offset + i, occurrence == 1)
                        newWord = true
                    }
                } else {
                    newWord = false
                }
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
            // TODO: use `primary` to update secondary styling option
            null,
            EditorColors.TEXT_SEARCH_RESULT_ATTRIBUTES.defaultAttributes.backgroundColor,
            this.editor.colorsScheme.getColor(EditorColors.CARET_COLOR),
            EffectType.SEARCH_MATCH,
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

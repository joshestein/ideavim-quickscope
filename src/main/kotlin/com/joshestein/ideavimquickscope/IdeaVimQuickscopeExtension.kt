package com.joshestein.ideavimquickscope

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.EditorEventMulticaster
import com.intellij.openapi.util.Disposer
import com.maddyhome.idea.vim.VimPlugin
import com.maddyhome.idea.vim.command.MappingMode
import com.maddyhome.idea.vim.extension.VimExtension
import com.maddyhome.idea.vim.extension.VimExtensionFacade
import com.maddyhome.idea.vim.extension.VimExtensionFacade.putExtensionHandlerMapping
import com.maddyhome.idea.vim.extension.VimExtensionFacade.putKeyMappingIfMissing
import com.maddyhome.idea.vim.extension.VimExtensionHandler
import com.maddyhome.idea.vim.helper.StringHelper.parseKeys
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimList
import java.awt.event.KeyEvent

private enum class Direction { FORWARD, BACKWARD }

private var ACCEPTED_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray()

private const val ACCEPTED_CHARS_VARIABLE = "qs_accepted_chars"
private const val HIGHLIGHT_ON_KEYS_VARIABLE = "qs_highlight_on_keys"

class Listener : CaretListener {
    private lateinit var highlighter: Highlighter
    override fun caretPositionChanged(e: CaretEvent) {
        if (!this::highlighter.isInitialized) this.highlighter = Highlighter(e.editor)

        if (highlighter.editor != e.editor) highlighter.updateEditor(e.editor)

        highlighter.removeHighlights()
        highlighter.addHighlights(getHighlightsOnLine(e.editor, Direction.FORWARD))
        highlighter.addHighlights(getHighlightsOnLine(e.editor, Direction.BACKWARD))
    }
}

class IdeaVimQuickscopeExtension : VimExtension {
    private lateinit var multiCaster: EditorEventMulticaster
    private lateinit var caretListener: Listener
    override fun getName() = "quickscope"
    override fun init() {
        val userAcceptedChars = VimPlugin.getVariableService().getGlobalVariableValue(ACCEPTED_CHARS_VARIABLE)
        val highlightKeys = VimPlugin.getVariableService().getGlobalVariableValue(HIGHLIGHT_ON_KEYS_VARIABLE)

        if (userAcceptedChars != null && userAcceptedChars is VimList) {
            ACCEPTED_CHARS = userAcceptedChars.values
                .joinToString("")
                .toCharArray()
        }

        if (highlightKeys != null && highlightKeys is VimList) {
            // Only add highlights after pressing one of the variable keys (e.g. "f", "t", "F", "T")
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
        } else {
            // Create a caret listener that automatically highlights unique characters in both directions.
            multiCaster = EditorFactory.getInstance().eventMulticaster
            caretListener = Listener()
            multiCaster.addCaretListener(caretListener, Disposer.newDisposable())
        }
    }

    override fun dispose() {
        if (this::multiCaster.isInitialized && this::caretListener.isInitialized) {
            multiCaster.removeCaretListener(caretListener)
        }
    }

    private class QuickscopeHandler(private val char: Char) : VimExtensionHandler {
        private lateinit var highlighter: Highlighter

        override fun execute(editor: Editor, context: DataContext) {
            if (!this::highlighter.isInitialized) this.highlighter = Highlighter(editor)
            if (highlighter.editor != editor) highlighter.updateEditor(editor)

            val direction = if (char == 'f' || char == 't') Direction.FORWARD else Direction.BACKWARD
            highlighter.addHighlights(getHighlightsOnLine(editor, direction))
            val to = getChar(editor) ?: return highlighter.removeHighlights()

            VimExtensionFacade.executeNormalWithoutMapping(parseKeys("$char$to"), editor)
            highlighter.removeHighlights()
        }

        private fun getChar(editor: Editor): Char? {
            val key = VimExtensionFacade.inputKeyStroke(editor)
            if (key.keyChar == KeyEvent.CHAR_UNDEFINED || key.keyCode == KeyEvent.VK_ESCAPE) return null
            return key.keyChar
        }
    }
}

private fun getHighlightsOnLine(editor: Editor, direction: Direction): List<Highlight> {
    val highlights = mutableListOf<Highlight>()
    val occurrences = mutableMapOf<Char, Int>()
    var posPrimary = -1
    var posSecondary = -1

    val caret = editor.caretModel.primaryCaret
    var i = caret.offset

    var isFirstWord = true
    var isFirstChar = true
    while ((direction == Direction.FORWARD && (i < caret.visualLineEnd)) || (direction == Direction.BACKWARD && (i >= caret.visualLineStart))) {
        if (i == editor.document.textLength) return highlights

        val char = editor.document.charsSequence[i]
        if (isFirstChar) {
            isFirstChar = false
        } else if (ACCEPTED_CHARS.contains(char)) {
            occurrences[char] = occurrences.getOrDefault(char, 0) + 1
            if (!isFirstWord) {
                val occurrence = occurrences[char]

                if (occurrence == 1 && ((direction == Direction.FORWARD && posPrimary == -1) || direction == Direction.BACKWARD)) {
                    posPrimary = i
                } else if (occurrence == 2 && ((direction == Direction.FORWARD && posSecondary == -1) || direction == Direction.BACKWARD)) {
                    posSecondary = i
                }
            }
        } else {
            if (!isFirstWord) {
                if (posPrimary >= 0) {
                    highlights.add(Highlight(posPrimary, true))
                } else if (posSecondary >= 0) {
                    highlights.add(Highlight(posSecondary, false))
                }
            }

            isFirstWord = false
            posPrimary = -1
            posSecondary = -1
        }

        if (direction == Direction.FORWARD) {
            i += 1
        } else {
            i -= 1
        }
    }

    // Add highlights for first/last characters.
    if (posPrimary >= 0) {
        highlights.add(Highlight(posPrimary, true))
    } else if (posSecondary >= 0) {
        highlights.add(Highlight(posSecondary, false))
    }

    return highlights
}
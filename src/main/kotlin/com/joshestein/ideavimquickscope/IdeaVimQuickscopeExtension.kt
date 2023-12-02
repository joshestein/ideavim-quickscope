package com.joshestein.ideavimquickscope

import com.intellij.ide.ui.LafManager
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.EditorEventMulticaster
import com.intellij.openapi.util.Disposer
import com.maddyhome.idea.vim.VimPlugin
import com.maddyhome.idea.vim.command.CommandState
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
private var LOWERCASE_PRIORITY = false

private const val ACCEPTED_CHARS_VARIABLE = "qs_accepted_chars"
private const val HIGHLIGHT_ON_KEYS_VARIABLE = "qs_highlight_on_keys"
private const val LOWERCASE_PRIORITY_VARIABLE = "qs_lowercase_priority"

private lateinit var highlighter: Highlighter

class Listener : CaretListener {
    override fun caretPositionChanged(e: CaretEvent) {
        if (!::highlighter.isInitialized) highlighter = Highlighter(e.editor)
        if (highlighter.editor != e.editor) highlighter.updateEditor(e.editor)

        // TODO: rather than manually inspecting the mode, once autocommands are supported we should listen to
        // `InsertEnter` and remove highlights.
        // https://youtrack.jetbrains.com/issue/VIM-1693/Add-support-for-autocmd
        if (CommandState.getInstance(e.editor).mode == CommandState.Mode.INSERT) return highlighter.removeHighlights()

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
        LOWERCASE_PRIORITY =
            VimPlugin.getVariableService().getGlobalVariableValue(LOWERCASE_PRIORITY_VARIABLE)?.asBoolean() == true

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
        override fun execute(editor: Editor, context: DataContext) {
            if (!::highlighter.isInitialized) highlighter = Highlighter(editor)
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
    var isPrimaryLowerCase = false
    var posSecondary = -1
    var isSecondaryLowerCase = false

    val caret = editor.caretModel.primaryCaret

    var isFirstWord = true
    var isFirstChar = true
    val line = when (direction) {
        Direction.FORWARD ->
            editor.document.charsSequence.subSequence(caret.offset, caret.visualLineEnd)

        Direction.BACKWARD ->
            editor.document.charsSequence.subSequence(caret.visualLineStart, caret.offset + 1).reversed()
    }

    line.forEachIndexed { i, char ->
        if (isFirstChar) {
            isFirstChar = false
        } else if (ACCEPTED_CHARS.contains(char)) {
            occurrences[char] = occurrences.getOrDefault(char, 0) + 1
            if (!isFirstWord) {
                val occurrence = occurrences[char]
                if (occurrence == 1) {
                    if (posPrimary == -1 || (LOWERCASE_PRIORITY && !isPrimaryLowerCase && char.isLowerCase())) {
                        posPrimary = i
                        isPrimaryLowerCase = char.isLowerCase()
                    }
                } else if (occurrence == 2) {
                    if (posSecondary == -1 || (LOWERCASE_PRIORITY && !isSecondaryLowerCase && char.isLowerCase())) {
                        posSecondary = i
                        isSecondaryLowerCase = char.isLowerCase()
                    }
                }
            }
        } else {
            if (!isFirstWord) {
                if (posPrimary >= 0) {
                    highlights.add(Highlight(applyOffset(posPrimary, caret, direction), true))
                } else if (posSecondary >= 0) {
                    highlights.add(Highlight(applyOffset(posSecondary, caret, direction), false))
                }
            }

            isFirstWord = false
            posPrimary = -1
            posSecondary = -1
        }

    }

    // Add highlights for first/last characters.
    if (posPrimary >= 0) {
        highlights.add(Highlight(applyOffset(posPrimary, caret, direction), true))
    } else if (posSecondary >= 0) {
        highlights.add(Highlight(applyOffset(posSecondary, caret, direction), false))
    }

    return highlights
}

private fun applyOffset(int: Int, caret: Caret, direction: Direction): Int {
    return when (direction) {
        Direction.FORWARD -> int + caret.offset
        Direction.BACKWARD -> caret.offset - int
    }
}

class LafListener : LafManagerListener {
    override fun lookAndFeelChanged(source: LafManager) {
        if (::highlighter.isInitialized) highlighter.updateHighlighterColors()
    }
}

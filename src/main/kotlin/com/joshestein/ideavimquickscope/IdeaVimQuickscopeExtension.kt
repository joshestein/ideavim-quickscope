package com.joshestein.ideavimquickscope

import com.intellij.ide.IdeEventQueue
import com.intellij.ide.ui.LafManager
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.util.Disposer
import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.VimPlugin
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.command.MappingMode
import com.maddyhome.idea.vim.extension.VimExtension
import com.maddyhome.idea.vim.extension.VimExtensionFacade.putKeyMappingIfMissing
import com.maddyhome.idea.vim.newapi.ij
import com.maddyhome.idea.vim.state.mode.Mode as VimMode
import com.maddyhome.idea.vim.vimscript.model.VimLContext
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimDataType
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimInt
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimList
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimString
import com.maddyhome.idea.vim.vimscript.model.expressions.Expression
import java.awt.event.KeyEvent
import java.util.WeakHashMap

internal enum class Direction { FORWARD, BACKWARD }

internal var ACCEPTED_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray()

private const val ACCEPTED_CHARS_VARIABLE = "qs_accepted_chars"
private const val HIGHLIGHT_ON_KEYS_VARIABLE = "qs_highlight_on_keys"
private const val DISABLE_FOR_DIFFS_VARIABLE = "qs_disable_for_diffs"

internal val highlighters = WeakHashMap<Editor, Highlighter>()

private fun getHighlighter(editor: Editor): Highlighter {
    return highlighters.computeIfAbsent(editor) { Highlighter(it) }
}

private var disableForDiffs = false

/** Editor showing key-mode highlights that have not been removed yet, if any. */
private var pendingEditor: Editor? = null

/** Whether quickscope may draw highlights in [editor] at all. Never affects what the motion keys do. */
private fun highlightsAllowed(editor: Editor): Boolean {
    if (editor.editorKind == EditorKind.CONSOLE) return false
    if (disableForDiffs && editor.editorKind == EditorKind.DIFF) return false
    return true
}

private fun directionOf(key: Char) = if (key == 'f' || key == 't') Direction.FORWARD else Direction.BACKWARD

/** Automatic mode: highlight both directions whenever the caret moves. */
class Listener : CaretListener {
    override fun caretPositionChanged(e: CaretEvent) {
        val highlighter = getHighlighter(e.editor)

        // TODO: rather than manually inspecting the mode, once autocommands are supported we should listen to
        // `InsertEnter` and remove highlights.
        // https://youtrack.jetbrains.com/issue/VIM-1693/Add-support-for-autocmd
        if (injector.vimState.mode == VimMode.INSERT) return highlighter.removeHighlights()

        highlighter.removeHighlights()
        if (!highlightsAllowed(e.editor)) return
        highlighter.addHighlights(getHighlightsOnLine(e.editor, Direction.FORWARD))
        highlighter.addHighlights(getHighlightsOnLine(e.editor, Direction.BACKWARD))
    }
}

/**
 * Key mode: an `<expr>` mapping, the same mechanism upstream quick-scope uses.
 *
 * Evaluated when the user presses one of the `g:qs_highlight_on_keys` keys. It draws the highlights for that key's
 * direction and returns the key itself, so IdeaVim runs its own `f`/`F`/`t`/`T` motion. Counts, operators, `;`/`,`,
 * dot-repeat, macros and digraph arguments all keep their native behaviour because quickscope never handles the
 * motion or its argument.
 */
private class QuickscopeExpression(private val key: Char, private val onHighlightsShown: (Editor) -> Unit) : Expression() {
    override fun evaluate(editor: VimEditor, context: ExecutionContext, vimContext: VimLContext): VimDataType {
        val ijEditor = editor.ij
        if (highlightsAllowed(ijEditor)) {
            getHighlighter(ijEditor).addHighlights(getHighlightsOnLine(ijEditor, directionOf(key)))
            pendingEditor = ijEditor
        }
        return VimString(key.toString())
    }
}

class IdeaVimQuickscopeExtension : VimExtension {
    /** Parent of every listener registered by [init]. Disposed by [dispose]. */
    private var disposable: Disposable? = null

    override fun getName() = "quickscope"

    override fun init() {
        tearDown()

        val userAcceptedChars = VimPlugin.getVariableService().getGlobalVariableValue(ACCEPTED_CHARS_VARIABLE)
        val highlightKeys = VimPlugin.getVariableService().getGlobalVariableValue(HIGHLIGHT_ON_KEYS_VARIABLE)
        disableForDiffs = VimPlugin.getVariableService().getGlobalVariableValue(DISABLE_FOR_DIFFS_VARIABLE) == VimInt(1)

        if (userAcceptedChars is VimList) {
            ACCEPTED_CHARS = userAcceptedChars.values
                .joinToString("") { (it as? VimString)?.value ?: "" }
                .toCharArray()
        }

        val parent = Disposer.newDisposable("IdeaVim-Quickscope")
        disposable = parent

        if (highlightKeys is VimList) {
            // Only add highlights after pressing one of the variable keys (e.g. "f", "t", "F", "T")
            for (value in highlightKeys.values) {
                val string = (value as? VimString)?.value ?: continue
                if (string.isEmpty()) continue
                VimPlugin.getKey().putKeyMapping(
                    MappingMode.NXO,
                    injector.parser.parseKeys("<Plug>quickscope-$string"),
                    owner,
                    QuickscopeExpression(string[0]),
                    "<expr> quickscope $string",
                    false
                )
                putKeyMappingIfMissing(
                    MappingMode.NXO,
                    injector.parser.parseKeys(string),
                    owner,
                    injector.parser.parseKeys("<Plug>quickscope-$string"),
                    true
                )
            }

            // The expression never sees the argument character. IdeaVim handles each key inside the dispatch of its
            // AWT event, so after that dispatch the command builder tells us whether it is still waiting for the
            // argument. Covers found, not found, <Esc>, cancelled operators and macros alike.
            IdeEventQueue.getInstance().addPostprocessor({ event ->
                if (event is KeyEvent) removeStaleHighlights()
                false
            }, parent)
        } else {
            // Create a caret listener that automatically highlights unique characters in both directions.
            EditorFactory.getInstance().eventMulticaster.addCaretListener(Listener(), parent)
        }
    }

    override fun dispose() {
        // Removes every key mapping owned by this extension.
        super.dispose()
        tearDown()
        for (highlighter in highlighters.values) {
            if (!highlighter.editor.isDisposed) highlighter.removeHighlights()
        }
        highlighters.clear()
    }

    private fun tearDown() {
        removePendingHighlights()
        pendingEditor = null
        disposable?.let { Disposer.dispose(it) }
        disposable = null
    }

    /** Removes key-mode highlights once IdeaVim has stopped waiting for the motion's character argument. */
    private fun removeStaleHighlights() {
        if (pendingEditor == null) return
        if (KeyHandler.getInstance().keyHandlerState.commandBuilder.isAwaitingCharOrDigraphArgument()) return
        removePendingHighlights()
    }

    private fun removePendingHighlights() {
        val editor = pendingEditor ?: return
        pendingEditor = null
        if (!editor.isDisposed) getHighlighter(editor).removeHighlights()
    }
}

internal fun getHighlightsOnLine(editor: Editor, direction: Direction): List<Highlight> {
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

class LafListener : LafManagerListener {
    override fun lookAndFeelChanged(source: LafManager) {
        for (highlighter in highlighters.values) {
            if (!highlighter.editor.isDisposed) {
                highlighter.updateHighlighterColors()
            }
        }
    }
}

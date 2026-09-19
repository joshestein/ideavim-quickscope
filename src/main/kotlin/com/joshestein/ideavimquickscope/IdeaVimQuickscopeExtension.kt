package com.joshestein.ideavimquickscope

import com.intellij.ide.IdeEventQueue
import com.intellij.ide.ui.LafManager
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.maddyhome.idea.vim.VimPlugin
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.command.MappingMode
import com.maddyhome.idea.vim.extension.VimExtension
import com.maddyhome.idea.vim.extension.VimExtensionFacade.putKeyMappingIfMissing
import com.maddyhome.idea.vim.newapi.ij
import com.maddyhome.idea.vim.newapi.vim
import com.maddyhome.idea.vim.state.mode.Mode as VimMode
import com.maddyhome.idea.vim.vimscript.model.VimLContext
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimDataType
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimInt
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimList
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimString
import com.maddyhome.idea.vim.vimscript.model.expressions.Expression
import java.awt.event.KeyEvent

internal enum class Direction { FORWARD, BACKWARD }

internal var ACCEPTED_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray()

private const val ACCEPTED_CHARS_VARIABLE = "qs_accepted_chars"
private const val HIGHLIGHT_ON_KEYS_VARIABLE = "qs_highlight_on_keys"
private const val DISABLE_FOR_DIFFS_VARIABLE = "qs_disable_for_diffs"

/** Each editor owns its Highlighter. User data is released together with the editor. */
internal val HIGHLIGHTER_KEY = Key.create<Highlighter>("quickscope.highlighter")

private fun getHighlighter(editor: Editor): Highlighter =
    editor.getUserData(HIGHLIGHTER_KEY) ?: Highlighter(editor).also { editor.putUserData(HIGHLIGHTER_KEY, it) }

private fun allHighlighters(): List<Highlighter> =
    EditorFactory.getInstance().allEditors.mapNotNull { it.getUserData(HIGHLIGHTER_KEY) }

private var disableForDiffs = false

/** Editor showing key-mode highlights that have not been removed yet, if any. */
private var pendingEditor: Editor? = null

/** Whether the KEY_TYPED event of the key that showed the highlights has been seen. */
private var triggerKeyTyped = false

/** Whether quickscope may draw highlights in [editor]. Never affects what the motion keys do. */
private fun highlightsAllowed(editor: Editor): Boolean {
    if (editor.editorKind == EditorKind.CONSOLE) return false
    if (disableForDiffs && editor.editorKind == EditorKind.DIFF) return false
    return true
}

/** Automatic mode: highlight both directions whenever the caret moves. */
class Listener : CaretListener {
    override fun caretPositionChanged(e: CaretEvent) {
        e.editor.getUserData(HIGHLIGHTER_KEY)?.removeHighlights()

        // TODO: rather than manually inspecting the mode, once autocommands are supported we should listen to
        // `InsertEnter` and remove highlights.
        // https://youtrack.jetbrains.com/issue/VIM-1693/Add-support-for-autocmd
        val mode = e.editor.vim.mode
        if (mode is VimMode.INSERT || mode is VimMode.REPLACE) return

        if (!highlightsAllowed(e.editor)) return
        val highlighter = getHighlighter(e.editor)
        highlighter.addHighlights(getHighlightsOnLine(e.editor, Direction.FORWARD))
        highlighter.addHighlights(getHighlightsOnLine(e.editor, Direction.BACKWARD))
    }
}

/**
 * An `<expr>` mapping for one of the `g:qs_highlight_on_keys` keys, the same mechanism upstream quick-scope uses.
 *
 * Draws the highlights for the key's direction and returns the key itself, so IdeaVim runs its own `f`/`F`/`t`/`T`.
 * Quickscope never handles the motion or its argument, so operators, counts, `;`/`,`, dot-repeat and macros keep
 * their native behaviour.
 */
private class QuickscopeExpression(private val key: Char) : Expression() {
    override fun evaluate(editor: VimEditor, context: ExecutionContext, vimContext: VimLContext): VimDataType {
        val ijEditor = editor.ij
        if (highlightsAllowed(ijEditor)) {
            val direction = if (key == 'f' || key == 't') Direction.FORWARD else Direction.BACKWARD
            getHighlighter(ijEditor).addHighlights(getHighlightsOnLine(ijEditor, direction))
            pendingEditor = ijEditor
            triggerKeyTyped = false
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

        // IdeaVim only calls dispose() on `set noquickscope` or plugin unload, never at IDE exit. Parent under the
        // application so the platform disposes the listeners at shutdown instead of reporting a leak.
        val parent = Disposer.newDisposable(ApplicationManager.getApplication(), "IdeaVim-Quickscope")
        disposable = parent

        if (highlightKeys is VimList) {
            // Only add highlights after pressing one of the variable keys (e.g. "f", "t", "F", "T")
            for (value in highlightKeys.values) {
                // TODO: When using a newer version of IdeaVim, we can use value.toVimString().value
                val key = (value as? VimString)?.value?.firstOrNull() ?: continue
                VimPlugin.getKey().putKeyMapping(
                    MappingMode.NXO,
                    injector.parser.parseKeys("<Plug>quickscope-$key"),
                    owner,
                    QuickscopeExpression(key),
                    "<expr> quickscope $key",
                    false
                )
                putKeyMappingIfMissing(
                    MappingMode.NXO,
                    injector.parser.parseKeys("$key"),
                    owner,
                    injector.parser.parseKeys("<Plug>quickscope-$key"),
                    true
                )
            }

            // The expression never sees the argument character. It runs during the key event of the trigger key, so
            // the next KEY_TYPED event is the argument (or <Esc>), and IdeaVim has already handled it by the time
            // post-processors run. No IdeaVim API is involved, so this survives IdeaVim internals changing.
            IdeEventQueue.getInstance().addPostprocessor({ event ->
                if (event is KeyEvent && event.id == KeyEvent.KEY_TYPED) onKeyTyped()
                false
            }, parent)
        } else {
            // Create a caret listener that automatically highlights unique characters in both directions.
            EditorFactory.getInstance().eventMulticaster.addCaretListener(Listener(), parent)
        }
    }

    override fun dispose() {
        super.dispose()
        tearDown()
        EditorFactory.getInstance().allEditors.forEach { it.putUserData(HIGHLIGHTER_KEY, null) }
    }

    /** Undoes everything [init] registered, so a re-run of `.ideavimrc` can switch modes cleanly. */
    private fun tearDown() {
        VimPlugin.getKey().removeKeyMapping(owner)
        pendingEditor = null
        disposable?.let { Disposer.dispose(it) }
        disposable = null
        allHighlighters().forEach { it.removeHighlights() }
    }

    private fun onKeyTyped() {
        val editor = pendingEditor ?: return
        if (!triggerKeyTyped) {
            // The KEY_TYPED of the key that showed the highlights. The argument comes next.
            triggerKeyTyped = true
            return
        }
        pendingEditor = null
        editor.getUserData(HIGHLIGHTER_KEY)?.removeHighlights()
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
        allHighlighters().forEach { it.updateHighlighterColors() }
    }
}

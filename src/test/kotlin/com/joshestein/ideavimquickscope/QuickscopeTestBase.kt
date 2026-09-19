package com.joshestein.ideavimquickscope

import com.intellij.ide.IdeEventQueue
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.VimPlugin
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.helper.TestInputModel
import com.maddyhome.idea.vim.newapi.vim
import com.maddyhome.idea.vim.state.mode.Mode
import java.awt.Font
import java.awt.event.KeyEvent
import javax.swing.JPanel
import javax.swing.KeyStroke

/**
 * Shared fixture for quickscope tests.
 *
 * Boots IdeaVim in unit-test mode, clears vimscript variables and key mappings between tests, and offers helpers to
 * configure an editor from text with a `<caret>` marker, feed keystrokes through IdeaVim, and read back the
 * highlights the plugin added to the editor's markup model.
 */
abstract class QuickscopeTestBase : BasePlatformTestCase() {
    private val defaultAcceptedChars = ACCEPTED_CHARS.copyOf()

    override fun setUp() {
        super.setUp()
        ensureIdeaVimIsOn()
        resetVimState()
    }

    override fun tearDown() {
        try {
            if (myFixture.editor != null) {
                myFixture.editor.vim.mode = Mode.NORMAL()
                KeyHandler.getInstance().fullReset(myFixture.editor.vim)
            }
            resetVimState()
        } finally {
            super.tearDown()
        }
    }

    private fun ensureIdeaVimIsOn() {
        val plugin = VimPlugin.getInstance()
        if (plugin.onOffDisposable == null) {
            plugin.initialize()
        }
    }

    private fun resetVimState() {
        VimPlugin.getVariableService().clear()
        VimPlugin.getKey().resetKeyMappings()
        ACCEPTED_CHARS = defaultAcceptedChars.copyOf()
        highlighters.values.forEach { it.removeHighlights() }
        highlighters.clear()
    }

    /** Configures a plain text editor. `<caret>` marks the caret position. */
    protected fun configure(text: String): Editor {
        myFixture.configureByText("test.txt", text)
        return myFixture.editor
    }

    /**
     * Feeds [keys] (in `:map` notation) through IdeaVim's key handler, as a user typing would.
     *
     * After each key a KEY_RELEASED event is pushed through [IdeEventQueue], so post-processors registered by the
     * plugin observe the keystroke just like they do in the IDE. The event targets a detached panel, so nothing
     * else consumes it.
     */
    protected fun typeText(editor: Editor, keys: String) {
        val keyHandler = KeyHandler.getInstance()
        val vimEditor = editor.vim
        val context = injector.executionContextManager.getEditorExecutionContext(vimEditor)
        val inputModel = TestInputModel.getInstance(editor)
        inputModel.setKeyStrokes(injector.parser.parseKeys(keys))
        var key = inputModel.nextKeyStroke()
        while (key != null) {
            keyHandler.handleKey(vimEditor, key, context, keyHandler.keyHandlerState)
            dispatchKeyReleased(key)
            key = inputModel.nextKeyStroke()
        }
    }

    private fun dispatchKeyReleased(key: KeyStroke) {
        val keyCode = if (key.keyCode != 0) key.keyCode else KeyEvent.VK_UNDEFINED
        val event = KeyEvent(JPanel(), KeyEvent.KEY_RELEASED, System.currentTimeMillis(), key.modifiers, keyCode, KeyEvent.CHAR_UNDEFINED)
        IdeEventQueue.getInstance().dispatchEvent(event)
    }

    /** Reads quickscope highlights back out of the editor's markup model, sorted by position. */
    protected fun visibleHighlights(editor: Editor): List<Highlight> {
        return editor.markupModel.allHighlighters
            .filter { it.layer == HighlighterLayer.SELECTION && it.endOffset - it.startOffset == 1 }
            .map { Highlight(it.startOffset, it.getTextAttributes(null)?.fontType == Font.BOLD) }
            .sortedBy { it.position }
    }

    protected fun primary(position: Int) = Highlight(position, true)
    protected fun secondary(position: Int) = Highlight(position, false)
}

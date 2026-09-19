package com.joshestein.ideavimquickscope

import com.intellij.ide.IdeEventQueue
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.VimPlugin
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.helper.TestInputModel
import com.maddyhome.idea.vim.newapi.vim
import com.maddyhome.idea.vim.state.mode.Mode
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimDataType
import java.awt.Font
import java.awt.event.KeyEvent
import javax.swing.JPanel

/**
 * Shared fixture for quickscope tests.
 *
 * Boots IdeaVim in unit-test mode, clears vimscript variables and key mappings between tests, and offers helpers to
 * configure an editor from text with a `<caret>` marker, feed keystrokes through IdeaVim, and read back the
 * highlights the plugin added to the editor's markup model.
 */
abstract class QuickscopeTestBase : BasePlatformTestCase() {
    private val defaultAcceptedChars = ACCEPTED_CHARS.copyOf()

    /** Detached target for synthetic key events, so nothing else consumes them. */
    private val keyEventSource = JPanel()

    override fun setUp() {
        super.setUp()
        ensureIdeaVimIsOn()
        resetVimState()
    }

    override fun tearDown() {
        try {
            myFixture.editor?.let { resetVim(it) }
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
        EditorFactory.getInstance().allEditors.forEach {
            it.getUserData(HIGHLIGHTER_KEY)?.removeHighlights()
            it.putUserData(HIGHLIGHTER_KEY, null)
        }
    }

    /** Returns [editor] to normal mode with no pending command, as if the user had pressed `<Esc>` enough times. */
    protected fun resetVim(editor: Editor) {
        editor.vim.mode = Mode.NORMAL()
        KeyHandler.getInstance().fullReset(editor.vim)
    }

    /** Stores a `g:` variable, as `let g:name = value` in `.ideavimrc` would. */
    protected fun setVariable(name: String, value: VimDataType) {
        VimPlugin.getVariableService().storeGlobalVariable(name, value)
    }

    /** Configures a plain text editor. `<caret>` marks the caret position. */
    protected fun configure(text: String): Editor {
        myFixture.configureByText("test.txt", text)
        return myFixture.editor
    }

    /**
     * Feeds [keys] (in `:map` notation) through IdeaVim's key handler, as a user typing would.
     *
     * After each key, a KEY_TYPED event is pushed through [IdeEventQueue] so post-processors registered by the
     * plugin see it as in the IDE, where IdeaVim handles the character during that event's dispatch. The event
     * carries no key code or real character: the plugin only counts events, and a real key would be matched as an
     * IDE shortcut and handled twice.
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
            val event = KeyEvent(keyEventSource, KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0, KeyEvent.VK_UNDEFINED, '\u0000')
            IdeEventQueue.getInstance().dispatchEvent(event)
            key = inputModel.nextKeyStroke()
        }
    }

    /** The raw range highlighters quickscope added to [editor], sorted by position. */
    protected fun rangeHighlighters(editor: Editor = myFixture.editor): List<RangeHighlighter> =
        editor.markupModel.allHighlighters
            .filter { it.layer == HighlighterLayer.SELECTION && it.endOffset - it.startOffset == 1 }
            .sortedBy { it.startOffset }

    /** Reads quickscope highlights back out of the editor's markup model, sorted by position. */
    protected fun visibleHighlights(editor: Editor = myFixture.editor): List<Highlight> =
        rangeHighlighters(editor).map { Highlight(it.startOffset, it.getTextAttributes(null)?.fontType == Font.BOLD) }

    protected fun assertNoHighlights(editor: Editor = myFixture.editor) =
        assertEquals(emptyList<Highlight>(), visibleHighlights(editor))

    protected fun primary(position: Int) = Highlight(position, true)
    protected fun secondary(position: Int) = Highlight(position, false)
}

package com.joshestein.ideavimquickscope

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.VimPlugin
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.newapi.vim
import com.maddyhome.idea.vim.state.mode.Mode
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimDataType
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimInt
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimList
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimString

/**
 * End-to-end tests for [IdeaVimQuickscopeExtension].
 *
 * Variables are stored in IdeaVim's global variable service, then [IdeaVimQuickscopeExtension.init] is called, as
 * happens when `.ideavimrc` runs `let g:...` followed by `set quickscope`.
 */
class IdeaVimQuickscopeExtensionTest : QuickscopeTestBase() {
    private var extension: IdeaVimQuickscopeExtension? = null

    override fun tearDown() {
        try {
            extension?.dispose()
            extension = null
        } finally {
            super.tearDown()
        }
    }

    private fun enableQuickscope() {
        extension = IdeaVimQuickscopeExtension().also { it.init() }
    }

    private fun enableKeyMode(vararg keys: String = arrayOf("f", "F", "t", "T")) {
        setVariable("qs_highlight_on_keys", vimList(*keys))
        enableQuickscope()
    }

    private fun setVariable(name: String, value: VimDataType) {
        VimPlugin.getVariableService().storeGlobalVariable(name, value)
    }

    private fun vimList(vararg values: String) = VimList(values.map<String, VimDataType> { VimString(it) }.toMutableList())

    /** Runs [block] with an editor of the given [kind] over [text], then releases the editor. */
    private fun withEditorOfKind(kind: EditorKind, text: String, block: (Editor) -> Unit) {
        val document = EditorFactory.getInstance().createDocument(text)
        val editor = EditorFactory.getInstance().createEditor(document, project, kind)
        try {
            block(editor)
        } finally {
            EditorFactory.getInstance().releaseEditor(editor)
        }
    }

    private fun assertNoHighlights(editor: Editor) = assertEquals(emptyList<Highlight>(), visibleHighlights(editor))

    // Automatic mode (no qs_highlight_on_keys)
    //
    // The caret starts at offset 0 after configure(). A CaretEvent only fires when the caret actually moves, so every
    // test moves the caret to a different offset before reading highlights.

    fun `test automatic mode highlights in both directions when the caret moves`() {
        enableQuickscope()
        val editor = configure("abc def ghi")

        editor.caretModel.moveToOffset(4)

        assertEquals(listOf(primary(0), primary(8)), visibleHighlights(editor))
    }

    fun `test automatic mode replaces highlights on every caret move`() {
        enableQuickscope()
        val editor = configure("abc def ghi")

        editor.caretModel.moveToOffset(4)
        assertEquals(listOf(primary(0), primary(8)), visibleHighlights(editor))

        editor.caretModel.moveToOffset(8)
        assertEquals(listOf(primary(0), primary(4)), visibleHighlights(editor))
    }

    fun `test automatic mode removes highlights in insert mode`() {
        enableQuickscope()
        val editor = configure("abc def ghi")
        editor.caretModel.moveToOffset(4)
        assertEquals(2, visibleHighlights(editor).size)

        typeText(editor, "i")
        assertEquals(Mode.INSERT, injector.vimState.mode)
        editor.caretModel.moveToOffset(8)

        assertNoHighlights(editor)
    }

    fun `test automatic mode restores highlights after leaving insert mode`() {
        enableQuickscope()
        val editor = configure("abc def ghi")

        typeText(editor, "i<Esc>")
        assertEquals(Mode.NORMAL(), injector.vimState.mode)
        editor.caretModel.moveToOffset(4)

        assertEquals(listOf(primary(0), primary(8)), visibleHighlights(editor))
    }

    fun `test automatic mode respects qs_accepted_chars`() {
        setVariable("qs_accepted_chars", vimList("a", "b", "c"))
        enableQuickscope()
        val editor = configure("xyz abc")

        editor.caretModel.moveToOffset(1)

        // Only 'a', 'b', 'c' are accepted, so "xyz" contributes nothing and "abc" is highlighted on 'a'.
        assertEquals("abc", String(ACCEPTED_CHARS))
        assertEquals(listOf(primary(4)), visibleHighlights(editor))
    }

    fun `test qs_accepted_chars ignores non string entries`() {
        setVariable("qs_accepted_chars", VimList(mutableListOf(VimString("a"), VimInt(1), VimString("b"))))
        enableQuickscope()

        assertEquals("ab", String(ACCEPTED_CHARS))
    }

    fun `test automatic mode never highlights in console editors`() {
        enableQuickscope()
        withEditorOfKind(EditorKind.CONSOLE, "abc def ghi") { editor ->
            editor.caretModel.moveToOffset(4)
            assertNoHighlights(editor)
        }
    }

    fun `test automatic mode highlights in diff editors by default`() {
        enableQuickscope()
        withEditorOfKind(EditorKind.DIFF, "abc def ghi") { editor ->
            editor.caretModel.moveToOffset(4)
            assertEquals(listOf(primary(0), primary(8)), visibleHighlights(editor))
        }
    }

    fun `test qs_disable_for_diffs disables highlights in diff editors`() {
        setVariable("qs_disable_for_diffs", VimInt(1))
        enableQuickscope()
        withEditorOfKind(EditorKind.DIFF, "abc def ghi") { editor ->
            editor.caretModel.moveToOffset(4)
            assertNoHighlights(editor)
        }
    }

    fun `test qs_disable_for_diffs does not affect normal editors`() {
        setVariable("qs_disable_for_diffs", VimInt(1))
        enableQuickscope()
        val editor = configure("abc def ghi")

        editor.caretModel.moveToOffset(4)

        assertEquals(listOf(primary(0), primary(8)), visibleHighlights(editor))
    }

    fun `test automatic mode dispose removes highlights and stops listening`() {
        enableQuickscope()
        val editor = configure("abc def ghi")
        editor.caretModel.moveToOffset(4)
        assertEquals(2, visibleHighlights(editor).size)

        extension!!.dispose()
        assertNoHighlights(editor)

        editor.caretModel.moveToOffset(8)
        assertNoHighlights(editor)
    }

    fun `test init twice registers a single listener`() {
        enableQuickscope()
        extension!!.init()
        val editor = configure("abc def ghi")

        editor.caretModel.moveToOffset(4)
        assertEquals(listOf(primary(0), primary(8)), visibleHighlights(editor))

        extension!!.dispose()
        editor.caretModel.moveToOffset(8)
        assertNoHighlights(editor)
    }

    // Key mode (qs_highlight_on_keys): highlights while IdeaVim waits for the motion's character

    fun `test key mode does not highlight on caret move`() {
        enableKeyMode()
        val editor = configure("<caret>abc def ghi")

        editor.caretModel.moveToOffset(4)

        assertNoHighlights(editor)
    }

    fun `test key mode f shows forward highlights while waiting for the character`() {
        enableKeyMode()
        val editor = configure("<caret>abc def ghi")

        typeText(editor, "f")

        assertEquals(listOf(primary(4), primary(8)), visibleHighlights(editor))
        assertEquals(0, editor.caretModel.offset)
    }

    fun `test key mode F shows backward highlights while waiting for the character`() {
        enableKeyMode()
        val editor = configure("abc def <caret>ghi")

        typeText(editor, "F")

        assertEquals(listOf(primary(0), primary(4)), visibleHighlights(editor))
        assertEquals(8, editor.caretModel.offset)
    }

    fun `test key mode t and T highlight like f and F`() {
        enableKeyMode()
        val editor = configure("abc <caret>def ghi")

        typeText(editor, "t")
        assertEquals(listOf(primary(8)), visibleHighlights(editor))

        typeText(editor, "<Esc>T")
        assertEquals(listOf(primary(0)), visibleHighlights(editor))
    }

    fun `test key mode highlights with a pending operator`() {
        enableKeyMode()
        val editor = configure("abc <caret>def ghi")

        typeText(editor, "dF")

        assertEquals(listOf(primary(0)), visibleHighlights(editor))
        assertEquals("abc def ghi", editor.document.text)
    }

    fun `test key mode highlights in visual mode`() {
        enableKeyMode()
        val editor = configure("<caret>abc def ghi")

        typeText(editor, "vf")

        assertEquals(listOf(primary(4), primary(8)), visibleHighlights(editor))
    }

    fun `test key mode respects qs_accepted_chars`() {
        setVariable("qs_accepted_chars", vimList("a", "b", "c"))
        enableKeyMode()
        val editor = configure("<caret>xyz abc")

        typeText(editor, "f")

        assertEquals(listOf(primary(4)), visibleHighlights(editor))
    }

    fun `test key mode only maps the configured keys`() {
        enableKeyMode("f")
        val editor = configure("abc def <caret>ghi")

        typeText(editor, "F")
        assertNoHighlights(editor)

        typeText(editor, "a")
        assertEquals(0, editor.caretModel.offset)
    }

    // Key mode: highlights stay while IdeaVim waits for the argument. Removal after the argument is covered by the
    // parity tests below, which assert no highlights remain.

    fun `test key mode keeps highlights while a digraph argument is being typed`() {
        enableKeyMode()
        val editor = configure("<caret>abc def ghi")

        typeText(editor, "f<C-K>")

        assertEquals(listOf(primary(4), primary(8)), visibleHighlights(editor))
    }

    // Key mode: editors where highlighting is disabled still get a working motion

    fun `test key mode in console editors runs the motion without highlights`() {
        enableKeyMode()
        withEditorOfKind(EditorKind.CONSOLE, "abc def ghi") { editor ->
            typeText(editor, "f")
            assertNoHighlights(editor)

            typeText(editor, "g")
            assertEquals(8, editor.caretModel.offset)
            assertNoHighlights(editor)
        }
    }

    fun `test key mode in diff editors highlights by default`() {
        enableKeyMode()
        withEditorOfKind(EditorKind.DIFF, "abc def ghi") { editor ->
            typeText(editor, "f")
            assertEquals(listOf(primary(4), primary(8)), visibleHighlights(editor))

            typeText(editor, "g")
            assertEquals(8, editor.caretModel.offset)
            assertNoHighlights(editor)
        }
    }

    fun `test key mode with qs_disable_for_diffs runs the motion without highlights in diff editors`() {
        setVariable("qs_disable_for_diffs", VimInt(1))
        enableKeyMode()
        withEditorOfKind(EditorKind.DIFF, "abc def ghi") { editor ->
            typeText(editor, "f")
            assertNoHighlights(editor)

            typeText(editor, "g")
            assertEquals(8, editor.caretModel.offset)
        }
    }

    // Key mode: lifecycle

    fun `test key mode dispose removes the mappings and highlights`() {
        enableKeyMode()
        val editor = configure("<caret>abc def ghi")
        typeText(editor, "f")
        assertEquals(2, visibleHighlights(editor).size)

        extension!!.dispose()
        assertNoHighlights(editor)

        // `f` is plain IdeaVim again: it still works, but does not highlight.
        typeText(editor, "g")
        assertEquals(8, editor.caretModel.offset)
        typeText(editor, "Fa")
        assertEquals(0, editor.caretModel.offset)
        assertNoHighlights(editor)
    }

    fun `test key mode init twice does not duplicate highlights or listeners`() {
        enableKeyMode()
        extension!!.init()
        val editor = configure("<caret>abc def ghi")

        typeText(editor, "f")
        assertEquals(listOf(primary(4), primary(8)), visibleHighlights(editor))

        typeText(editor, "g")
        assertNoHighlights(editor)
    }

    fun `test key mode ignores empty entries in qs_highlight_on_keys`() {
        enableKeyMode("f", "")
        val editor = configure("<caret>abc def ghi")

        typeText(editor, "fg")

        assertEquals(8, editor.caretModel.offset)
    }

    // Key mode: parity with plain IdeaVim
    //
    // Regression tests for https://github.com/joshestein/ideavim-quickscope/issues/17 and a general guard: quickscope
    // must never change what a motion does, only decorate the wait for its argument.

    /**
     * Runs [keys] once in plain IdeaVim and once with quickscope key mode enabled, then asserts the resulting text,
     * caret, selection and mode are identical.
     */
    private fun assertKeysBehaveLikePlainVim(text: String, keys: String) {
        val plain = configure(text)
        typeText(plain, keys)
        val expected = Snapshot.of(plain)
        plain.vim.mode = Mode.NORMAL()
        KeyHandler.getInstance().fullReset(plain.vim)

        enableKeyMode()
        val editor = configure(text)
        typeText(editor, keys)

        assertEquals("after '$keys'", expected, Snapshot.of(editor))
        assertNoHighlights(editor)
    }

    private data class Snapshot(val text: String, val caret: Int, val selection: Pair<Int, Int>?, val mode: Mode) {
        companion object {
            fun of(editor: Editor) = Snapshot(
                editor.document.text,
                editor.caretModel.offset,
                if (editor.selectionModel.hasSelection()) editor.selectionModel.selectionStart to editor.selectionModel.selectionEnd else null,
                injector.vimState.mode,
            )
        }
    }

    fun `test parity f`() = assertKeysBehaveLikePlainVim("<caret>abc def ghi", "fg")
    fun `test parity F`() = assertKeysBehaveLikePlainVim("abc def <caret>ghi", "Fa")
    fun `test parity t`() = assertKeysBehaveLikePlainVim("<caret>abc def ghi", "tg")
    fun `test parity T`() = assertKeysBehaveLikePlainVim("abc def <caret>ghi", "Tc")
    fun `test parity f with count`() = assertKeysBehaveLikePlainVim("<caret>x a a a", "2fa")
    fun `test parity F with count`() = assertKeysBehaveLikePlainVim("a a a <caret>x", "2Fa")
    fun `test parity f not found`() = assertKeysBehaveLikePlainVim("<caret>abc def ghi", "fz")
    fun `test parity f cancelled`() = assertKeysBehaveLikePlainVim("<caret>abc def ghi", "f<Esc>")
    fun `test parity semicolon repeat`() = assertKeysBehaveLikePlainVim("<caret>x a a a", "fa;")
    fun `test parity comma reverse repeat`() = assertKeysBehaveLikePlainVim("<caret>x a a a", "fa;;,")
    fun `test parity semicolon after t does not get stuck`() = assertKeysBehaveLikePlainVim("<caret>x a a a", "ta;")
    fun `test parity visual f`() = assertKeysBehaveLikePlainVim("<caret>abc def ghi", "vfg")
    fun `test parity visual F`() = assertKeysBehaveLikePlainVim("abc def <caret>ghi", "vFa")
    fun `test parity visual line f is a no op`() = assertKeysBehaveLikePlainVim("<caret>abc def ghi", "Vfg")

    fun `test parity df`() = assertKeysBehaveLikePlainVim("one two <caret>three four\nnext line", "dfe")
    fun `test parity dt`() = assertKeysBehaveLikePlainVim("one two <caret>three four\nnext line", "dte")
    fun `test parity dF`() = assertKeysBehaveLikePlainVim("one two three fo<caret>ur\nnext line", "dFw")
    fun `test parity dT`() = assertKeysBehaveLikePlainVim("one two three fo<caret>ur\nnext line", "dTw")
    fun `test parity cF`() = assertKeysBehaveLikePlainVim("one two three fo<caret>ur\nnext line", "cFwX<Esc>")
    fun `test parity yF`() = assertKeysBehaveLikePlainVim("one two three fo<caret>ur\nnext line", "yFw")
    fun `test parity dF at end of line`() = assertKeysBehaveLikePlainVim("one two three fou<caret>r\nnext line", "dFw")
    fun `test parity dF with count`() = assertKeysBehaveLikePlainVim("a b a b a <caret>b", "d2Fa")
    fun `test parity semicolon after dF`() = assertKeysBehaveLikePlainVim("a x a x a x <caret>b", "dFx;")
    fun `test parity dF not found leaves text alone`() = assertKeysBehaveLikePlainVim("abc <caret>def", "dFz")
    fun `test parity dF cancelled`() = assertKeysBehaveLikePlainVim("abc <caret>def", "dF<Esc>")
    fun `test parity dot repeat after df`() = assertKeysBehaveLikePlainVim("<caret>x a a a", "dfa.")
    fun `test parity dot repeat after dF`() = assertKeysBehaveLikePlainVim("a a a a <caret>x", "dFa.")
    fun `test parity dot repeat after cf`() = assertKeysBehaveLikePlainVim("<caret>x a a a", "cfaZ<Esc>w.")
    fun `test parity macro`() = assertKeysBehaveLikePlainVim("<caret>x a a a", "qqfaq@q")
    fun `test parity macro with operator`() = assertKeysBehaveLikePlainVim("<caret>x a x a x a", "qqdfaq@q")
    fun `test parity undo after dF`() = assertKeysBehaveLikePlainVim("one two three fo<caret>ur", "dFwu")
    fun `test parity multiple carets`() = assertKeysBehaveLikePlainVim("<caret>abc def\nabc def", "fd")
}

package com.joshestein.ideavimquickscope

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.maddyhome.idea.vim.api.injector
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
        val created = IdeaVimQuickscopeExtension()
        extension = created
        created.init()
    }

    private fun enableKeyMode(vararg keys: String = arrayOf("f", "F", "t", "T")) {
        setVariable("qs_highlight_on_keys", vimList(*keys))
        enableQuickscope()
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

    // Automatic mode (no qs_highlight_on_keys)
    //
    // The caret starts at offset 0 after configure(). A CaretEvent only fires when the caret actually moves, so every
    // test moves the caret to a different offset before reading highlights.

    fun `test automatic mode highlights both directions on every caret move`() {
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

    fun `test automatic mode removes highlights in replace mode`() {
        enableQuickscope()
        val editor = configure("abc def ghi")
        editor.caretModel.moveToOffset(4)
        assertEquals(2, visibleHighlights(editor).size)

        typeText(editor, "R")
        assertEquals(Mode.REPLACE, injector.vimState.mode)
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

    fun `test qs_accepted_chars replaces the default characters`() {
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

    // Key mode (qs_highlight_on_keys): highlights only while IdeaVim waits for the motion's character

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

    fun `test key mode keeps highlights while a digraph argument is being typed`() {
        enableKeyMode()
        val editor = configure("<caret>abc def ghi")

        typeText(editor, "f<C-K>")

        assertEquals(listOf(primary(4), primary(8)), visibleHighlights(editor))
    }

    fun `test key mode in console editors runs the motion without highlights`() {
        enableKeyMode()
        withEditorOfKind(EditorKind.CONSOLE, "abc def ghi") { editor ->
            typeText(editor, "f")
            assertNoHighlights(editor)

            typeText(editor, "g")
            assertEquals(8, editor.caretModel.offset)
        }
    }

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

    fun `test key mode init twice does not duplicate highlights or leave stale ones`() {
        enableKeyMode()
        val editor = configure("<caret>abc def ghi")
        typeText(editor, "f")

        // `.ideavimrc` reload re-runs init() on the same instance.
        extension!!.init()
        assertNoHighlights(editor)

        typeText(editor, "<Esc>f")
        assertEquals(listOf(primary(4), primary(8)), visibleHighlights(editor))
        typeText(editor, "g")
        assertNoHighlights(editor)
    }

    fun `test init switching from key mode to automatic mode removes the key mappings`() {
        enableKeyMode()
        val editor = configure("<caret>abc def ghi")

        setVariable("qs_highlight_on_keys", VimInt(0))
        extension!!.init()
        typeText(editor, "fg")

        // Plain `f` moved the caret, and the automatic caret listener highlighted the new position.
        assertEquals(8, editor.caretModel.offset)
        assertEquals(listOf(primary(0), primary(4)), visibleHighlights(editor))
    }

    fun `test init switching from automatic mode to key mode removes the automatic highlights`() {
        enableQuickscope()
        val editor = configure("abc def ghi")
        editor.caretModel.moveToOffset(4)
        assertEquals(2, visibleHighlights(editor).size)

        setVariable("qs_highlight_on_keys", vimList("f"))
        extension!!.init()

        assertNoHighlights(editor)
        editor.caretModel.moveToOffset(8)
        assertNoHighlights(editor)
    }

    fun `test released editors are dropped from the highlighter cache`() {
        enableQuickscope()
        var released: Editor? = null
        withEditorOfKind(EditorKind.MAIN_EDITOR, "abc def ghi") { editor ->
            editor.caretModel.moveToOffset(4)
            assertTrue(highlighters.containsKey(editor))
            released = editor
        }

        assertFalse(highlighters.containsKey(released))
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
     * caret, selection and mode are identical and no highlights remain.
     */
    private fun assertKeysBehaveLikePlainVim(text: String, keys: String) {
        val plain = configure(text)
        typeText(plain, keys)
        val expected = Snapshot.of(plain)
        resetVim(plain)

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
    fun `test parity f with count`() = assertKeysBehaveLikePlainVim("<caret>x a a a", "2fa")
    fun `test parity f not found`() = assertKeysBehaveLikePlainVim("<caret>abc def ghi", "fz")
    fun `test parity f cancelled`() = assertKeysBehaveLikePlainVim("<caret>abc def ghi", "f<Esc>")
    fun `test parity semicolon and comma repeat`() = assertKeysBehaveLikePlainVim("<caret>x a a a", "fa;;,")
    fun `test parity visual f`() = assertKeysBehaveLikePlainVim("<caret>abc def ghi", "vfg")

    fun `test parity df`() = assertKeysBehaveLikePlainVim("one two <caret>three four\nnext line", "dfe")
    fun `test parity dF`() = assertKeysBehaveLikePlainVim("one two three fo<caret>ur\nnext line", "dFw")
    fun `test parity dT`() = assertKeysBehaveLikePlainVim("one two three fo<caret>ur\nnext line", "dTw")
    fun `test parity cF`() = assertKeysBehaveLikePlainVim("one two three fo<caret>ur\nnext line", "cFwX<Esc>")
    fun `test parity dF with count and semicolon`() = assertKeysBehaveLikePlainVim("a x a x a x <caret>b", "d2Fx;")
    fun `test parity dF cancelled`() = assertKeysBehaveLikePlainVim("abc <caret>def", "dF<Esc>")
    fun `test parity dot repeat after dF`() = assertKeysBehaveLikePlainVim("a a a a <caret>x", "dFa.")
    fun `test parity macro with operator`() = assertKeysBehaveLikePlainVim("<caret>x a x a x a", "qqdfaq@q")
}

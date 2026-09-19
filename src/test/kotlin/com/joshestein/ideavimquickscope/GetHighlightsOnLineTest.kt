package com.joshestein.ideavimquickscope

/**
 * Tests for the highlight algorithm, [getHighlightsOnLine].
 *
 * Conventions:
 * - The caret position is marked with `<caret>` in the input text.
 * - The character under the caret and the rest of the word containing the caret are never highlighted.
 * - A word's highlight is primary if the word contains a character that appears exactly once between the caret
 *   and the end of the line (in the scan direction). It is secondary if the best character appears exactly twice.
 * - Non-accepted characters (whitespace, punctuation, newline) separate words and are never highlighted.
 */
class GetHighlightsOnLineTest : QuickscopeTestBase() {

    // Highlights are returned in scan order (right to left for BACKWARD). Order is irrelevant, so sort by position.
    private fun forward(text: String) = getHighlightsOnLine(configure(text), Direction.FORWARD).sortedBy { it.position }
    private fun backward(text: String) = getHighlightsOnLine(configure(text), Direction.BACKWARD).sortedBy { it.position }

    // Forward

    fun `test forward highlights first unique character of each following word`() {
        assertEquals(listOf(primary(4), primary(8), primary(12)), forward("<caret>abc def ghi jkl"))
    }

    fun `test forward never highlights the word under the caret`() {
        assertEquals(emptyList<Highlight>(), forward("<caret>abc"))
    }

    fun `test forward does not count the character under the caret`() {
        // 'a' under the caret is not counted, so the 'a' in "abd" is unique and reachable with `fa`.
        assertEquals(listOf(primary(4)), forward("<caret>abc abd"))
    }

    fun `test forward counts characters of the word under the caret`() {
        // 'b' and 'c' in the first word are counted, so "cb" has no unique character and gets a secondary highlight.
        assertEquals(listOf(secondary(4)), forward("<caret>abc cb"))
    }

    fun `test forward uses secondary highlight when no character is unique`() {
        // "ba": 'b' seen twice, 'a' seen once -> primary on 'a'.
        // "ab": 'a' seen twice, 'b' seen three times -> secondary on 'a'.
        assertEquals(listOf(primary(4), secondary(6)), forward("<caret>ab ba ab"))
    }

    fun `test forward has no highlight when every character appears three or more times`() {
        assertEquals(emptyList<Highlight>(), forward("<caret>aaa aaa"))
    }

    fun `test forward stops at the end of the line`() {
        assertEquals(listOf(primary(4)), forward("<caret>abc def\nghi"))
    }

    fun `test forward treats punctuation as a word separator`() {
        // "bar" -> 'b' is unique. "baz" -> 'b' and 'a' were already seen, so 'z' is the unique character.
        assertEquals(listOf(primary(4), primary(10)), forward("<caret>foo.bar(baz)"))
    }

    fun `test forward accepts digits and upper case by default`() {
        assertEquals(listOf(primary(4), primary(8)), forward("<caret>abc 123 ABC"))
    }

    fun `test forward on an empty line has no highlight`() {
        assertEquals(emptyList<Highlight>(), forward("<caret>"))
        assertEquals(emptyList<Highlight>(), forward("abc\n<caret>\ndef"))
    }

    fun `test forward with caret at end of document does not fail`() {
        assertEquals(emptyList<Highlight>(), forward("abc def<caret>"))
    }

    // Backward

    fun `test backward highlights unique character of each preceding word`() {
        assertEquals(listOf(primary(0), primary(4)), backward("abc def <caret>ghi"))
    }

    fun `test backward prefers the leftmost unique character in a word`() {
        // 'b' and the second 'a' are both unique candidates when scanning backward; the leftmost ('a' at 1) wins.
        assertEquals(listOf(primary(1)), backward("aab <caret>xa"))
    }

    fun `test backward uses secondary highlight when no character is unique`() {
        assertEquals(listOf(secondary(0)), backward("ab ab<caret>c"))
    }

    fun `test backward stops at the start of the line`() {
        assertEquals(listOf(primary(4)), backward("xyz\nabc <caret>def"))
    }

    fun `test backward with caret at start of line has no highlight`() {
        assertEquals(emptyList<Highlight>(), backward("<caret>abc def"))
        assertEquals(emptyList<Highlight>(), backward("xyz\n<caret>abc def"))
    }

    // Accepted characters

    fun `test custom accepted characters exclude other characters`() {
        ACCEPTED_CHARS = "abcdefghijklmnopqrstuvwxyz".toCharArray()
        // Upper case and digits are no longer accepted, so "ABC" and "123" contain no candidate characters.
        assertEquals(emptyList<Highlight>(), forward("<caret>abc ABC 123"))
    }

    fun `test custom accepted characters act as word separators`() {
        ACCEPTED_CHARS = "abc".toCharArray()
        // 'x' splits the word: "a" is a word, "c" is a word.
        assertEquals(listOf(primary(3), primary(5)), forward("<caret>ab axc"))
    }
}

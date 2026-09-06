package com.termux.terminal;

/**
 * Tests for visible-screen change tracking (optimization plan task T1.1): the metadata
 * accumulated by {@link TerminalBuffer} so consumers can avoid redrawing rows that did not
 * change. Purely informational; terminal behaviour is unchanged (existing test suite guards that).
 */
public class ScreenChangeTrackingTest extends TerminalTestCase {

    private TerminalBuffer screen() {
        return mTerminal.getScreen();
    }

    /** Writing a single character in place marks only that row as dirty. */
    public void testInPlaceOverwriteMarksOnlyThatRow() {
        withTerminalSized(20, 4);
        enterString("hello world");
        screen().clearScreenChanges();

        enterString("\033[3;5HX"); // 1-based row 3 => index 2, column 5 => index 4.

        TerminalBuffer.ScreenChanges changes = screen().getScreenChanges();
        assertEquals(0, changes.scrollRows);
        assertFalse(changes.fullRedraw);
        assertFalse(screen().isScreenRowDirty(0));
        assertFalse(screen().isScreenRowDirty(1));
        assertTrue(screen().isScreenRowDirty(2));
        assertFalse(screen().isScreenRowDirty(3));
    }

    /** A line feed at the bottom of the screen scrolls the whole screen by one row. */
    public void testLineFeedScrollsWholeScreen() {
        withTerminalSized(10, 3);
        enterString("a\nb\nc"); // fills rows 0,1,2.

        screen().clearScreenChanges();
        enterString("\n"); // scrolls because the cursor is on the last row.

        TerminalBuffer.ScreenChanges changes = screen().getScreenChanges();
        assertEquals(1, changes.scrollRows);
        assertFalse(changes.fullRedraw);
    }

    /** ED2 (erase in display / clear screen) is reported as a full-screen change. */
    public void testEraseInDisplayMarksFullRedraw() {
        withTerminalSized(20, 4);
        enterString("abcdefghijklmnopqrst");
        screen().clearScreenChanges();

        enterString("\033[2J");

        assertTrue(screen().getScreenChanges().fullRedraw);
    }

    /** Resizing rearranges all content and must force a full redraw. */
    public void testResizeMarksFullRedraw() {
        withTerminalSized(10, 4);
        enterString("abcd");
        screen().clearScreenChanges();

        resize(12, 5);

        assertTrue(screen().getScreenChanges().fullRedraw);
    }

    /** Entering and leaving the alternate screen buffer forces a full redraw of the active buffer. */
    public void testAlternateScreenSwitchMarksFullRedraw() {
        withTerminalSized(10, 4);
        enterString("main screen");
        TerminalBuffer mainScreen = screen();
        screen().clearScreenChanges();

        enterString("\033[?1049h");
        TerminalBuffer altScreen = screen();
        assertNotSame(mainScreen, altScreen);
        assertTrue(altScreen.getScreenChanges().fullRedraw);

        altScreen.clearScreenChanges();
        enterString("\033[?1049l");
        assertSame(mainScreen, screen());
        assertTrue(mainScreen.getScreenChanges().fullRedraw);
    }

    /** clearScreenChanges() resets the scroll delta, full flag and row marks. */
    public void testClearScreenChangesResetsEverything() {
        withTerminalSized(10, 3);
        enterString("a\nb\nc");
        screen().clearScreenChanges();

        enterString("\n");
        assertTrue(screen().getScreenChanges().scrollRows > 0);

        screen().clearScreenChanges();
        TerminalBuffer.ScreenChanges changes = screen().getScreenChanges();
        assertEquals(0, changes.scrollRows);
        assertFalse(changes.fullRedraw);
        for (int row = 0; row < mTerminal.mRows; row++)
            assertFalse("Row " + row + " still dirty after clear", screen().isScreenRowDirty(row));
    }

    /** Scrolling a non-full scroll region rewrites only that region: dirty rows, no whole-screen delta. */
    public void testScrollRegionRewriteMarksDirtyRowsWithoutScroll() {
        withTerminalSized(10, 4);
        enterString("row0\nrow1\nrow2\nrow3");
        screen().clearScreenChanges();

        // Set a scroll region on rows index 1..2 (1-based 2..3), move the cursor to its
        // bottom line (index 2) and line feed to scroll only that region.
        enterString("\033[2;3r\033[3;1H\n");

        TerminalBuffer.ScreenChanges changes = screen().getScreenChanges();
        assertEquals(0, changes.scrollRows);
        assertFalse(changes.fullRedraw);
        assertFalse(screen().isScreenRowDirty(0));
        assertTrue(screen().isScreenRowDirty(1));
        assertTrue(screen().isScreenRowDirty(2));
        assertFalse(screen().isScreenRowDirty(3));
    }
}

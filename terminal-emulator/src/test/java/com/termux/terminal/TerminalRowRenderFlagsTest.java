package com.termux.terminal;

import junit.framework.TestCase;

/**
 * Tests for the conservative flags that {@link TerminalRow} tracks for the renderer:
 * {@link TerminalRow#isBlankForRender()}, {@link TerminalRow#hasUniformStyle()} and
 * {@link TerminalRow#getRenderVersion()}. The renderer relies on these to skip blank rows and to
 * invalidate its cached draw runs, so they must stay correct (and conservative) on every mutation.
 */
public class TerminalRowRenderFlagsTest extends TestCase {

	private static final int COLUMNS = 80;
	/** One java char, display width two. */
	private static final int WIDE = 0x679C;
	/** Surrogate pair, display width one. */
	private static final int SURROGATE = 0x1D11E;
	/** Combining character, display width zero. */
	private static final int COMBINING = 0x0308;

	private static final long RED = TextStyle.encode(1, TextStyle.COLOR_INDEX_BACKGROUND, 0);
	private static final long RED_BG = TextStyle.encode(TextStyle.COLOR_INDEX_FOREGROUND, 1, 0);

	private TerminalRow newRow() {
		return new TerminalRow(COLUMNS, TextStyle.NORMAL);
	}

	public void testBlankForRender() {
		TerminalRow row = newRow();
		assertTrue("a fresh row draws nothing", row.isBlankForRender());

		row.setChar(0, 'a', TextStyle.NORMAL);
		assertFalse("a non-space char makes the row visible", row.isBlankForRender());

		// Conservative: once a non-space char was written the row stays "not known blank" even if it
		// is overwritten with spaces again; only clear() resets it.
		row.setChar(0, ' ', TextStyle.NORMAL);
		assertFalse("conservative, not reset by overwriting with a space", row.isBlankForRender());

		row.clear(TextStyle.NORMAL);
		assertTrue("clear() resets to blank", row.isBlankForRender());

		row.setChar(0, ' ', TextStyle.NORMAL);
		assertTrue("writing spaces into a blank row keeps it blank", row.isBlankForRender());
	}

	public void testBlankRowWithNonDefaultStyleIsNotSkipped() {
		TerminalRow cleared = new TerminalRow(COLUMNS, RED_BG);
		assertFalse("blank spaces with a non-default style may still draw a background", cleared.isBlankForRender());
		assertTrue(cleared.hasUniformStyle());
		assertEquals(RED_BG, cleared.getUniformStyle());

		TerminalRow written = newRow();
		written.setChar(0, ' ', RED_BG);
		assertFalse("a space with a non-default style must not be skipped", written.isBlankForRender());

		TerminalRow styled = newRow();
		assertTrue(styled.isBlankForRender());
		styled.markStyleChanged();
		assertFalse("in-place style changes invalidate the blank fast path", styled.isBlankForRender());
	}

	public void testNonOneWidthFlags() {
		TerminalRow row = newRow();
		assertFalse(row.hasNonOneWidthOrSurrogateChars());

		row.setChar(0, 'a', TextStyle.NORMAL);
		assertFalse("pure ASCII stays on the single-width fast path", row.hasNonOneWidthOrSurrogateChars());

		row.setChar(0, WIDE, TextStyle.NORMAL);
		assertTrue("wide chars leave the fast path", row.hasNonOneWidthOrSurrogateChars());
		assertFalse(row.isBlankForRender());

		row.clear(TextStyle.NORMAL);
		assertFalse("clear() returns to the fast path", row.hasNonOneWidthOrSurrogateChars());
		assertTrue(row.isBlankForRender());

		row.setChar(0, SURROGATE, TextStyle.NORMAL);
		assertTrue("surrogate pairs leave the fast path", row.hasNonOneWidthOrSurrogateChars());

		row.clear(TextStyle.NORMAL);
		row.setChar(0, COMBINING, TextStyle.NORMAL);
		assertTrue("zero-width chars leave the fast path", row.hasNonOneWidthOrSurrogateChars());
	}

	public void testUniformStyle() {
		TerminalRow row = newRow();
		assertTrue(row.hasUniformStyle());
		assertEquals(TextStyle.NORMAL, row.getUniformStyle());

		row.setChar(0, 'a', TextStyle.NORMAL);
		row.setChar(1, 'b', TextStyle.NORMAL);
		assertTrue("writing the common style keeps the row uniform", row.hasUniformStyle());

		row.setChar(2, 'c', RED);
		assertFalse("a different style breaks uniformity", row.hasUniformStyle());

		row.clear(TextStyle.NORMAL);
		assertTrue("clear() restores uniformity", row.hasUniformStyle());
		assertEquals(TextStyle.NORMAL, row.getUniformStyle());

		row.clear(RED);
		assertTrue(row.hasUniformStyle());
		assertEquals(RED, row.getUniformStyle());

		row.markStyleChanged();
		assertFalse("in-place style changes break uniformity", row.hasUniformStyle());
	}

	public void testCopyIntervalBreaksUniformity() {
		TerminalRow source = newRow();
		source.setChar(0, 'x', RED);

		TerminalRow destination = newRow();
		assertTrue(destination.hasUniformStyle());
		destination.copyInterval(source, 0, 1, 0);
		assertFalse("copying a cell with a different style breaks uniformity", destination.hasUniformStyle());
	}

	public void testRenderVersionIncrements() {
		TerminalRow row = newRow();
		int afterCreate = row.getRenderVersion();

		row.setChar(0, 'a', TextStyle.NORMAL);
		int afterSetChar = row.getRenderVersion();
		assertTrue("setChar() bumps the render version", afterSetChar > afterCreate);

		row.clear(TextStyle.NORMAL);
		int afterClear = row.getRenderVersion();
		assertTrue("clear() bumps the render version", afterClear > afterSetChar);

		row.markStyleChanged();
		int afterStyle = row.getRenderVersion();
		assertTrue("markStyleChanged() bumps the render version", afterStyle > afterClear);

		// Pure reads must not invalidate the renderer cache.
		row.getRenderVersion();
		row.isBlankForRender();
		row.hasUniformStyle();
		row.getUniformStyle();
		row.hasNonOneWidthOrSurrogateChars();
		row.getStyle(0);
		row.getSpaceUsed();
		row.findStartOfColumn(0);
		assertEquals("reads do not bump the render version", afterStyle, row.getRenderVersion());
	}

}

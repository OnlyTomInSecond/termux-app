package com.termux.terminal;

import junit.framework.TestCase;

import java.util.Random;

/**
 * Differential regression test for the cached column→char boundary lookup in {@link TerminalRow}
 * (findStartOfColumn / wideDisplayCharacterStartingAt). A reference implementation replicating the
 * original uncached scan semantics is compared against the row after every mutation, over a
 * randomized walk of wide/narrow/surrogate/combining writes.
 */
public class TerminalRowBoundaryCacheTest extends TestCase {

    private static final int COLUMNS = 80;

    private TerminalRow row;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        row = new TerminalRow(COLUMNS, TextStyle.NORMAL);
    }

    /** Reference implementation: the original full scan, kept free of any caching. */
    private int referenceFindStartOfColumn(int column) {
        char[] text = row.mText;
        int spaceUsed = row.getSpaceUsed();
        if (column == COLUMNS) return spaceUsed;
        int currentColumn = 0;
        int currentCharIndex = 0;
        while (true) {
            int newCharIndex = currentCharIndex;
            char c = text[newCharIndex++];
            boolean isHigh = Character.isHighSurrogate(c);
            int codePoint = isHigh ? Character.toCodePoint(c, text[newCharIndex++]) : c;
            int wcwidth = WcWidth.width(codePoint);
            if (wcwidth > 0) {
                currentColumn += wcwidth;
                if (currentColumn == column) {
                    while (newCharIndex < spaceUsed) {
                        if (Character.isHighSurrogate(text[newCharIndex])) {
                            if (WcWidth.width(Character.toCodePoint(text[newCharIndex], text[newCharIndex + 1])) <= 0) {
                                newCharIndex += 2;
                            } else {
                                break;
                            }
                        } else if (WcWidth.width(text[newCharIndex]) <= 0) {
                            newCharIndex++;
                        } else {
                            break;
                        }
                    }
                    return newCharIndex;
                } else if (currentColumn > column) {
                    return currentCharIndex;
                }
            }
            currentCharIndex = newCharIndex;
        }
    }

    /** Reference implementation of the original wideDisplayCharacterStartingAt full scan. */
    private boolean referenceWideStartingAt(int column) {
        char[] text = row.mText;
        for (int currentCharIndex = 0, currentColumn = 0; currentCharIndex < row.getSpaceUsed(); ) {
            char c = text[currentCharIndex++];
            int codePoint = Character.isHighSurrogate(c) ? Character.toCodePoint(c, text[currentCharIndex++]) : c;
            int wcwidth = WcWidth.width(codePoint);
            if (wcwidth > 0) {
                if (currentColumn == column && wcwidth == 2) return true;
                currentColumn += wcwidth;
                if (currentColumn > column) return false;
            }
        }
        return false;
    }

    /** The cached row's answer for "does a wide char start at column", via the public API. */
    private boolean cachedWideStartingAt(int column) {
        if (column < 0 || column >= COLUMNS) return false;
        int cellStart = row.findStartOfColumn(column);
        boolean cellStartsAtColumn = (column == 0) || row.findStartOfColumn(column) != row.findStartOfColumn(column - 1);
        return cellStartsAtColumn && WcWidth.width(row.mText, cellStart) == 2;
    }

    private void assertBoundariesMatch() {
        // Exercise both cached and uncached column lookups, including out-of-screen edge cases.
        for (int column = 0; column <= COLUMNS + 2; column++) {
            assertEquals("Column " + column + " boundary mismatch",
                referenceFindStartOfColumn(column), row.findStartOfColumn(column));
        }
    }

    private void assertWideStartsMatch() {
        for (int column = -1; column <= COLUMNS + 1; column++) {
            assertEquals("Wide-start mismatch at column " + column,
                referenceWideStartingAt(column), cachedWideStartingAt(column));
        }
    }

    public void testSequentialCjkFill() {
        for (int col = 0; col + 1 < COLUMNS; col += 2) {
            row.setChar(col, '界', TextStyle.NORMAL);
            assertBoundariesMatch();
            assertWideStartsMatch();
        }
        // Overwrite every other wide char with ASCII, shrinking content.
        for (int col = 0; col + 1 < COLUMNS; col += 4) {
            row.setChar(col, 'x', TextStyle.NORMAL);
            assertBoundariesMatch();
            assertWideStartsMatch();
        }
    }

    public void testRandomizedMutationWalk() {
        Random random = new Random(1234567);
        int[] codePoints = {
            'a', 'b', ' ', '界', '测', '\u4e2d', '\u6587',
            0x1F680, 0x1F389, // surrogate pairs (width 2)
            0x0301, 0x0308, // combining accents
            0x2705, 0x26A1, // width 2 single chars
        };
        for (int iteration = 0; iteration < 20000; iteration++) {
            int column = random.nextInt(COLUMNS);
            int codePoint = codePoints[random.nextInt(codePoints.length)];
            // Guard against the "combining char in column 0 attaches to the space cell" edge
            // and the "wide char in last column" exception, both expected setChar behavior.
            try {
                row.setChar(column, codePoint, TextStyle.NORMAL);
            } catch (IllegalArgumentException e) {
                // Wide char in the very last column is rejected by design.
            }
            if (iteration % 17 == 0) assertBoundariesMatch();
            if (iteration % 53 == 0) assertWideStartsMatch();
            if (iteration % 4096 == 0) {
                row.clear(TextStyle.NORMAL);
                assertBoundariesMatch();
                assertWideStartsMatch();
            }
        }
        assertBoundariesMatch();
        assertWideStartsMatch();
    }

    public void testCopyIntervalSelfAndOther() {
        Random random = new Random(99);
        TerminalRow other = new TerminalRow(COLUMNS, TextStyle.NORMAL);
        String pool = "界测中文字符a";
        for (int i = 0; i < 2000; i++) {
            try {
                row.setChar(random.nextInt(COLUMNS), pool.charAt(random.nextInt(pool.length())), TextStyle.NORMAL);
                other.setChar(random.nextInt(COLUMNS), pool.charAt(random.nextInt(pool.length())), TextStyle.NORMAL);
            } catch (IllegalArgumentException e) {
                // Wide char in the very last column is rejected by setChar design.
            }
            if (i % 50 == 0) {
                int x1 = random.nextInt(COLUMNS - 4);
                int x2 = x1 + 1 + random.nextInt(COLUMNS - x1 - 1);
                int dx = random.nextInt(COLUMNS - (x2 - x1) - 1);
                try {
                    row.copyInterval(other, x1, x2, dx);
                } catch (IllegalArgumentException e) {
                    // copyInterval delegates to setChar, which rejects wide chars in the last
                    // column by design; the emulator never produces such copies.
                }
                assertBoundariesMatch();
                assertWideStartsMatch();
                try {
                    row.copyInterval(row, dx, dx + (x2 - x1), 0);
                } catch (IllegalArgumentException e) {
                    // Same last-column edge case as above.
                }
                assertBoundariesMatch();
                assertWideStartsMatch();
            }
        }
    }
}
